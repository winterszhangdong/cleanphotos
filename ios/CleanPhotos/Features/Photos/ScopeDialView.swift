import SwiftUI
import UIKit

/// 纯 UIKit 范围刻度视图：滚动与横滑过程中由 `CADisplayLink` 直接驱动 `transform` 与 `alpha`，零 SwiftUI 重组。
final class ScopeDialUIView: UIView {
    private let contentView: UIView = {
        let v = UIView()
        v.backgroundColor = .clear
        v.isUserInteractionEnabled = false
        v.clipsToBounds = true
        return v
    }()
    private let trackView = UIView()
    private let indicatorView: UIView = {
        let v = UIView()
        v.backgroundColor = DarkroomUIColors.paper
        v.layer.cornerRadius = DarkroomDimens.navIndicatorHeight / 2
        v.isUserInteractionEnabled = false
        return v
    }()
    private let maskLayer: CAGradientLayer = {
        let g = CAGradientLayer()
        g.colors = [
            UIColor.clear.cgColor,
            UIColor.black.cgColor,
            UIColor.black.cgColor,
            UIColor.clear.cgColor,
        ]
        g.startPoint = CGPoint(x: 0, y: 0.5)
        g.endPoint = CGPoint(x: 1, y: 0.5)
        return g
    }()

    private var labels: [UILabel] = []
    private(set) var scopes: [LibraryScope] = []
    private(set) var widths: [CGFloat] = []
    private var chinese = true
    private(set) var progress: CGFloat = 0
    private var contentWidth: CGFloat = 0
    private var lastMaskWidth: CGFloat = 0
    private var dragStartProgress: CGFloat = 0

    private lazy var panRecognizer = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
    private lazy var tapRecognizer = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))

    var onSelectScope: ((Int) -> Void)?
    /// translationX (手指平移), dialStride, ended, velocityX (pt/s)
    var onDialDrag: ((CGFloat, CGFloat, Bool, CGFloat) -> Void)?

    var isDialInteractionEnabled: Bool = true {
        didSet {
            panRecognizer.isEnabled = isDialInteractionEnabled
            tapRecognizer.isEnabled = isDialInteractionEnabled
        }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = DarkroomUIColors.room
        isOpaque = true
        clipsToBounds = true

        addSubview(contentView)
        contentView.layer.mask = maskLayer

        trackView.isUserInteractionEnabled = false
        contentView.addSubview(trackView)
        contentView.addSubview(indicatorView)

        panRecognizer.maximumNumberOfTouches = 1
        addGestureRecognizer(panRecognizer)
        addGestureRecognizer(tapRecognizer)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var intrinsicContentSize: CGSize {
        CGSize(width: UIView.noIntrinsicMetric, height: DarkroomDimens.scopeRowHeight)
    }

    func setScopes(_ nextScopes: [LibraryScope], chinese nextChinese: Bool) {
        guard nextScopes != scopes || nextChinese != chinese else { return }
        scopes = nextScopes
        chinese = nextChinese

        labels.forEach { $0.removeFromSuperview() }
        labels.removeAll(keepingCapacity: true)
        widths.removeAll(keepingCapacity: true)

        let font = DarkroomUIFonts.notoSC(size: 14, weight: .medium)
        let gap = DarkroomDimens.scopeDialGap
        var x: CGFloat = 0
        for (i, scope) in nextScopes.enumerated() {
            let title = PhotoPageCopy.scopeName(scope, chinese: nextChinese)
            let label = UILabel()
            label.font = font
            label.textColor = DarkroomUIColors.paper
            label.text = title
            label.numberOfLines = 1
            label.isAccessibilityElement = true
            label.accessibilityLabel = title
            let size = label.sizeThatFits(CGSize(width: CGFloat.greatestFiniteMagnitude, height: DarkroomDimens.scopeRowHeight))
            let w = ceil(size.width)
            let h = ceil(size.height)
            label.frame = CGRect(x: x, y: DarkroomDimens.scopeDialTextTop, width: w, height: h)
            label.alpha = ScopeDialMath.alpha(distance: abs(CGFloat(i) - progress))
            trackView.addSubview(label)
            labels.append(label)
            widths.append(w)
            x += w
            if i + 1 < nextScopes.count { x += gap }
        }
        contentWidth = max(1, x)
        setNeedsLayout()
        applyProgressTransform()
    }

    func setProgress(_ nextProgress: CGFloat) {
        progress = nextProgress
        applyProgressTransform()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let b = bounds
        contentView.frame = b
        let h = max(b.height, DarkroomDimens.scopeRowHeight)
        trackView.bounds = CGRect(x: 0, y: 0, width: contentWidth, height: h)
        trackView.center = CGPoint(x: contentWidth / 2, y: h / 2)

        indicatorView.frame = CGRect(
            x: (b.width - DarkroomDimens.navIndicatorWidth) / 2,
            y: DarkroomDimens.scopeDialIndicatorTop,
            width: DarkroomDimens.navIndicatorWidth,
            height: DarkroomDimens.navIndicatorHeight
        )

        if abs(b.width - lastMaskWidth) > 0.5 || maskLayer.bounds.size != b.size {
            lastMaskWidth = b.width
            maskLayer.frame = contentView.bounds
            let fade = Double(DarkroomDimens.scopeDialEdgeFade / max(b.width, 1))
            let leftStop = min(0.49, max(0, fade))
            let rightStop = max(0.51, min(1, 1 - fade))
            maskLayer.locations = [0, NSNumber(value: leftStop), NSNumber(value: rightStop), 1]
        }

        applyProgressTransform()
    }

    private func applyProgressTransform() {
        guard !widths.isEmpty else { return }
        let vpCenter = bounds.width > 0 ? bounds.width / 2 : UIScreen.main.bounds.width / 2
        let tx = ScopeDialMath.translationX(
            viewportCenter: vpCenter,
            progress: progress,
            widths: widths,
            gap: DarkroomDimens.scopeDialGap
        )
        trackView.transform = CGAffineTransform(translationX: tx, y: 0)
        for i in labels.indices {
            labels[i].alpha = ScopeDialMath.alpha(distance: abs(CGFloat(i) - progress))
        }
    }

    @objc private func handlePan(_ g: UIPanGestureRecognizer) {
        let dx = g.translation(in: self).x
        let vx = g.velocity(in: self).x
        switch g.state {
        case .began:
            dragStartProgress = progress
            let stride = ScopeDialMath.pageStride(progress: dragStartProgress, widths: widths, gap: DarkroomDimens.scopeDialGap)
            onDialDrag?(dx, stride, false, vx)
        case .changed:
            let stride = ScopeDialMath.pageStride(progress: dragStartProgress, widths: widths, gap: DarkroomDimens.scopeDialGap)
            onDialDrag?(dx, stride, false, vx)
        case .ended, .cancelled, .failed:
            let stride = ScopeDialMath.pageStride(progress: dragStartProgress, widths: widths, gap: DarkroomDimens.scopeDialGap)
            onDialDrag?(dx, stride, true, vx)
        default:
            break
        }
    }

    @objc private func handleTap(_ g: UITapGestureRecognizer) {
        guard g.state == .ended, !widths.isEmpty else { return }
        let contentX = g.location(in: trackView).x
        let hit = ScopeDialMath.hitIndex(
            contentX: contentX,
            widths: widths,
            gap: DarkroomDimens.scopeDialGap,
            minTouch: DarkroomDimens.minTouch
        )
        if hit >= 0 {
            onSelectScope?(hit)
        }
    }
}

/// 将纯 UIKit 范围刻度挂载到 PhotosView 顶栏中并绑定给 `PhotosPagerController`。
struct ScopeDialView: UIViewRepresentable {
    var scopes: [LibraryScope]
    var chinese: Bool
    var interactionEnabled: Bool
    var onAttach: (ScopeDialUIView) -> Void
    var onSelect: (Int) -> Void
    var onDrag: (CGFloat, CGFloat, Bool, CGFloat) -> Void

    func makeUIView(context: Context) -> ScopeDialUIView {
        let view = ScopeDialUIView()
        view.setScopes(scopes, chinese: chinese)
        view.isDialInteractionEnabled = interactionEnabled
        view.onSelectScope = onSelect
        view.onDialDrag = onDrag
        onAttach(view)
        return view
    }

    func updateUIView(_ uiView: ScopeDialUIView, context: Context) {
        uiView.setScopes(scopes, chinese: chinese)
        uiView.isDialInteractionEnabled = interactionEnabled
        uiView.onSelectScope = onSelect
        uiView.onDialDrag = onDrag
        onAttach(uiView)
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: ScopeDialUIView, context: Context) -> CGSize? {
        CGSize(width: proposal.width ?? UIScreen.main.bounds.width, height: DarkroomDimens.scopeRowHeight)
    }
}

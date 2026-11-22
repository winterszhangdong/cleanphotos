import SwiftUI
import Photos

/// 当天进度条（ui-design 4.2 / ReviewChrome.kt）：2pt 高、3pt 间隔，一格一张。
/// 收藏琥珀实心；取消收藏空心琥珀格；移走退影灰·深；看过保留相纸白 78%；
/// 当前 #FFF6E8 80% + 6pt 发光、1.6s 呼吸；未处理虚线（3pt / 2pt，相纸白 34%）。超过 40 张改连续比例条。
struct ReviewProgressBar: View {
    let cells: [ReviewSessionModel.CellState]
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// 分格模式的当前格交给 BreathingCurrentCell。连续比例条（> 40 张）的当前格用 0.45。
    private static let current = Color(red: 1, green: 246/255, blue: 232/255)
    private static let kept = DarkroomColors.paper.opacity(0.78)
    private static let pending = DarkroomColors.paper.opacity(0.34)

    var body: some View {
        Canvas { ctx, size in
            let n = max(cells.count, 1)
            let h = size.height
            if cells.count > 40 {
                drawDashed(&ctx, CGRect(origin: .zero, size: size))
                let per = size.width / CGFloat(n)
                for (i, s) in cells.enumerated() {
                    let r = CGRect(x: CGFloat(i) * per, y: 0, width: per + 0.5, height: h)
                    switch s {
                    case .favorited: ctx.fill(Path(r), with: .color(DarkroomColors.amber))
                    case .unfavorited: ctx.fill(Path(r), with: .color(DarkroomColors.amber.opacity(0.4)))
                    case .trashed: ctx.fill(Path(r), with: .color(DarkroomColors.fadeDeep))
                    case .kept: ctx.fill(Path(r), with: .color(Self.kept))
                    default: break
                    }
                }
                if let c = cells.firstIndex(of: .current) {
                    let r = CGRect(x: CGFloat(c) * per - 1, y: 0, width: max(per, 3), height: h)
                    ctx.fill(Path(roundedRect: r, cornerRadius: h / 2), with: .color(Self.current.opacity(0.45)))
                }
                return
            }
            let gap: CGFloat = 3
            let w = (size.width - gap * CGFloat(n - 1)) / CGFloat(n)
            for (i, s) in cells.enumerated() {
                let r = CGRect(x: CGFloat(i) * (w + gap), y: 0, width: w, height: h)
                let rr = Path(roundedRect: r, cornerRadius: h / 2)
                switch s {
                case .pending:
                    drawDashed(&ctx, r)
                case .current:
                    // 发光 + 实心在上面那层 BreathingCurrentCell 里画（1.6s 呼吸由渲染服务驱动，不占主线程）
                    drawDashed(&ctx, r)
                case .kept:
                    ctx.fill(rr, with: .color(Self.kept))
                case .favorited:
                    ctx.fill(rr, with: .color(DarkroomColors.amber))
                case .trashed:
                    ctx.fill(rr, with: .color(DarkroomColors.fadeDeep))
                case .unfavorited:
                    ctx.stroke(Path(roundedRect: r.insetBy(dx: 0.5, dy: 0.5), cornerRadius: h / 2),
                               with: .color(DarkroomColors.amber), lineWidth: 1)
                }
            }
        }
        .frame(height: 2)
        .overlay(alignment: .topLeading) {
            if cells.count <= 40, let c = cells.firstIndex(of: .current) {
                GeometryReader { geo in
                    let n = max(cells.count, 1)
                    let gap: CGFloat = 3
                    let w = (geo.size.width - gap * CGFloat(n - 1)) / CGFloat(n)
                    let pad = BreathingCurrentCell.pad
                    BreathingCurrentCell(breathe: !reduceMotion)
                        .frame(width: w + 2 * pad, height: geo.size.height + 2 * pad)
                        .offset(x: CGFloat(c) * (w + gap) - pad, y: -pad)
                }
                .allowsHitTesting(false)
                .accessibilityHidden(true)
                // 换页时当前格直接跳到新位置（和原来 Canvas 里一样），不跟翻页弹簧插值
                .transaction { $0.animation = nil }
            }
        }
    }

    private func drawDashed(_ ctx: inout GraphicsContext, _ r: CGRect) {
        var x: CGFloat = 0
        while x < r.width {
            let seg = CGRect(x: r.minX + x, y: r.minY, width: min(3, r.width - x), height: r.height)
            ctx.fill(Path(seg), with: .color(Self.pending))
            x += 5
        }
    }
}

/// 清理页返回。模糊圆在进入时就建好，藏着（`isHidden`，不采样后面的照片）。
/// 点开详情只改这颗的透明度，不再在那一帧创建 `UIVisualEffectView`。
struct ReviewBackButton: UIViewRepresentable {
    var shown: Bool
    var action: () -> Void

    func makeUIView(context: Context) -> ReviewBackControl {
        let view = ReviewBackControl()
        view.addTarget(context.coordinator, action: #selector(Coordinator.tap), for: .touchUpInside)
        return view
    }

    func updateUIView(_ uiView: ReviewBackControl, context: Context) {
        context.coordinator.action = action
        let animated = uiView.hasAppeared && !context.transaction.disablesAnimations
        uiView.setShown(shown, animated: animated)
        uiView.hasAppeared = true
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: ReviewBackControl, context: Context) -> CGSize? {
        CGSize(width: 40, height: 40)
    }

    func makeCoordinator() -> Coordinator { Coordinator(action) }

    final class Coordinator: NSObject {
        var action: () -> Void
        init(_ action: @escaping () -> Void) { self.action = action }
        @objc func tap() { action() }
    }
}

final class ReviewBackControl: UIControl {
    var hasAppeared = false
    private var shown = false
    private let blur: UIVisualEffectView = {
        let view = UIVisualEffectView(effect: UIBlurEffect(style: .dark))
        view.isUserInteractionEnabled = false
        view.clipsToBounds = true
        return view
    }()
    private let tint: UIView = {
        let view = UIView()
        view.isUserInteractionEnabled = false
        view.backgroundColor = DarkroomUIColors.glass
        return view
    }()
    private let icon: UIImageView = {
        let view = UIImageView(image: DarkroomIcons.template(.back, size: 18))
        view.tintColor = DarkroomUIColors.paper
        view.contentMode = .center
        view.isUserInteractionEnabled = false
        return view
    }()

    override init(frame: CGRect) {
        super.init(frame: frame)
        isHidden = true
        alpha = 0
        isAccessibilityElement = true
        accessibilityTraits = .button
        blur.contentView.addSubview(tint)
        blur.layer.borderWidth = 1
        blur.layer.borderColor = DarkroomUIColors.glassRim.cgColor
        addSubview(blur)
        addSubview(icon)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        let side = min(bounds.width, bounds.height)
        let rect = CGRect(x: (bounds.width - side) / 2, y: (bounds.height - side) / 2, width: side, height: side)
        blur.frame = rect
        blur.layer.cornerRadius = side / 2
        tint.frame = blur.contentView.bounds
        icon.frame = rect
    }

    func setShown(_ next: Bool, animated: Bool) {
        guard next != shown else { return }
        shown = next
        isUserInteractionEnabled = next
        isAccessibilityElement = next
        if !animated {
            alpha = next ? 1 : 0
            isHidden = !next
            return
        }
        if next {
            isHidden = false
            UIView.animate(withDuration: DarkroomMotion.short, delay: 0, options: [.curveEaseOut, .allowUserInteraction, .beginFromCurrentState]) {
                self.alpha = 1
            }
        } else {
            UIView.animate(withDuration: DarkroomMotion.micro, delay: 0, options: [.curveEaseIn, .allowUserInteraction, .beginFromCurrentState]) {
                self.alpha = 0
            } completion: { finished in
                if finished, !self.shown { self.isHidden = true }
            }
        }
    }
}

/// 40pt 玻璃圆钮（.rv-top 返回）：#141210 56% + 14% 描边 + 模糊。
struct GlassCircleButton: View {
    var icon: DarkroomIcon
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            DarkroomIconView(icon: icon, size: 18)
                .foregroundStyle(DarkroomColors.paper)
                .frame(width: 40, height: 40)
                .background {
                    Circle()
                        .fill(DarkroomColors.glass)
                        .background { DarkroomBlurBackdrop().clipShape(Circle()) }
                        .overlay(Circle().strokeBorder(DarkroomColors.glassRim, lineWidth: 1))
                }
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
    }
}

/// .rv-meta：点照片后才出现的 mono 10.5 相纸白 62%「名称 · 大小」。没有方向提示。
struct ReviewMetaRow: View {
    let photo: PhotoItem
    /// 进度行没出来时，视频把时长接在文件信息后面。
    var duration: String? = nil

    @State private var loadedInfo: AssetSizeCache.Info?
    @State private var loadedId = ""

    var body: some View {
        Text(fileLine)
            .font(DarkroomFonts.plexMono(size: 10.5))
            .foregroundStyle(DarkroomColors.paper.opacity(0.62))
            .lineLimit(1)
            .frame(maxWidth: .infinity, alignment: .leading)
            .transaction { if !PerfLegacy.on { $0.animation = nil } } // 翻页时直接换字，不做隐式文字插值
            .task(id: photo.id) {
                if AssetSizeCache.shared.cachedInfo(photo.id) != nil {
                    if loadedId != photo.id {
                        loadedInfo = nil
                        loadedId = ""
                    }
                    return
                }
                let info = await AssetSizeCache.shared.loadInfo(for: photo.asset)
                guard !Task.isCancelled else { return }
                loadedId = photo.id
                loadedInfo = info
            }
    }

    private var fileLine: String {
        let info = AssetSizeCache.shared.cachedInfo(photo.id) ?? (loadedId == photo.id ? loadedInfo : nil)
        let name = info?.name ?? "—"
        let bytes = info?.bytes ?? 0
        let size = bytes > 0 ? " · " + FileSizeFormatter.format(bytes) : ""
        let length = duration.map { " · " + $0 } ?? ""
        return name + size + length
    }
}

/// 当前格（Android DayCells glow + 1.6s 呼吸 1 ↔ 0.55，EaseInOut 往返）：#FFF6E8 80% 实心 + 外扩 2pt、模糊 3 的 35% 发光。
/// 呼吸用 CABasicAnimation 改整层 opacity —— 由渲染服务逐帧插值，主线程不参与（之前 Canvas 里的 Bool 状态插值不了，呼吸从没动过）。
/// 减弱动态效果：常亮（Android animations 关时 breathAlpha = 1）。
struct BreathingCurrentCell: UIViewRepresentable {
    static let pad: CGFloat = 6
    var breathe: Bool

    func makeUIView(context: Context) -> CellView { CellView() }
    func updateUIView(_ v: CellView, context: Context) { v.breathe = breathe }

    final class CellView: UIView {
        private let glow = CALayer()
        private let fill = CAShapeLayer()
        private static let color = UIColor(red: 1, green: 246/255, blue: 232/255, alpha: 1)
        private static let key = "cp.breath"
        var breathe = true { didSet { if oldValue != breathe { updateAnimation() } } }

        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
            backgroundColor = .clear
            glow.shadowColor = Self.color.cgColor
            glow.shadowOpacity = 0.35
            glow.shadowRadius = 3
            glow.shadowOffset = .zero
            fill.fillColor = Self.color.withAlphaComponent(0.8).cgColor
            layer.addSublayer(glow)
            layer.addSublayer(fill)
        }
        required init?(coder: NSCoder) { fatalError() }

        override func layoutSubviews() {
            super.layoutSubviews()
            CATransaction.begin(); CATransaction.setDisableActions(true)
            let p = BreathingCurrentCell.pad
            let r = bounds.insetBy(dx: p, dy: p)
            glow.frame = bounds
            glow.shadowPath = UIBezierPath(roundedRect: r.insetBy(dx: -2, dy: -2), cornerRadius: r.height).cgPath
            fill.frame = bounds
            fill.path = UIBezierPath(roundedRect: r, cornerRadius: r.height / 2).cgPath
            CATransaction.commit()
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            updateAnimation()
        }

        private func updateAnimation() {
            layer.removeAnimation(forKey: Self.key)
            guard breathe, window != nil else { return }
            let a = CABasicAnimation(keyPath: "opacity")
            a.fromValue = 1.0
            a.toValue = 0.55
            a.duration = 0.8
            a.autoreverses = true
            a.repeatCount = .infinity
            a.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
            a.isRemovedOnCompletion = false // 切后台再回来不丢
            // 呼吸只是 2pt 小格的透明度缓变：30fps 看不出区别；不设上限时这条无限动画让渲染服务整树每帧重绘（CP-Perf 实测渲染服务 CPU +37%、上下滑掉帧变多）
            a.preferredFrameRateRange = CAFrameRateRange(minimum: 15, maximum: 30, preferred: 30)
            layer.add(a, forKey: Self.key)
        }
    }
}

import SwiftUI
import Photos

/// 当天进度条（ui-design 4.2 / ReviewChrome.kt）：2pt 高、3pt 间隔，一格一张。
/// 收藏琥珀实心；取消收藏空心琥珀格；移走退影灰·深；看过保留相纸白 78%；
/// 当前 #FFF6E8 80% + 6pt 发光、1.6s 呼吸；未处理虚线（3pt / 2pt，相纸白 34%）。超过 40 张改连续比例条。
struct ReviewProgressBar: View {
    let cells: [ReviewSessionModel.CellState]
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// 连续比例条（> 40 张）的当前格沿用原来的常亮值；分格模式的当前格交给 BreathingCurrentCell（Core Animation 呼吸）。
    private let breath = true

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
                    ctx.fill(Path(roundedRect: r, cornerRadius: h / 2), with: .color(Self.current.opacity(breath ? 0.45 : 0.8)))
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

/// 40pt 玻璃圆钮（.rv-top 返回 / 更多）：#141210 56% + 14% 描边 + 模糊。
struct GlassCircleButton: View {
    var systemName: String
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(DarkroomColors.paper)
                .frame(width: 40, height: 40)
                .background {
                    Circle()
                        .fill(DarkroomColors.glass)
                        .background(.ultraThinMaterial, in: Circle())
                        .overlay(Circle().strokeBorder(DarkroomColors.glassRim, lineWidth: 1))
                }
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
    }
}

/// .rv-meta：mono 10.5 相纸白 62%「名称 · 大小」+ 两颗方向提示小点（琥珀 / 退影灰）。
struct ReviewMetaRow: View {
    let photo: PhotoItem
    let scope: ReviewScope
    let locale: Locale
    var showHints: Bool = true

    var body: some View {
        HStack {
            Text(fileLine)
                .font(DarkroomFonts.plexMono(size: 10.5))
                .foregroundStyle(DarkroomColors.paper.opacity(0.62))
                .lineLimit(1)
                .transaction { if !PerfLegacy.on { $0.animation = nil } } // 翻页时直接换字，不做隐式文字插值
            Spacer(minLength: 8)
            if showHints {
                HStack(spacing: 12) {
                    hint(DarkroomColors.amber, L10n.string(scope == .favorites ? "hint_up_unfavorite" : "hint_up_favorite", locale: locale))
                    hint(DarkroomColors.fade, L10n.string("hint_down", locale: locale))
                }
            }
        }
    }

    private var fileLine: String {
        // 文件名 + 大小走 AssetSizeCache（翻页前 prepare() 已在后台预取前后几张），不在主线程查 PHAssetResource
        let name = AssetSizeCache.shared.name(for: photo.asset) ?? "—"
        let size = photo.byteSize > 0 ? " · " + FileSizeFormatter.format(photo.byteSize) : ""
        return name + size
    }

    private func hint(_ color: Color, _ label: String) -> some View {
        HStack(spacing: 4) {
            Circle().fill(color).frame(width: 6, height: 6)
            Text(label)
                .font(DarkroomFonts.plexMono(size: 10.5))
                .foregroundStyle(DarkroomColors.paper.opacity(0.62))
        }
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

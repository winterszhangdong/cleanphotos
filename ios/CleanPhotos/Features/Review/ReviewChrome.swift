import SwiftUI
import Photos

/// 当天进度条（ui-design 4.2 / ReviewChrome.kt）：2pt 高、3pt 间隔，一格一张。
/// 收藏琥珀实心；取消收藏空心琥珀格；移走退影灰·深；看过保留相纸白 78%；
/// 当前 #FFF6E8 80% + 6pt 发光、1.6s 呼吸；未处理虚线（3pt / 2pt，相纸白 34%）。超过 40 张改连续比例条。
struct ReviewProgressBar: View {
    let cells: [ReviewSessionModel.CellState]
    @State private var breath = false

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
                    drawDashed(&ctx, r)
                    let glow = Path(roundedRect: r.insetBy(dx: -2, dy: -2), cornerRadius: h)
                    var g = ctx
                    g.addFilter(.blur(radius: 3))
                    g.fill(glow, with: .color(Self.current.opacity((breath ? 0.55 : 1) * 0.35)))
                    ctx.fill(rr, with: .color(Self.current.opacity((breath ? 0.55 : 1) * 0.8)))
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
        .animation(DarkroomMotion.standard, value: cells)
        .onAppear {
            withAnimation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true)) { breath = true }
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

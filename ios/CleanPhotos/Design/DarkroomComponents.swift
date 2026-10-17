import SwiftUI

// Android core/design/DarkroomComponents.kt + EmptyArt.kt 的 SwiftUI 版本。

extension DarkroomColors {
    static let pileCard = Color(red: 0x1D/255, green: 0x1A/255, blue: 0x16/255)
    static let pileRim = amber.opacity(0.35)
    static let pileGlow = amber.opacity(0.12)
    static let pileStarGlow = amber.opacity(0.6)
    static let printPaper = Color(red: 0xDC/255, green: 0xD6/255, blue: 0xCD/255)
    static let printShadow = Color.black.opacity(0.5)
    static let hangClip = Color(red: 0x3A/255, green: 0x36/255, blue: 0x32/255)
    static let hangLine = paper.opacity(0.24)
    static let toastFill = Color(red: 0x1E/255, green: 0x1C/255, blue: 0x19/255).opacity(0.86)
    static let toastRim = Color(red: 1, green: 240/255, blue: 225/255).opacity(0.12)
    static let dialogScrim = Color(red: 0x0A/255, green: 0x09/255, blue: 0x08/255).opacity(0.64)
    static let dialogShadow = Color.black.opacity(0.5)
}

/// .btn-tray：36pt 托盘色 + 细描边，13 500。
struct TrayTextButton: View {
    let title: String
    var enabled: Bool = true
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title)
                .font(Font.custom("NotoSansSC-Medium", size: 13))
                .foregroundStyle(DarkroomColors.paper)
                .padding(.horizontal, 16)
                .frame(height: 36)
                .background(DarkroomColors.tray, in: Capsule())
                .overlay(Capsule().strokeBorder(DarkroomColors.rim, lineWidth: 1))
                .opacity(enabled ? 1 : 0.3)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

/// .btn-paper：36pt 相纸白实心，13 600 暗室色；不可点 30%。
struct PaperButton: View {
    let title: String
    var enabled: Bool = true
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title)
                .font(Font.custom("NotoSansSC-SemiBold", size: 13))
                .foregroundStyle(DarkroomColors.room)
                .padding(.horizontal, 16)
                .frame(height: 36)
                .background(DarkroomColors.paper, in: Capsule())
        }
        .buttonStyle(.plain)
        .opacity(enabled ? 1 : 0.3)
        .disabled(!enabled)
    }
}

/// .ghost：40pt 托盘中性按钮 + 可选等宽小字。
struct GhostButton: View {
    let title: String
    var mono: String? = nil
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Text(title).font(Font.custom("NotoSansSC-Medium", size: 13)).foregroundStyle(DarkroomColors.paper)
                if let mono { Text(mono).font(DarkroomFonts.plexMono(size: 11)).foregroundStyle(DarkroomColors.dim) }
            }
            .padding(.horizontal, 20)
            .frame(height: 40)
            .background(DarkroomColors.tray, in: Capsule())
            .overlay(Capsule().strokeBorder(DarkroomColors.rim2, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

/// .btn-clear：40pt 透明 + 删除红 55% 描边，「清空」入口专用。
struct DeleteOutlineButton: View {
    let title: String
    var enabled: Bool = true
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title)
                .font(Font.custom("NotoSansSC-SemiBold", size: 14))
                .foregroundStyle(DarkroomColors.delete)
                .padding(.horizontal, 16)
                .frame(height: 40)
                .overlay(Capsule().strokeBorder(DarkroomColors.delete.opacity(0.55), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .opacity(enabled ? 1 : 0.3)
        .disabled(!enabled)
    }
}

/// 空状态容器（.empty）：页头（安全区 + 84 − 32）与底部导航（92）之间居中。
struct EmptyStateArea<Content: View>: View {
    var insets: EdgeInsets = EdgeInsets()
    @ViewBuilder var content: Content
    var body: some View {
        VStack(spacing: 0) { content }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.top, 116 - 32)
            .padding(.bottom, 92)
    }
}

/// .empty h4 18/24 600 + p 13/20 dim（最宽 232，上 8 下 24）。
struct EmptyTitleAndBody: View {
    let title: String
    let body_: String
    init(_ title: String, _ body: String) { self.title = title; self.body_ = body }
    var body: some View {
        VStack(spacing: 0) {
            Text(title)
                .font(Font.custom("NotoSansSC-SemiBold", size: 18))
                .foregroundStyle(DarkroomColors.paper)
                .multilineTextAlignment(.center)
                .frame(minHeight: 24)
            Text(body_)
                .font(Font.custom("NotoSansSC-Regular", size: 13))
                .lineSpacing(5)
                .foregroundStyle(DarkroomColors.dim)
                .multilineTextAlignment(.center)
                .frame(width: 232)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)
                .padding(.bottom, 24)
        }
    }
}

/// 暗房提示条（Snackbar 换皮）：38pt 胶囊，#1E1C19 86% + 细描边，14 相纸白。
struct DarkToast: View {
    let text: String
    var body: some View {
        Text(text)
            .font(Font.custom("NotoSansSC-Regular", size: 14))
            .foregroundStyle(DarkroomColors.paper)
            .padding(.horizontal, 19)
            .frame(height: 38)
            .background(DarkroomColors.toastFill, in: Capsule())
            .overlay(Capsule().strokeBorder(DarkroomColors.toastRim, lineWidth: 1))
    }
}

/// 空状态「一叠相纸」（EmptyPileArt）：两张托盘色底卡斜放，顶卡演示手势后回位，周期 3.6s。
/// sink = true 为垃圾桶：相纸往下沉、图标换垃圾桶、箭头朝下、无琥珀光晕。Reduce Motion 时停在静止插画。
struct EmptyPileArt: View {
    var sink: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var cycleStart = Date()

    private static let cycle = 3.6, pushAt = 0.648, dropAt = 1.980, starAt = 0.792, starBack = 0.540
    private static let arrowInAt = 0.432, arrowStep = 0.360

    var body: some View {
        TimelineView(.animation(paused: reduceMotion)) { tl in
            let t = reduceMotion ? 0 : tl.date.timeIntervalSince(cycleStart).truncatingRemainder(dividingBy: Self.cycle)
            art(t: t)
        }
        .frame(width: 160, height: 176)
    }

    private func art(t: Double) -> some View {
        // push：PUSH_AT 起落定弹簧 0 → 1，DROP_AT 起弹跳弹簧回 0
        let push: Double = t < Self.dropAt
            ? SpringToken.settle.value(at: t - Self.pushAt)
            : SpringToken.settle.value(at: Self.dropAt - Self.pushAt) * (1 - SpringToken.bouncy.value(at: t - Self.dropAt))
        let starUp = SpringToken.bouncy.value(at: t - Self.starAt)
        let star: Double = t < Self.dropAt ? starUp : max(0, SpringToken.bouncy.value(at: Self.dropAt - Self.starAt) * (1 - min(1, (t - Self.dropAt) / Self.starBack)))
        let arrow: Double = {
            let a = t - Self.arrowInAt
            if a <= 0 { return 0 }
            if a < Self.arrowStep { return CubicBezier.decelerate(a / Self.arrowStep) }
            return 1 + min(1, (a - Self.arrowStep) / Self.arrowStep)
        }()
        let dir: Double = sink ? 1 : -1
        let arrowAlpha = arrow <= 1 ? 0.9 * arrow : 0.9 * (2 - arrow)
        let arrowY = arrow <= 1 ? 12.6 * (1 - arrow) : -15.75 * (arrow - 1)
        return ZStack(alignment: .topLeading) {
            card.opacity(0.6).rotatedShift(-10, -16)
            card.opacity(0.8).rotatedShift(8, 16)
            ZStack {
                RoundedRectangle(cornerRadius: 8, style: .continuous).fill(DarkroomColors.pileCard)
                RoundedRectangle(cornerRadius: 8, style: .continuous).strokeBorder(sink ? DarkroomColors.rim : DarkroomColors.pileRim, lineWidth: 1)
                let s = 1 + 0.25 * star
                if sink {
                    Image(systemName: "trash").font(.system(size: 22)).foregroundStyle(DarkroomColors.dim).scaleEffect(s)
                } else {
                    Image(systemName: "star.fill").font(.system(size: 24)).foregroundStyle(DarkroomColors.pileStarGlow)
                        .blur(radius: 8).opacity(min(1, max(0, star))).scaleEffect(s)
                    Image(systemName: "star.fill").font(.system(size: 24)).foregroundStyle(DarkroomColors.amber).scaleEffect(s)
                }
            }
            .frame(width: 100, height: 128)
            .shadow(color: sink ? DarkroomColors.printShadow : DarkroomColors.pileGlow, radius: 20)
            .rotationEffect(.degrees(dir * 3 * push))
            .offset(x: 30, y: 24 + CGFloat(dir * 44 * push))
            Image(systemName: sink ? "arrow.down" : "arrow.up")
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(sink ? DarkroomColors.dim : DarkroomColors.amber)
                .frame(width: 19, height: 19)
                .opacity(arrowAlpha)
                .offset(y: CGFloat(-dir * arrowY))
                .frame(width: 160, height: 176, alignment: sink ? .bottom : .top)
                .offset(y: CGFloat(23 * dir))
        }
        .frame(width: 160, height: 176, alignment: .topLeading)
    }

    private var card: some View {
        RoundedRectangle(cornerRadius: 8, style: .continuous).fill(DarkroomColors.tray)
            .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).strokeBorder(DarkroomColors.rim, lineWidth: 1))
            .frame(width: 100, height: 128)
    }
}

private extension View {
    /// CSS rotate(θ) translateX(dx)：平移向量随旋转；基准位置 (30, 24)。
    func rotatedShift(_ deg: Double, _ dx: CGFloat) -> some View {
        let r = deg * .pi / 180
        return self.rotationEffect(.degrees(deg))
            .offset(x: 30 + dx * CGFloat(cos(r)), y: 24 + dx * CGFloat(sin(r)))
    }
}

/// 晾绳（ClotheslineArt）：248×152，下垂细绳（Q 曲线）+ 三张相纸夹在绳上轻摆 ±2.5°（4s，各自错开）。
struct ClotheslineArt: View {
    let photoIds: [String]
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var t0 = Date()
    private static let delays: [Double] = [1.2, 2.4, 0.4]
    private static let positions: [CGPoint] = [CGPoint(x: 20, y: 26), CGPoint(x: 94, y: 33), CGPoint(x: 168, y: 26)]

    var body: some View {
        TimelineView(.animation(paused: reduceMotion)) { tl in
            let t = tl.date.timeIntervalSince(t0)
            ZStack(alignment: .topLeading) {
                Path { p in
                    p.move(to: CGPoint(x: 0, y: 18))
                    p.addQuadCurve(to: CGPoint(x: 248, y: 18), control: CGPoint(x: 124, y: 40))
                }
                .stroke(DarkroomColors.hangLine, lineWidth: 1)
                ForEach(Array(photoIds.prefix(3).enumerated()), id: \.offset) { i, id in
                    // 0%/100% = −2.5°，50% = +2.5°，ease-in-out 往返
                    let phase = ((t + Self.delays[i]).truncatingRemainder(dividingBy: 4.0)) / 4.0
                    let tri = phase < 0.5 ? phase * 2 : (1 - phase) * 2
                    let eased = tri < 0.5 ? 2 * tri * tri : 1 - pow(-2 * tri + 2, 2) / 2
                    let angle = reduceMotion ? 0 : -2.5 + 5 * eased
                    HangingPrint(id: id)
                        .rotationEffect(.degrees(angle), anchor: UnitPoint(x: 0.5, y: -6 / 80))
                        .offset(x: Self.positions[i].x, y: Self.positions[i].y)
                }
            }
        }
        .frame(width: 248, height: 152, alignment: .topLeading)
    }
}

private struct HangingPrint: View {
    let id: String
    @EnvironmentObject private var library: PhotoLibraryService
    @State private var image: UIImage?
    var body: some View {
        ZStack(alignment: .top) {
            ZStack {
                DarkroomColors.tileFill
                if let image { Image(uiImage: image).resizable().scaledToFill().accessibilityHidden(true) }
            }
            .frame(width: 52, height: 64)
            .clipped()
            .padding(.horizontal, 4).padding(.top, 4).padding(.bottom, 12)
            .background(DarkroomColors.printPaper, in: RoundedRectangle(cornerRadius: 2))
            .shadow(color: DarkroomColors.printShadow, radius: 12, y: 12)
            RoundedRectangle(cornerRadius: 2).fill(DarkroomColors.hangClip)
                .overlay(RoundedRectangle(cornerRadius: 2).strokeBorder(DarkroomColors.menuRim, lineWidth: 1))
                .frame(width: 8, height: 14)
                .offset(y: -8)
        }
        .frame(width: 60)
        .onAppear {
            if image == nil, let a = library.allById[id]?.asset {
                _ = library.requestThumbnail(for: a, side: 64) { img in Task { @MainActor in image = img } }
            }
        }
    }
}

/// 骨架呼吸（Android skeletonColor）：#171513 ↔ #211E1B，2.4s 往返（每半程 EaseInOut），按 delay 错开起步。
enum SkeletonBreath {
    static let period: Double = 2.4
    static func color(delay: Double, now: Date, epoch: Date) -> Color {
        let t = now.timeIntervalSince(epoch) - delay
        guard t > 0 else { return DarkroomColors.skeletonLow }
        let half = period / 2
        let phase = t.truncatingRemainder(dividingBy: period)
        let lin = phase < half ? phase / half : 1 - (phase - half) / half
        let q = CubicBezier(0.42, 0, 0.58, 1)(lin)
        let lo = (0x17, 0x15, 0x13), hi = (0x21, 0x1E, 0x1B)
        func mix(_ a: Int, _ b: Int) -> Double { (Double(a) + (Double(b) - Double(a)) * q) / 255 }
        return Color(red: mix(lo.0, hi.0), green: mix(lo.1, hi.1), blue: mix(lo.2, hi.2))
    }
}

/// .sk 骨架块（圆角 4）。
struct SkeletonBlock: View {
    var width: CGFloat
    var height: CGFloat
    var delay: Double = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var epoch = Date()
    var body: some View {
        TimelineView(.animation(paused: reduceMotion)) { tl in
            RoundedRectangle(cornerRadius: 4)
                .fill(SkeletonBreath.color(delay: delay, now: tl.date, epoch: epoch))
                .frame(width: width, height: height)
        }
    }
}

/// 照片页加载骨架：三组 8 / 6 / 4 格，天标题 24×20 + 64×12，各格错开 200ms（Android LoadingSkeleton）。
struct LoadingSkeletonGrid: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var epoch = Date()
    private static let groups: [[Double]] = [
        [0, 200, 400, 600, 800, 1000, 200, 400],
        [0, 200, 400, 600, 800, 1000],
        [300, 500, 700, 900],
    ]
    var body: some View {
        let cols = DarkroomDimens.gridColumns
        let w = UIScreen.main.bounds.width
        let side = (w - DarkroomDimens.gridGap * CGFloat(cols - 1)) / CGFloat(cols)
        TimelineView(.animation(paused: reduceMotion)) { tl in
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Self.groups.indices, id: \.self) { g in
                    HStack(spacing: 8) {
                        RoundedRectangle(cornerRadius: 4).fill(SkeletonBreath.color(delay: 0, now: tl.date, epoch: epoch)).frame(width: 24, height: 20)
                        RoundedRectangle(cornerRadius: 4).fill(SkeletonBreath.color(delay: 0, now: tl.date, epoch: epoch)).frame(width: 64, height: 12)
                    }
                    .padding(.horizontal, DarkroomDimens.screenSide)
                    .padding(.top, DarkroomDimens.dayTop)
                    .padding(.bottom, DarkroomDimens.dayBottom)
                    .frame(height: DarkroomDimens.dayHeight, alignment: .center)
                    LazyVGrid(columns: Array(repeating: GridItem(.fixed(side), spacing: DarkroomDimens.gridGap), count: cols), spacing: DarkroomDimens.gridGap) {
                        ForEach(Self.groups[g].indices, id: \.self) { i in
                            Rectangle()
                                .fill(SkeletonBreath.color(delay: Self.groups[g][i] / 1000, now: tl.date, epoch: epoch))
                                .frame(width: side, height: side)
                        }
                    }
                }
            }
        }
        .accessibilityIdentifier("photos-skeleton")
    }
}

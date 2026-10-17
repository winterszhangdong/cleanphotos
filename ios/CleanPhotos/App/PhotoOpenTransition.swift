import SwiftUI

/// 网格 → 清理页的共享元素（Android `SharedPhoto.sharedPhoto` + `NavGraph` gridRecede / photoEnter）：
/// - 展开：0–80ms 停在缩略图（按压档），80–400ms 按 settle 弹簧曲线（asEasing，400ms 准时收住）放大到大图的 fit 位置；
///   网格同段 Standard 退到 0.96 / 亮度 0.25，清理页底色同段淡入；返回 / 日期 / 进度条 400–520ms 淡入。
/// - 收回：先停 80ms，再 320ms Standard 回到网格瓦片；网格 400ms 从 0.96 / 0.25 回来；清理页 400ms 淡出，控件先 100ms 淡出。
/// 网格瓦片把自己的全局 frame 与已显示缩略图登记在这里（不触发刷新）；飞行期间对应瓦片和大图都隐藏，只画飞行层。
@MainActor
final class PhotoOpenTransition: ObservableObject {
    static let shared = PhotoOpenTransition()

    var tileFrames: [String: CGRect] = [:]
    var tileImages: [String: UIImage] = [:]
    /// 飞行中的照片 id：网格瓦片与清理页当前页据此隐藏自己（Android 共享元素在过渡期间只画在 overlay 里）。
    @Published var flyingId: String?

    static let press: Double = 0.08
    static let expandEnd: Double = 0.40
    static let chromeFade: Double = 0.12
    static let collapse: Double = 0.40
    static let micro: Double = 0.10
    static let gridScale: CGFloat = 0.96
    static let gridAlpha: Double = 0.25

    /// Android `SpringSpec.asEasing()`：弹簧在包络衰减到 0.001 的时刻收住，再归一化到 [0, 1]。
    nonisolated static func settleEase(_ f: Double) -> Double {
        if f >= 1 { return 1 }
        if f <= 0 { return 0 }
        let s = SpringToken.settle
        let span = log(1000) / (min(s.damping, 1) * sqrt(s.stiffness))
        return s.value(at: f * span) / s.value(at: span)
    }

    func register(_ id: String, frame: CGRect) { tileFrames[id] = frame }
    func unregister(_ id: String) { tileFrames[id] = nil; tileImages[id] = nil }
}

/// 飞行层：在 from → to 之间按阶段曲线插值，内容按 fill 裁进当前矩形（网格方块 ↔ 大图 fit）。
struct SharedFlight: Equatable {
    enum Kind { case expand, collapse }
    let id: String
    let kind: Kind
    let image: UIImage
    let from: CGRect
    let to: CGRect
    let start: Date

    func progress(at now: Date) -> Double {
        let t = now.timeIntervalSince(start)
        let p = PhotoOpenTransition.press
        guard t > p else { return 0 }
        switch kind {
        case .expand:
            return PhotoOpenTransition.settleEase((t - p) / (PhotoOpenTransition.expandEnd - p))
        case .collapse:
            return CubicBezier.standard(min(1, (t - p) / (PhotoOpenTransition.collapse - p)))
        }
    }

    static func == (a: SharedFlight, b: SharedFlight) -> Bool { a.id == b.id && a.start == b.start && a.kind == b.kind }
}

struct SharedFlightLayer: View {
    let flight: SharedFlight

    var body: some View {
        TimelineView(.animation) { tl in
            let q = CGFloat(flight.progress(at: tl.date))
            let r = CGRect(x: flight.from.minX + (flight.to.minX - flight.from.minX) * q,
                           y: flight.from.minY + (flight.to.minY - flight.from.minY) * q,
                           width: flight.from.width + (flight.to.width - flight.from.width) * q,
                           height: flight.from.height + (flight.to.height - flight.from.height) * q)
            Image(uiImage: flight.image)
                .resizable()
                .accessibilityHidden(true)
                .scaledToFill()
                .frame(width: max(1, r.width), height: max(1, r.height))
                .clipped()
                .position(x: r.midX, y: r.midY)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }
}

/// 清理页通过环境拿到「收回」：传当前照片 id 与已加载的大图；没有宿主（fullScreenCover / 测试）时退回 dismiss。
struct ReviewCloseAction {
    var handler: ((String?, UIImage?) -> Void)?
}

private struct ReviewCloseKey: EnvironmentKey { static let defaultValue = ReviewCloseAction() }
private struct ReviewSharedEntryKey: EnvironmentKey { static let defaultValue = false }

extension EnvironmentValues {
    var reviewClose: ReviewCloseAction {
        get { self[ReviewCloseKey.self] }
        set { self[ReviewCloseKey.self] = newValue }
    }
    /// 是否由共享元素带进来：是则控件 400–520ms 淡入（Android navChrome）。
    var reviewSharedEntry: Bool {
        get { self[ReviewSharedEntryKey.self] }
        set { self[ReviewSharedEntryKey.self] = newValue }
    }
}

/// 过渡时钟：网格退场 / 清理页淡入淡出全部按开始时刻逐帧算（与飞行层同一时基，不依赖 withAnimation 事务）。
struct SharedClock: Equatable {
    enum Kind { case expand, collapse }
    let kind: Kind
    let start: Date

    /// 0 = 网格原样 / 清理页不可见；1 = 网格 0.96 / 0.25、清理页完全不透明。
    func openness(at now: Date) -> Double {
        let t = now.timeIntervalSince(start)
        switch kind {
        case .expand:
            let p = PhotoOpenTransition.press
            guard t > p else { return 0 }
            return CubicBezier.standard(min(1, (t - p) / (PhotoOpenTransition.expandEnd - p)))
        case .collapse:
            return 1 - CubicBezier.standard(min(1, max(0, t) / PhotoOpenTransition.collapse))
        }
    }
}

/// role .grid：网格（与导航）按 openness 缩到 0.96、透明到 0.25；role .review：清理页透明度 = openness。
struct SharedRecede: ViewModifier {
    enum Role { case grid, review }
    let role: Role
    let clock: SharedClock?
    let open: Bool

    func body(content: Content) -> some View {
        TimelineView(.animation(paused: clock == nil)) { tl in
            let o = clock?.openness(at: tl.date) ?? (open ? 1 : 0)
            switch role {
            case .grid:
                // 清理页完全盖住之后（不透明暗房底色），网格与导航不再参与合成：
                // 25% 透明度的整棵网格每帧都要离屏合成一次、导航毛玻璃每帧都要重采样，真机上翻页掉帧的主要 GPU 开销。
                // 动画进行中（clock != nil）照常按 openness 画，收回动画一开始就从 0.25 接上，看不出差别。
                let covered = clock == nil && open && !PerfLegacy.on
                content
                    .scaleEffect(1 - (1 - PhotoOpenTransition.gridScale) * o)
                    .opacity(covered ? 0 : 1 - (1 - PhotoOpenTransition.gridAlpha) * o)
            case .review:
                content.opacity(o)
            }
        }
    }
}

/// Android navChrome：进入后 400–520ms Standard 淡入；收回时 100ms 淡出。按时刻逐帧算。
struct NavChromeFade: ViewModifier {
    struct State: Equatable { let start: Date; let entering: Bool }
    let state: State?

    func body(content: Content) -> some View {
        TimelineView(.animation(paused: state == nil)) { tl in
            content.opacity(Self.alpha(state, tl.date))
        }
    }

    static func alpha(_ s: State?, _ now: Date) -> Double {
        guard let s else { return 1 }
        let t = now.timeIntervalSince(s.start)
        if s.entering {
            guard t > PhotoOpenTransition.expandEnd else { return 0 }
            return CubicBezier.standard(min(1, (t - PhotoOpenTransition.expandEnd) / PhotoOpenTransition.chromeFade))
        }
        return 1 - CubicBezier.standard(min(1, max(0, t) / PhotoOpenTransition.micro))
    }
}

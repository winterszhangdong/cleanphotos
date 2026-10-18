import SwiftUI

/// 清理页偏好（Android DataStoreKeys：has_seen_review_tutorial / review_swipe_action_count）。
enum ReviewPrefs {
    static let tutorialKey = "has_seen_review_tutorial"
    static let swipeCountKey = "review_swipe_action_count"
    /// 决定 10：累计成功上滑 / 下滑不足 20 次时显示方向提示小点（撤回不回退计数）。
    static let hintHideAfter = 20

    static var hasSeenTutorial: Bool {
        if AuditHooks.enabled, ProcessInfo.processInfo.arguments.contains("-cpForceTutorial") { return false }
        return UserDefaults.standard.bool(forKey: tutorialKey)
    }
    static func markTutorialSeen() { if !PerfDryRun.on { UserDefaults.standard.set(true, forKey: tutorialKey) } }
    static var swipeCount: Int { UserDefaults.standard.integer(forKey: swipeCountKey) }
    static func recordSwipe() { if !PerfDryRun.on { UserDefaults.standard.set(swipeCount + 1, forKey: swipeCountKey) } }
    static var showDirectionHints: Bool { swipeCount < hintHideAfter }
}

/// CSS 关键帧求值：stops 为 (0..1, 值)，每段内按 --ease-out（0.2, 0.8, 0.2, 1）。
enum TutorialTimeline {
    static let draftEaseOut = CubicBezier(0.2, 0.8, 0.2, 1)
    static func keyframes(_ t: Double, _ stops: [(Double, Double)], linear: Bool = false) -> Double {
        guard let first = stops.first else { return 0 }
        if t <= first.0 { return first.1 }
        for i in 1..<stops.count {
            let (t1, v1) = stops[i], (t0, v0) = stops[i - 1]
            if t <= t1 {
                if t1 <= t0 { return v1 }
                let p = (t - t0) / (t1 - t0)
                return v0 + (v1 - v0) * (linear ? p : draftEaseOut(p))
            }
        }
        return stops.last!.1
    }
    static let dotOpacity: [(Double, Double)] = [(0, 0), (0.04, 1), (0.22, 1), (0.28, 0), (0.333, 0), (0.334, 0), (0.374, 1), (0.554, 1), (0.61, 0), (0.666, 0), (0.667, 0), (0.707, 1), (0.88, 1), (0.94, 0), (1, 0)]
    static let dotX: [(Double, Double)] = [(0, 0), (0.666, 0), (0.667, 56), (0.707, 56), (0.88, -56), (0.94, -64), (1, -64)]
    static let dotY: [(Double, Double)] = [(0, 0), (0.04, 0), (0.22, -96), (0.28, -104), (0.333, -104), (0.334, 0), (0.374, 0), (0.554, 96), (0.61, 104), (0.666, 104), (0.667, 0), (1, 0)]
    static let dotScale: [(Double, Double)] = [(0, 1.2), (0.04, 0.9), (0.333, 0.9), (0.334, 1.2), (0.374, 0.9), (0.666, 0.9), (0.667, 1.2), (0.707, 0.9), (1, 0.9)]
    static let trailUpOpacity: [(Double, Double)] = [(0, 1), (0.22, 1), (0.32, 0), (1, 0)]
    static let trailUpScale: [(Double, Double)] = [(0, 0), (0.04, 0), (0.22, 1), (1, 1)]
    static let trailDownOpacity: [(Double, Double)] = [(0, 0), (0.374, 0), (0.375, 1), (0.554, 1), (0.65, 0), (1, 0)]
    static let trailDownScale: [(Double, Double)] = [(0, 0), (0.375, 0), (0.554, 1), (1, 1)]
    static let trailSideOpacity: [(Double, Double)] = [(0, 0), (0.707, 0), (0.708, 1), (0.88, 1), (0.98, 0), (1, 0)]
    static let trailSideScale: [(Double, Double)] = [(0, 0), (0.708, 0), (0.88, 1), (1, 1)]
    static let star: [(Double, Double)] = [(0, 0), (0.18, 0), (0.22, 1), (0.28, 1), (0.36, 0), (1, 0)]
    static let bin: [(Double, Double)] = [(0, 0), (0.51, 0), (0.554, 1), (0.61, 1), (0.69, 0), (1, 0)]
    /// tut-row：线性，0–31% 亮、34% 起 0.38；第 2、3 行相位 +1/3、+2/3。
    static func rowOpacity(_ t: Double, row: Int) -> Double {
        let phase = (t - Double(row) / 3 + 1).truncatingRemainder(dividingBy: 1)
        return keyframes(phase, [(0, 1), (0.31, 1), (0.34, 0.38), (1, 0.38)], linear: true)
    }
}

/// 首次教学（Android ReviewTutorialOverlay / mock 13）：照片压暗到 32%（舞台里做），中间光点依次演示
/// 上滑（琥珀）、下滑、左右（相纸白），每段 2.4s；三行文字依次点亮；「知道了」或完成任意一次滑动后永久关闭。
/// 不拦截滑动手势（只有按钮可点）。Reduce Motion 时停在 0.2 相位。
struct ReviewTutorialOverlay: View {
    var topInset: CGFloat
    var bottomInset: CGFloat
    var locale: Locale
    var onKnow: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var t0 = Date()

    private static let dotFill = Color(red: 1, green: 248/255, blue: 238/255).opacity(0.92)
    private static let dotRing = Color(red: 1, green: 246/255, blue: 232/255).opacity(0.12)
    private static let dotGlow = Color(red: 1, green: 246/255, blue: 232/255).opacity(0.5)

    var body: some View {
        TimelineView(.animation(paused: reduceMotion)) { tl in
            let t = reduceMotion ? 0.2 : (tl.date.timeIntervalSince(t0) / 7.2).truncatingRemainder(dividingBy: 1)
          GeometryReader { g in
            ZStack(alignment: .top) {
                Color.clear.allowsHitTesting(false)
                if g.size.width > g.size.height {
                    // 横屏：高度只有 ~390，竖排会把三行说明挤出屏幕 —— 光点演示在左、说明在右，整体在按钮上方居中
                    HStack(spacing: 48) {
                        stage(t).frame(width: 200, height: 264)
                        rows(t).frame(width: 360)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding(.top, topInset)
                    .padding(.bottom, bottomInset + 84)
                    .allowsHitTesting(false)
                } else {
                    stage(t).frame(width: 200, height: 264).padding(.top, topInset + 100).allowsHitTesting(false)
                    rows(t)
                        .padding(.horizontal, 32)
                        .padding(.top, topInset + 400)
                        .allowsHitTesting(false)
                }
                VStack {
                    Spacer()
                    Button(action: onKnow) {
                        Text(L10n.string("tut_ok", locale: locale))
                            .font(Font.custom("NotoSansSC-SemiBold", size: 14))
                            .foregroundStyle(DarkroomColors.room)
                            .padding(.horizontal, 32)
                            .frame(height: 44)
                            .background(DarkroomColors.paper, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("tutorial-ok")
                    .padding(.bottom, bottomInset + 40)
                }
            }
          }
        }
    }

    private func rows(_ t: Double) -> some View {
        VStack(spacing: 0) {
            row(0, t, "arrow.up", DarkroomColors.amber, "tut_up", "tut_up_desc", last: false)
            row(1, t, "arrow.down", DarkroomColors.paper, "tut_down", "tut_down_desc", last: false)
            row(2, t, "arrow.left.and.right", DarkroomColors.paper, "tut_lr", "tut_lr_desc", last: true)
        }
    }

    private func row(_ i: Int, _ t: Double, _ icon: String, _ tint: Color, _ title: String, _ right: String, last: Bool) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).font(.system(size: 13, weight: .semibold)).foregroundStyle(tint).frame(width: 24)
            Text(L10n.string(title, locale: locale)).font(Font.custom("NotoSansSC-Medium", size: 15)).foregroundStyle(DarkroomColors.paper)
            Spacer()
            Text(L10n.string(right, locale: locale)).font(Font.custom("NotoSansSC-Regular", size: 13)).foregroundStyle(DarkroomColors.dim)
        }
        .frame(height: 48)
        .overlay(alignment: .bottom) { if !last { Rectangle().fill(DarkroomColors.rim).frame(height: 1) } }
        .opacity(TutorialTimeline.rowOpacity(t, row: i))
    }

    private func stage(_ t: Double) -> some View {
        let k = TutorialTimeline.keyframes
        return ZStack {
            // 虚线轴：竖轴 top/bottom 44，横轴 left/right 24
            Path { p in
                var y: CGFloat = 44
                while y < 264 - 44 { p.addRect(CGRect(x: 100, y: y, width: 1, height: 3)); y += 7 }
                var x: CGFloat = 24
                while x < 200 - 24 { p.addRect(CGRect(x: x, y: 132, width: 3, height: 1)); x += 7 }
            }
            .fill(DarkroomColors.paper.opacity(0.14))
            // 光迹
            RoundedRectangle(cornerRadius: 2)
                .fill(LinearGradient(colors: [DarkroomColors.amber.opacity(0.9), DarkroomColors.amber.opacity(0)], startPoint: .top, endPoint: .bottom))
                .frame(width: 3, height: 96)
                .scaleEffect(x: 1, y: k(t, TutorialTimeline.trailUpScale, false), anchor: .bottom)
                .opacity(k(t, TutorialTimeline.trailUpOpacity, false))
                .position(x: 100, y: 36 + 48)
            RoundedRectangle(cornerRadius: 2)
                .fill(LinearGradient(colors: [DarkroomColors.paper.opacity(0), DarkroomColors.paper.opacity(0.6)], startPoint: .top, endPoint: .bottom))
                .frame(width: 3, height: 96)
                .scaleEffect(x: 1, y: k(t, TutorialTimeline.trailDownScale, false), anchor: .top)
                .opacity(k(t, TutorialTimeline.trailDownOpacity, false))
                .position(x: 100, y: 132 + 48)
            RoundedRectangle(cornerRadius: 2)
                .fill(LinearGradient(colors: [DarkroomColors.paper.opacity(0.6), DarkroomColors.paper.opacity(0)], startPoint: .leading, endPoint: .trailing))
                .frame(width: 112, height: 3)
                .scaleEffect(x: k(t, TutorialTimeline.trailSideScale, false), y: 1, anchor: .trailing)
                .opacity(k(t, TutorialTimeline.trailSideOpacity, false))
                .position(x: 44 + 56, y: 132)
            end(k(t, TutorialTimeline.star, false), amber: true).position(x: 100, y: 4 + 16)
            end(k(t, TutorialTimeline.bin, false), amber: false).position(x: 100, y: 264 - 4 - 16)
            // 光点 28 + 6 光晕 + 24 柔光
            ZStack {
                Circle().fill(RadialGradient(colors: [Self.dotGlow, .clear], center: .center, startRadius: 0, endRadius: 38)).frame(width: 76, height: 76)
                Circle().fill(Self.dotRing).frame(width: 40, height: 40)
                Circle().fill(Self.dotFill).frame(width: 28, height: 28)
            }
            .scaleEffect(k(t, TutorialTimeline.dotScale, false))
            .opacity(k(t, TutorialTimeline.dotOpacity, false))
            .position(x: 100 + k(t, TutorialTimeline.dotX, false), y: 132 + k(t, TutorialTimeline.dotY, false))
        }
        .frame(width: 200, height: 264)
    }

    private func end(_ on: Double, amber: Bool) -> some View {
        let fill = amber ? DarkroomColors.amber : DarkroomColors.paper
        return ZStack {
            if amber && on > 0 { Circle().fill(DarkroomColors.amber.opacity(0.2 * on)).frame(width: 40, height: 40) }
            if on > 0 { Circle().fill(fill.opacity(on)) }
            if on < 1 { Circle().strokeBorder(DarkroomColors.paper.opacity(0.2 * (1 - on)), lineWidth: 1) }
            Image(systemName: amber ? "star.fill" : "trash")
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(on > 0.5 ? DarkroomColors.room.opacity(on) : DarkroomColors.paper.opacity(0.5 * (1 - on) + 0.0))
        }
        .frame(width: 32, height: 32)
    }
}

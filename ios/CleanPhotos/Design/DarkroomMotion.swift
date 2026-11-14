import SwiftUI

enum DarkroomMotion {
    static let micro: Double = 0.100
    static let short: Double = 0.150
    static let medium: Double = 0.280
    static let long: Double = 0.450
    static let develop: Double = 0.520
    static let developStagger: Double = 0.055
    static let developMaxSteps = 12
    static let menu: Double = 0.180
    static let scrollSettleIgnore: Double = 0.300
    /// 日期滑块松手后留在屏幕上的时间，然后再用 short 淡出。
    static let scrubberHold: Double = 0.900
    /// 当前页停稳后再自动开播。这段里中心键不出现。
    static let playbackStartDelay: Double = 0.250
    /// 中心键出现 / 消失的缩放起点。
    static let revealScale: CGFloat = 0.92

    static let standard = Animation.timingCurve(0.2, 0, 0, 1, duration: medium)
    static let shortCurve = Animation.timingCurve(0.2, 0, 0, 1, duration: short)
    static let developCurve = Animation.timingCurve(0.45, 0, 0.2, 1, duration: develop)
    static let menuIn = Animation.timingCurve(0.2, 0, 0, 1, duration: menu)
    /// 顶栏收起、范围刻度吸页。阻尼 0.75，和排序图标的落定弹簧一致。
    static let settle = Animation.spring(response: 0.4, dampingFraction: 0.75)
}

/// 减弱动态效果（对应 Android「动画时长缩放 = 0」：有限动画直接跳终态）：
/// 根视图和每个全屏层都挂一次，把事务里的动画清掉，withAnimation / .animation(value:) 一律瞬时生效。
/// 逐帧时钟驱动的部分（共享元素、溶解、呼吸、晾片、显影相纸）各自按 reduceMotion 停表或跳过。
struct ReduceMotionGate: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func body(content: Content) -> some View {
        content.transaction { t in
            if reduceMotion { t.animation = nil; t.disablesAnimations = true }
        }
    }
}

extension View {
    func reduceMotionGate() -> some View { modifier(ReduceMotionGate()) }
}

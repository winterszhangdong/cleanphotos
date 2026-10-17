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

    static let standard = Animation.timingCurve(0.2, 0, 0, 1, duration: medium)
    static let developCurve = Animation.timingCurve(0.45, 0, 0.2, 1, duration: develop)
    static let menuIn = Animation.timingCurve(0.2, 0, 0, 1, duration: menu)
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

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

/// 印样表装袋（docs/ui-design.md 3.20）。时长单位秒，位移单位 pt。业务代码不另写魔法数字。
enum FilingMotion {
    static let penStroke: Double = 0.220
    static let envelopeDwell: Double = 0.400
    static let gather: Double = 0.360
    static let stackHold: Double = 0.080
    static let total: Double = 1.100
    static let pickUp: Double = 0.240
    static let putBack: Double = 0.280
    static let liftStagger: Double = 0.020
    static let liftStaggerSteps = 4
    static let liftPop: CGFloat = 0.08
    static let liftShadow: CGFloat = 10
    static let landHandoff: Double = 0.2
    static let flapReopenDelay: Double = 0.300
    static let stackScale: CGFloat = 0.66
    static let stackTilt: CGFloat = 16
    static let stackRotations: [CGFloat] = [-8, 4, 12]
    static let mouthScale: CGFloat = 0.32
    static let cameraDistance: CGFloat = 12
    static let leanTiltX: CGFloat = 12
    static let leanTiltYMax: CGFloat = 8
    static let leanLift: CGFloat = 8
    static let leanScale: CGFloat = 1.04
    static let dimOthers: CGFloat = 0.42
    static let flapBump: CGFloat = 3
    static let hoverFanDeg: CGFloat = 14
    static let peekFanDeg: CGFloat = 18
    static let peekLift: CGFloat = 10
    static let peekScale: CGFloat = 1.06
    static let stripEnterShift: CGFloat = 28
    static let stripEnterTilt: CGFloat = -1.2
    static let stripSkew: [CGFloat] = [0, 0.5, -0.4, 0.3]
    static let stackLift: CGFloat = 36
    static let tagStackGap: CGFloat = 8
    static let tagFanClear: CGFloat = 60
    static let tagHeight: CGFloat = 36
    static let tagMinWidth: CGFloat = 64
    static let tagMaxWidth: CGFloat = 160
    static let tagMargin: CGFloat = 8
    static let tagTongueWidth: CGFloat = 10
    static let tagTongueHeight: CGFloat = 6
    static let tagFromScale: CGFloat = 0.88
    static let edgeZone: CGFloat = 40
    static let edgeBand: CGFloat = 24
    static let sheetHeight: CGFloat = 0.72
    static let sheetMaxHeight: CGFloat = 600
    static let sheetScrim: CGFloat = 0.64
    static let sheetCloseDelay: Double = 0.300
    static let sheetTopClear: CGFloat = 56
    static let sheetDismissDrag: CGFloat = 96
    static let sheetLandFade: Double = 0.120
    static let rollAngle: CGFloat = -70
    static let rollScaleX: CGFloat = 0.16
    static let stagger: Double = 0.040
    static let perspective: CGFloat = 1 / 12
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

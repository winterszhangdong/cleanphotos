import SwiftUI

/// 未看标记（.tile.new::after / Android UnseenDot.kt）：
/// 6pt #FFFBF5 光点，外圈 1.5pt `room` 50% 实心暗环（box-shadow 0 0 0 1.5px，紧贴光点、没有缝），
/// 再外 8pt 柔光 #FFF6E8 70%（box-shadow 0 0 8px 1px，不裁切）。调用处定位在缩略图左上内缩 6pt。
struct UnseenDot: View {
    private let dot = DarkroomDimens.unseenDot
    private let ring = DarkroomDimens.unseenRing

    var body: some View {
        ZStack {
            // 柔光：1pt spread 的圆做 8pt 模糊（CSS blur radius 8 ≈ σ 4）
            Circle()
                .fill(DarkroomColors.unseenGlow)
                .frame(width: dot + 2, height: dot + 2)
                .blur(radius: DarkroomDimens.unseenGlow / 2)
            Circle()
                .fill(DarkroomColors.unseenGlow)
                .frame(width: dot + 2, height: dot + 2)
                .blur(radius: DarkroomDimens.unseenGlow / 4)
                .opacity(0.6)
            // 暗环：实心圆铺在光点下面，外径 = 光点 + 2×1.5
            Circle()
                .fill(DarkroomColors.unseenRing)
                .frame(width: dot + ring * 2, height: dot + ring * 2)
            Circle()
                .fill(DarkroomColors.unseenDot)
                .frame(width: dot, height: dot)
        }
        .frame(width: dot, height: dot)
        .compositingGroup()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

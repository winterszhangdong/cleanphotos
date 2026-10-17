import SwiftUI

/// 选中圆勾（SelectionPick.kt）：22pt 相纸白圆 + 2pt 暗室描边 + 14pt 粗勾，0 2 8 投影。
/// 只画在选中的格子上；未选中格不画空心圈（ui-design 3.1a / 3.6）。
struct SelectionCheck: View {
    var body: some View {
        ZStack {
            Circle()
                .fill(DarkroomColors.paper)
                .shadow(color: .black.opacity(0.5), radius: 4, y: 2)
            Circle()
                .strokeBorder(DarkroomColors.room, lineWidth: 2)
            Image(systemName: "checkmark")
                .font(.system(size: 11, weight: .heavy))
                .foregroundStyle(DarkroomColors.room)
        }
        .frame(width: 22, height: 22)
        .allowsHitTesting(false)
    }
}

extension AnyTransition {
    /// 淡入 + 0.6→1 缩放 160ms 标准曲线。
    static var selectPop: AnyTransition {
        .scale(scale: 0.6).combined(with: .opacity)
    }
}

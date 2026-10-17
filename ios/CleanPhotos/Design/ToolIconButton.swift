import SwiftUI

/// 36pt 圆形工具按钮：托盘色底 + 9% 细线，图标 18pt；菜单打开时反白。
/// `pip`：筛选为「仅未看过」时右上角（内缩 7pt）6pt 相纸白小点。
struct ToolIconButton: View {
    var systemName: String
    var inverted: Bool = false
    var pip: Bool = false
    var rotation: Angle = .zero
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 15, weight: .medium))
                .rotationEffect(rotation)
                .foregroundStyle(inverted ? DarkroomColors.room : DarkroomColors.paper)
                .frame(width: DarkroomDimens.toolButton, height: DarkroomDimens.toolButton)
                .background(
                    Circle()
                        .fill(inverted ? DarkroomColors.paper : DarkroomColors.tray)
                        .overlay(Circle().strokeBorder(inverted ? Color.clear : DarkroomColors.rim, lineWidth: 1))
                )
                .overlay(alignment: .topTrailing) {
                    if pip {
                        Circle()
                            .fill(inverted ? DarkroomColors.room : DarkroomColors.paper)
                            .frame(width: 6, height: 6)
                            .padding(7)
                    }
                }
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(.isButton)
    }
}

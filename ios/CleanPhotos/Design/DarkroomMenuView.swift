import SwiftUI

struct DarkroomMenuOption: Identifiable {
    let id = UUID()
    let label: String
    let trailing: String?
    let selected: Bool
    let action: () -> Void
}

/// 排序 / 筛选 / 更多菜单（ui-design 3.9 / Android DarkroomMenu.kt）：
/// 192pt 玻璃菜单 #201D1A 88% + 12% 描边 + 0 24 48 黑 60% 投影，圆角 16、内边距 4；
/// 标题 11/16 字距 0.12em dim；项 44pt 圆角 12：未选中 14 dim，选中 10% 相纸白铺底 + 相纸白 500，不打勾；
/// 右侧 mono 11 faint（选中 dim）。
struct DarkroomMenuView: View {
    let header: String
    let options: [DarkroomMenuOption]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(header)
                .font(DarkroomType.menuHeader)
                .tracking(1.32)
                .foregroundStyle(DarkroomColors.dim)
                .padding(.horizontal, 12)
                .padding(.top, 8)
                .padding(.bottom, 4)
            ForEach(options) { option in
                Button(action: option.action) {
                    HStack {
                        Text(option.label)
                            .font(option.selected ? DarkroomType.menuItemOn : DarkroomType.menuItem)
                            .foregroundStyle(option.selected ? DarkroomColors.paper : DarkroomColors.dim)
                            .lineLimit(1)
                        Spacer(minLength: 8)
                        if let trailing = option.trailing {
                            Text(trailing)
                                .font(DarkroomFonts.plexMono(size: 11))
                                .foregroundStyle(option.selected ? DarkroomColors.dim : DarkroomColors.faint)
                                .lineLimit(1)
                        }
                    }
                    .padding(.horizontal, 12)
                    .frame(height: DarkroomDimens.menuItemHeight)
                    .background(
                        RoundedRectangle(cornerRadius: DarkroomDimens.menuItemCorner, style: .continuous)
                            .fill(option.selected ? DarkroomColors.menuItemOn : .clear)
                    )
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(DarkroomDimens.menuPadding)
        .frame(width: DarkroomDimens.menuWidth, alignment: .leading)
        .background {
            RoundedRectangle(cornerRadius: DarkroomDimens.menuCorner, style: .continuous)
                .fill(DarkroomColors.menuGlass)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: DarkroomDimens.menuCorner, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: DarkroomDimens.menuCorner, style: .continuous)
                        .strokeBorder(DarkroomColors.menuRim, lineWidth: 1)
                )
                .shadow(color: DarkroomColors.menuShadow, radius: 24, y: 24)
        }
        .transition(.scale(scale: 0.92, anchor: .topTrailing).combined(with: .opacity))
    }
}

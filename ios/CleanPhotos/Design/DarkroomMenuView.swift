import SwiftUI

struct DarkroomMenuOption: Identifiable {
    let id = UUID()
    let label: String
    let trailing: String?
    let selected: Bool
    /// 非 nil 时右侧画开关，不再画 trailing，也不铺选中底。
    var toggle: Bool? = nil
    let action: () -> Void
}

struct DarkroomMenuSection: Identifiable {
    let id = UUID()
    var header: String?
    var options: [DarkroomMenuOption]
}

/// 排序 / 筛选 / 更多菜单（ui-design 3.9 / Android DarkroomMenu.kt）：
/// 192pt 玻璃菜单 #201D1A 88% + 12% 描边 + 0 24 48 黑 60% 投影，圆角 16、内边距 4；
/// 标题 11/16 字距 0.12em dim；项 44pt 圆角 12：未选中 14 dim，选中 10% 相纸白铺底 + 相纸白 500，不打勾；
/// 右侧 mono 11 faint（选中 dim）。
struct DarkroomMenuView: View {
    private let sections: [DarkroomMenuSection]

    init(header: String, options: [DarkroomMenuOption]) {
        sections = [DarkroomMenuSection(header: header, options: options)]
    }

    init(sections: [DarkroomMenuSection]) {
        self.sections = sections
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(sections.enumerated()), id: \.element.id) { index, section in
                if index > 0 {
                    Spacer().frame(height: DarkroomDimens.menuPadding)
                    Rectangle()
                        .fill(DarkroomColors.rim)
                        .frame(height: 1)
                        .padding(.horizontal, 12)
                    Spacer().frame(height: DarkroomDimens.menuPadding)
                }
                if let header = section.header {
                    Text(header)
                        .font(DarkroomType.menuHeader)
                        .tracking(1.32)
                        .foregroundStyle(DarkroomColors.dim)
                        .padding(.horizontal, 12)
                        .padding(.top, 8)
                        .padding(.bottom, 4)
                }
                ForEach(section.options) { option in
                    menuRow(option)
                }
            }
        }
        .padding(DarkroomDimens.menuPadding)
        .frame(width: DarkroomDimens.menuWidth, alignment: .leading)
        .background {
            RoundedRectangle(cornerRadius: DarkroomDimens.menuCorner, style: .continuous)
                .fill(DarkroomColors.menuGlass)
                .background {
                    DarkroomBlurBackdrop()
                        .clipShape(RoundedRectangle(cornerRadius: DarkroomDimens.menuCorner, style: .continuous))
                }
                .overlay(
                    RoundedRectangle(cornerRadius: DarkroomDimens.menuCorner, style: .continuous)
                        .strokeBorder(DarkroomColors.menuRim, lineWidth: 1)
                )
                .shadow(color: DarkroomColors.menuShadow, radius: 24, y: 24)
        }
        .transition(.scale(scale: 0.92, anchor: .topTrailing).combined(with: .opacity))
    }

    private func menuRow(_ option: DarkroomMenuOption) -> some View {
        let highlighted = option.selected && option.toggle == nil
        return Button(action: option.action) {
            HStack {
                Text(option.label)
                    .font(highlighted ? DarkroomType.menuItemOn : DarkroomType.menuItem)
                    .foregroundStyle(highlighted ? DarkroomColors.paper : DarkroomColors.dim)
                    .lineLimit(1)
                Spacer(minLength: 8)
                if let on = option.toggle {
                    MenuSwitch(on: on)
                } else if let trailing = option.trailing {
                    Text(trailing)
                        .font(DarkroomFonts.plexMono(size: 11))
                        .foregroundStyle(highlighted ? DarkroomColors.dim : DarkroomColors.faint)
                        .lineLimit(1)
                }
            }
            .padding(.horizontal, 12)
            .frame(height: DarkroomDimens.menuItemHeight)
            .background(
                RoundedRectangle(cornerRadius: DarkroomDimens.menuItemCorner, style: .continuous)
                    .fill(highlighted ? DarkroomColors.menuItemOn : .clear)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// 菜单开关。开：相纸白轨道 + 暗室色圆钮；关：托盘轨道 + dim 圆钮。不用琥珀、不用红。
private struct MenuSwitch: View {
    var on: Bool

    var body: some View {
        Capsule()
            .fill(on ? DarkroomColors.paper : DarkroomColors.tray)
            .overlay(Capsule().strokeBorder(DarkroomColors.rim, lineWidth: 1))
            .frame(width: 36, height: 20)
            .overlay(alignment: on ? .trailing : .leading) {
                Circle()
                    .fill(on ? DarkroomColors.room : DarkroomColors.dim)
                    .frame(width: 16, height: 16)
                    .padding(.horizontal, 2)
            }
    }
}

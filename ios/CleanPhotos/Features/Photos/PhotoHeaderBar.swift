import SwiftUI

enum PhotoMenu: Equatable { case none, sort, filter, more }

/// 照片页顶栏（ui-design 3.1）：eyebrow + 大数字 + 单位 + 副文字；右侧排序 / 筛选 / 更多。
/// 菜单打开时左侧 50%，对应按钮反白，其余按钮 50%；加载中排序、筛选 40% 不可点。
struct PhotoHeaderBar: View {
    var eyebrow: String
    var count: Int
    var sub: String?
    var locale: Locale
    var isChinese: Bool
    var openMenu: PhotoMenu
    var sortOldest: Bool
    var unseenOnly: Bool
    var loading: Bool
    /// 首次加载还没有任何分组：大数字换成 96×24 骨架块 +「正在读取照片…」（Android ScreenHeader loading）。
    var skeleton: Bool = false
    var onSort: () -> Void
    var onFilter: () -> Void
    var onMore: () -> Void

    @State private var sortAngle: Double = 0

    private var formattedCount: String {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.locale = locale
        return f.string(from: NSNumber(value: count)) ?? "\(count)"
    }

    var body: some View {
        let menuOpen = openMenu != .none
        HStack(alignment: .bottom, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(eyebrow)
                    .font(DarkroomType.eyebrow)
                    .tracking(1.44)
                    .foregroundStyle(DarkroomColors.dim)
                    .lineLimit(1)

                if skeleton {
                    HStack(spacing: 12) {
                        SkeletonBlock(width: 96, height: 24)
                        Text(L10n.string("photos_loading", locale: locale))
                            .font(DarkroomType.countSub)
                            .foregroundStyle(DarkroomColors.dim)
                            .lineLimit(1)
                    }
                    .frame(height: 34, alignment: .center)
                } else {
                ViewThatFits(in: .horizontal) {
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        bigNumber
                        unit
                        subText.padding(.leading, 4)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(alignment: .firstTextBaseline, spacing: 4) { bigNumber; unit }
                        subText.lineLimit(1).truncationMode(.tail)
                    }
                }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .opacity(menuOpen ? 0.5 : 1)

            HStack(spacing: DarkroomDimens.headerActionGap) {
                ToolIconButton(systemName: "arrow.up.arrow.down", inverted: openMenu == .sort, rotation: .degrees(sortAngle), action: onSort)
                    .opacity(loading ? 0.4 : (openMenu == .filter || openMenu == .more ? 0.5 : 1))
                    .disabled(loading)
                    .accessibilityLabel(Text(L10n.string("cd_sort", locale: locale)))
                ToolIconButton(systemName: "line.3.horizontal.decrease", inverted: openMenu == .filter, pip: unseenOnly, action: onFilter)
                    .opacity(loading ? 0.4 : (openMenu == .sort || openMenu == .more ? 0.5 : 1))
                    .disabled(loading)
                    .accessibilityLabel(Text(L10n.string("cd_filter", locale: locale)))
                ToolIconButton(systemName: "ellipsis", inverted: openMenu == .more, action: onMore)
                    .opacity(openMenu == .sort || openMenu == .filter ? 0.5 : 1)
                    .accessibilityLabel(Text(L10n.string("cd_more", locale: locale)))
            }
        }
        .padding(.horizontal, DarkroomDimens.screenSide)
        .padding(.bottom, DarkroomDimens.headerBottomPadding)
        .frame(height: DarkroomDimens.headerHeight, alignment: .bottom)
        .background(DarkroomColors.room.opacity(0.92))
        .environment(\.locale, locale)
        .animation(DarkroomMotion.menuIn, value: openMenu)
        .onAppear { sortAngle = sortOldest ? 180 : 0 }
        .onChange(of: sortOldest) { _, oldest in
            // 排序图标在提示出现后 settle 转 180°
            withAnimation(.spring(response: 0.4, dampingFraction: 0.75).delay(0.08)) {
                sortAngle = oldest ? 180 : 0
            }
        }
    }

    private var bigNumber: some View {
        OdometerText(value: Int64(count), text: formattedCount, font: DarkroomType.countBig.monospacedDigit(), color: DarkroomColors.paper, tracking: -0.6)
            .lineLimit(1)
            .minimumScaleFactor(0.7)
    }

    @ViewBuilder private var unit: some View {
        if isChinese {
            Text(L10n.string("header_count_unit", locale: locale))
                .font(DarkroomType.countUnit)
                .foregroundStyle(DarkroomColors.dim)
        }
    }

    @ViewBuilder private var subText: some View {
        if let sub {
            Text(sub)
                .font(DarkroomType.countSub)
                .monospacedDigit()
                .foregroundStyle(DarkroomColors.dim)
        }
    }
}

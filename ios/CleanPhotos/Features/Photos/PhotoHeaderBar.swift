import SwiftUI

/// 照片页顶栏：没有小标题。大数字是当前范围张数；右侧只有 ⋯。多选时换成已选张数和关闭。
struct PhotoHeaderBar: View {
    var count: Int
    var unit: String
    var sub: String?
    var locale: Locale
    var skeleton: Bool
    /// 骨架右侧的说明。空则用「正在加载」。
    var loadingText: String? = nil
    var menuOpen: Bool
    var pip: Bool
    var selecting: Bool
    var onMore: () -> Void
    var onClose: () -> Void

    private var formattedCount: String {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.locale = locale
        return f.string(from: NSNumber(value: count)) ?? "\(count)"
    }

    var body: some View {
        HStack(alignment: .bottom, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                if skeleton && !selecting {
                    HStack(spacing: 12) {
                        SkeletonBlock(width: 96, height: 24)
                        Text(loadingText ?? L10n.string("photos_loading", locale: locale))
                            .font(DarkroomType.countSub)
                            .foregroundStyle(DarkroomColors.dim)
                            .lineLimit(1)
                    }
                    .frame(height: 34, alignment: .center)
                } else {
                    ViewThatFits(in: .horizontal) {
                        HStack(alignment: .firstTextBaseline, spacing: 4) {
                            bigNumber
                            unitText
                            subText.padding(.leading, 4)
                        }
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(alignment: .firstTextBaseline, spacing: 4) { bigNumber; unitText }
                            subText.lineLimit(1).truncationMode(.tail)
                        }
                    }
                    .frame(minHeight: 34, alignment: .bottom)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .opacity(menuOpen ? 0.5 : 1)

            if selecting {
                ToolIconButton(systemName: "xmark", action: onClose)
                    .accessibilityLabel(Text(L10n.string("cd_exit_selection", locale: locale)))
                    .accessibilityIdentifier("select-exit")
            } else {
                ToolIconButton(systemName: "ellipsis", inverted: menuOpen, pip: pip, action: onMore)
                    .accessibilityLabel(Text(L10n.string("cd_more", locale: locale)))
            }
        }
        .padding(.horizontal, DarkroomDimens.screenSide)
        .padding(.bottom, DarkroomDimens.photosHeaderBottom)
        .frame(height: DarkroomDimens.photosHeaderHeight, alignment: .bottom)
        .background(DarkroomColors.room)
        .environment(\.locale, locale)
    }

    private var bigNumber: some View {
        OdometerText(value: Int64(count), text: formattedCount, font: DarkroomType.countBig.monospacedDigit(), color: DarkroomColors.paper, tracking: -0.6)
            .lineLimit(1)
            .minimumScaleFactor(0.7)
    }

    @ViewBuilder private var unitText: some View {
        if !unit.isEmpty {
            Text(unit)
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

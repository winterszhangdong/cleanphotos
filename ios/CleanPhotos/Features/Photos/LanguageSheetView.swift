import SwiftUI

/// 贴底全宽语言托盘。实心托盘色，只圆顶部两角。不跳系统设置。
struct LanguageSheetView: View {
    var selection: AppLanguagePreference
    var onSelect: (AppLanguagePreference) -> Void
    @EnvironmentObject private var language: LanguageStore

    private var shape: UnevenRoundedRectangle {
        UnevenRoundedRectangle(
            topLeadingRadius: DarkroomDimens.langSheetCorner,
            bottomLeadingRadius: 0,
            bottomTrailingRadius: 0,
            topTrailingRadius: DarkroomDimens.langSheetCorner,
            style: .continuous
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("language_title")
                .font(Font.custom("NotoSansSC-SemiBold", size: 17))
                .foregroundStyle(DarkroomColors.paper)
                .padding(.horizontal, 16)
                .padding(.bottom, 8)

            ForEach(AppLanguagePreference.allCases) { pref in
                let on = pref == selection
                Button {
                    onSelect(pref)
                } label: {
                    HStack {
                        Text(pref.fixedTitle(
                            followSystemLocalized: String(localized: String.LocalizationValue("language_follow_system"), locale: language.effectiveLocale)
                        ))
                        .font(on ? DarkroomType.menuItemOn : DarkroomType.menuItem)
                        .foregroundStyle(on ? DarkroomColors.paper : DarkroomColors.dim)
                        .lineLimit(1)
                        Spacer(minLength: 8)
                        if on {
                            DarkroomIconView(icon: .checkBold, size: 18)
                                .foregroundStyle(DarkroomColors.amber)
                        }
                    }
                    .padding(.horizontal, 16)
                    .frame(height: DarkroomDimens.langItemHeight)
                    .background(
                        RoundedRectangle(cornerRadius: DarkroomDimens.menuItemCorner, style: .continuous)
                            .fill(on ? DarkroomColors.menuItemOn : Color.clear)
                    )
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.top, 24)
        .padding(.horizontal, 8)
        .padding(.bottom, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            shape
                .fill(DarkroomColors.tray)
                .overlay(shape.strokeBorder(DarkroomColors.rim, lineWidth: 1))
                .ignoresSafeArea(edges: .bottom)
        }
    }
}

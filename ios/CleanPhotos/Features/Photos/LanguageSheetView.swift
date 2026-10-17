import SwiftUI

/// In-app language sheet — three options only. Never opens system Settings.
struct LanguageSheetView: View {
    var selection: AppLanguagePreference
    var onSelect: (AppLanguagePreference) -> Void
    @EnvironmentObject private var language: LanguageStore

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("language_title")
                .font(DarkroomType.menuHeader)
                .tracking(1.4)
                .foregroundStyle(DarkroomColors.dim)
                .padding(.horizontal, 16)
                .padding(.top, 12)
                .padding(.bottom, 6)

            ForEach(AppLanguagePreference.allCases) { pref in
                let on = pref == selection
                Button {
                    onSelect(pref)
                } label: {
                    HStack {
                        Text(pref.fixedTitle(
                            followSystemLocalized: String(localized: String.LocalizationValue("language_follow_system"), locale: language.effectiveLocale)
                        ))
                        .font(on ? DarkroomType.menuItemOn : Font.custom("NotoSansSC-Regular", size: 15))
                        .foregroundStyle(on ? DarkroomColors.paper : DarkroomColors.dim)
                        Spacer()
                        if on {
                            Image(systemName: "checkmark")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(DarkroomColors.amber)
                        }
                    }
                    .padding(.horizontal, 16)
                    .frame(height: DarkroomDimens.langItemHeight)
                    .background(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(on ? DarkroomColors.menuItemOn : Color.clear)
                    )
                }
                .buttonStyle(.plain)
            }
        }
        .padding(4)
        .background {
            RoundedRectangle(cornerRadius: DarkroomDimens.langSheetCorner, style: .continuous)
                .fill(DarkroomColors.menuGlass)
                .overlay(
                    RoundedRectangle(cornerRadius: DarkroomDimens.langSheetCorner, style: .continuous)
                        .strokeBorder(DarkroomColors.menuRim, lineWidth: 1)
                )
                .shadow(color: DarkroomColors.menuShadow, radius: 24, y: 12)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: DarkroomDimens.langSheetCorner, style: .continuous))
        }
    }
}

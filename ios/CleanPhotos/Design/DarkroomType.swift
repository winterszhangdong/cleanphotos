import SwiftUI

enum DarkroomFonts {
    // PostScript names from the bundled TTFs (registered via UIAppFonts).
    static func noto(_ weight: Font.Weight) -> Font {
        let name: String
        switch weight {
        case .medium: name = "NotoSansSC-Medium"
        case .semibold: name = "NotoSansSC-SemiBold"
        case .bold: name = "NotoSansSC-Bold"
        default: name = "NotoSansSC-Regular"
        }
        return Font.custom(name, size: 14, relativeTo: .body)
    }

    static func schibsted(size: CGFloat, weight: Font.Weight = .semibold) -> Font {
        let name: String
        switch weight {
        case .bold: name = "SchibstedGrotesk-Bold"
        case .medium: name = "SchibstedGrotesk-Medium"
        default: name = "SchibstedGrotesk-SemiBold"
        }
        return Font.custom(name, size: size)
    }

    static func plexMono(size: CGFloat, weight: Font.Weight = .regular) -> Font {
        let name = weight == .medium ? "IBMPlexMono-Medium" : "IBMPlexMono-Regular"
        return Font.custom(name, size: size)
    }
}

enum DarkroomType {
    static let countBig = DarkroomFonts.schibsted(size: 30, weight: .semibold)
    static let countUnit = Font.custom("NotoSansSC-Regular", size: 13)
    static let countSub = DarkroomFonts.plexMono(size: 11)
    static let eyebrow = Font.custom("NotoSansSC-Regular", size: 12)
    static let navLabel = Font.custom("NotoSansSC-Regular", size: 13)
    static let navBadge = DarkroomFonts.plexMono(size: 10)
    static let dayNumber = DarkroomFonts.schibsted(size: 24, weight: .semibold)
    static let dayMeta = Font.custom("NotoSansSC-Regular", size: 12)
    static let dayRight = DarkroomFonts.plexMono(size: 11)
    static let menuHeader = Font.custom("NotoSansSC-Regular", size: 11)
    static let menuItem = Font.custom("NotoSansSC-Regular", size: 14)
    static let menuItemOn = Font.custom("NotoSansSC-Medium", size: 14)
    static let body = Font.custom("NotoSansSC-Regular", size: 14)
    static let title = Font.custom("NotoSansSC-SemiBold", size: 18)
    static let permTitle = Font.custom("NotoSansSC-SemiBold", size: 24)
}

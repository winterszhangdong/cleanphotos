import Foundation
import SwiftUI
import Combine

/// App language preference. Matches Android: Follow system / 简体中文 / English.
/// Never opens system Settings — override stays in-app via locale environment + UserDefaults.
enum AppLanguagePreference: String, CaseIterable, Identifiable {
    case system
    case zhHans
    case english

    var id: String { rawValue }

    /// Fixed labels that do not change with UI language (except Follow system).
    func fixedTitle(followSystemLocalized: String) -> String {
        switch self {
        case .system: return followSystemLocalized
        case .zhHans: return "简体中文"
        case .english: return "English"
        }
    }
}

@MainActor
final class LanguageStore: ObservableObject {
    private static let key = "cleanphotos.appLanguage"

    @Published var preference: AppLanguagePreference {
        didSet {
            UserDefaults.standard.set(preference.rawValue, forKey: Self.key)
            applyAppleLanguages()
            Self.cachedSystemZh = nil
        }
    }

    init() {
        let raw = UserDefaults.standard.string(forKey: Self.key) ?? AppLanguagePreference.system.rawValue
        preference = AppLanguagePreference(rawValue: raw) ?? .system
        applyAppleLanguages()
    }

    private static let zhLocale = Locale(identifier: "zh-Hans")
    private static let enLocale = Locale(identifier: "en")
    /// Locale.preferredLanguages 每次都要读一遍偏好设置，而 effectiveLocale 清理页每帧要取几十次：缓存，系统语言变了再重读。
    private static var cachedSystemZh: Bool?
    private static var localeObserver: NSObjectProtocol?
    private static var systemPrefersChinese: Bool {
        if let v = cachedSystemZh { return v }
        if localeObserver == nil {
            localeObserver = NotificationCenter.default.addObserver(forName: NSLocale.currentLocaleDidChangeNotification, object: nil, queue: .main) { _ in
                MainActor.assumeIsolated { cachedSystemZh = nil }
            }
        }
        let v = Locale.preferredLanguages.first?.lowercased().hasPrefix("zh") ?? false
        cachedSystemZh = v
        return v
    }

    private func applyAppleLanguages() {
        switch preference {
        case .system:
            UserDefaults.standard.removeObject(forKey: "AppleLanguages")
        case .zhHans:
            UserDefaults.standard.set(["zh-Hans"], forKey: "AppleLanguages")
        case .english:
            UserDefaults.standard.set(["en"], forKey: "AppleLanguages")
        }
        UserDefaults.standard.synchronize()
    }

    var effectiveLocale: Locale {
        switch preference {
        case .system:
            // Chinese (any region / script) → zh-Hans UI; else English.
            return Self.systemPrefersChinese ? Self.zhLocale : Self.enLocale
        case .zhHans:
            return Self.zhLocale
        case .english:
            return Self.enLocale
        }
    }

    var isChineseUI: Bool {
        effectiveLocale.identifier.lowercased().hasPrefix("zh")
    }

    func select(_ pref: AppLanguagePreference) {
        preference = pref
    }
}


enum L10n {
    /// 显式 locale 的 String(localized:) 不走 CFBundle 的表缓存，每次都 open/读 .strings 文件（清理页每帧几十次），这里自己缓存。
    private static var cache: [String: String] = [:]
    private static let lock = NSLock()

    static func string(_ key: String, locale: Locale) -> String {
        let k = locale.identifier + "\u{1}" + key
        lock.lock()
        if !PerfLegacy.on, let v = cache[k] { lock.unlock(); return v }
        lock.unlock()
        var resource = LocalizedStringResource(String.LocalizationValue(stringLiteral: key))
        resource.locale = locale
        let v = String(localized: resource)
        lock.lock(); cache[k] = v; lock.unlock()
        return v
    }

    static func format(_ key: String, locale: Locale, _ args: CVarArg...) -> String {
        String(format: string(key, locale: locale), locale: locale, arguments: args)
    }

    /// 英文按 one / other 选 key（`<key>_one`），中文只有一种；数字用 %d。
    static func plural(_ key: String, count: Int, locale: Locale) -> String {
        let isZh = locale.identifier.lowercased().hasPrefix("zh")
        let k = (!isZh && count == 1) ? key + "_one" : key
        return String(format: string(k, locale: locale), locale: locale, count)
    }

    /// Plural key chosen by `count`, formatted with explicit args (for %1$@ templates).
    static func pluralFormat(_ key: String, count: Int, locale: Locale, _ args: CVarArg...) -> String {
        let isZh = locale.identifier.lowercased().hasPrefix("zh")
        let k = (!isZh && count == 1) ? key + "_one" : key
        return String(format: string(k, locale: locale), locale: locale, arguments: args)
    }
}

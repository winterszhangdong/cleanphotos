import Foundation

/// 垃圾桶页新增文案。已有 key 仍走 L10n；这里的句子不进 Localizable.xcstrings。
enum TrashCopy {
    static func oldestDays(_ days: Int, locale: Locale) -> String {
        if locale.identifier.lowercased().hasPrefix("zh") {
            return "最早一张已放 \(days) 天"
        }
        let unit = days == 1 ? "day" : "days"
        return "Oldest item: \(days) \(unit) in trash"
    }

    static func totalFreed(_ size: String, locale: Locale) -> String {
        if locale.identifier.lowercased().hasPrefix("zh") {
            return "累计已释放 \(size)"
        }
        return "Freed \(size) in total"
    }
}

import Foundation

/// DateFormatter 创建 + 首次格式化要建 ICU 格式器（每次几百微秒到毫秒），视图 body 里每帧 new 一个会掉帧。
/// 按 (locale, format) 缓存；只在主线程用。
enum DateFormatCache {
    private static var cache: [String: DateFormatter] = [:]

    static func formatter(_ format: String, locale: Locale) -> DateFormatter {
        let k = locale.identifier + "|" + format
        if !PerfLegacy.on, let f = cache[k] { return f }
        let f = DateFormatter()
        f.locale = locale
        f.dateFormat = format
        cache[k] = f
        return f
    }

    static func string(_ date: Date, format: String, locale: Locale) -> String {
        formatter(format, locale: locale).string(from: date)
    }
}

import Foundation

// 照片库层的纯规则（无 PhotoKit 依赖，可单测）。对应 Android：
//   MediaStorePhotoDataSource.dayKeyOf（timestamp <= 0 → DayKey.UNKNOWN）
//   PhotoRepositoryImpl.refreshPhotos / cleanupStores（只有完整访问权才清理失效条目）

enum DayKeyRule {
    /// 「未知日期」天键（与 ReviewRules.unknownDay 同值）。
    static let unknown = "unknown"

    /// 没有拍摄时间（PhotoKit creationDate 为 nil → 这里收到 distantPast）或时间戳 <= 0：未知日期。
    static func isUnknown(_ date: Date) -> Bool { date.timeIntervalSince1970 <= 0 }

    /// 「yyyy-MM-dd」（给定日历 / 时区）；未知日期返回 `unknown`。
    static func key(_ date: Date, calendar: Calendar = .current) -> String {
        if isUnknown(date) { return unknown }
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }
}

enum StoreCleanupPolicy {
    /// 收藏 / 看过 / 垃圾桶里要删掉的失效 id；返回 nil 表示这次不清理。
    /// - 只有完整访问权（`fullAccess`）才清理：「部分照片」访问时查到的只是用户选中的那几张，
    ///   拿它当整个图库会把其余照片的状态全部删掉（Android 同规则）。
    /// - `existing` 为 nil（查询失败）不清理。
    /// - `existing` 是按已存 id 回查到的那部分。保护：存了东西、图库也不是空的，却一个都没查到 —— 多半是查询异常，不清理。
    static func idsToRemove(stored: Set<String>, existing: Set<String>?, fullAccess: Bool, libraryCount: Int) -> Set<String>? {
        guard fullAccess, let existing else { return nil }
        guard !stored.isEmpty else { return [] }
        if libraryCount > 0 && existing.isEmpty { return nil }
        return stored.subtracting(existing)
    }
}

enum TrashTotals {
    /// 「清空后可释放」：只算图库里还在的垃圾桶照片（和垃圾桶数量同一个口径）。
    static func bytes(entrySizes: [String: Int64], present: [String]) -> Int64 {
        present.reduce(Int64(0)) { $0 + max(0, entrySizes[$1] ?? 0) }
    }
}

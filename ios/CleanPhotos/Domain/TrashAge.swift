import Foundation

/// 「清空后可释放」卡片：最早一张已经放了几天。
/// 一天按 24 小时（不是自然日），时间戳是 Unix 毫秒，和 Android TrashOldestDays 同一把尺子。
/// 最早一张距 now 已满 30 天时返回已放的整天数；未满、或没有移入时间时返回 nil。
enum TrashAge {
    static let msPerDay: Int64 = 24 * 60 * 60 * 1000
    static let thresholdMs: Int64 = 30 * msPerDay

    static func wholeDays(movedAtMs: [Int64], nowMs: Int64) -> Int? {
        guard let earliest = movedAtMs.min() else { return nil }
        let age = nowMs - earliest
        guard age >= thresholdMs else { return nil }
        return Int(age / msPerDay)
    }

    static func unixMillis(_ time: TimeInterval) -> Int64 {
        Int64((time * 1000).rounded())
    }
}

import Foundation

/// 清空后溶解的时间表（对齐 Android TrashDissolveGrid / MotionDurations / dissolveClockMillis）。
enum DissolveTiming {
    static let item = 0.38       // DISSOLVE_ITEM
    static let stagger = 0.06    // DISSOLVE_STAGGER
    /// Android 只数可见项；一屏约 5 行 × 3
    static let maxVisible = 15
    /// 第 i 张的起点：第 0 项是 header，所以从 (i+1)·60ms 起
    static func start(index i: Int) -> Double { Double(i + 1) * stagger }
    /// 时钟总长 = 最后一张可见格子的起点 + 一整格（之前少一个步长，最后一张停在半溶解）
    static func clockEnd(count: Int) -> Double {
        let visible = min(count, maxVisible)
        return visible > 0 ? start(index: visible - 1) + item : item
    }
    /// 第 i 张的线性进度 0…1（视图里再套加速离场曲线）
    static func linearProgress(index i: Int, clock: Double) -> Double {
        min(1, max(0, (clock - start(index: i)) / item))
    }
}

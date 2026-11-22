import Foundation

/// 照片页滚动叠层的纯计算：顶栏收起、日期吸顶、日期滑块。
/// 这些数在滚动过程中只留在引用类型里，由 UIKit 改叠层位置，不写回列表所在的 SwiftUI 状态。
enum ScrollChrome {
    /// 手指或惯性还在走。`forceOpen`（多选、菜单、弹层，或列表已经在顶上）时保持展开。
    static func collapseWhileMoving(
        current: CGFloat,
        delta: CGFloat,
        scrollY: CGFloat,
        chromeHeight: CGFloat,
        forceOpen: Bool
    ) -> CGFloat {
        if forceOpen || scrollY <= 1 { return 0 }
        return min(chromeHeight, max(0, current + delta))
    }

    /// 停稳后吸到完全收起或完全展开。
    static func collapseSnap(
        current: CGFloat,
        scrollY: CGFloat,
        chromeHeight: CGFloat,
        forceOpen: Bool
    ) -> CGFloat {
        if forceOpen || scrollY <= 1 { return 0 }
        return current > chromeHeight / 2 ? chromeHeight : 0
    }

    struct DayMark: Equatable {
        var id: String
        /// 内容坐标。还没量到就是 nil，这一天不参与吸顶。
        var headerY: CGFloat?
        var headerHeight: CGFloat
        var lastY: CGFloat?
        var lastHeight: CGFloat?
    }

    struct Stuck: Equatable {
        var id: String
        /// 叠层在视口里的 y。
        var y: CGFloat
    }

    /// `days` 按列表顺序（可能含未测量的 `headerY == nil`）。用于兼容与通用场景。
    static func stuck(pinY: CGFloat, scrollY: CGFloat, days: [DayMark]) -> Stuck? {
        guard let lastId = days.last?.id else { return nil }
        for i in days.indices {
            let day = days[i]
            guard let headerY = day.headerY else { continue }
            let nextTop: CGFloat? = {
                for j in (i + 1)..<days.count {
                    if let y = days[j].headerY { return y - scrollY }
                }
                return nil
            }()
            let isLast = day.id == lastId
            if let y = DateStick.stickY(
                pinY: pinY,
                headerHeight: day.headerHeight,
                headerTop: headerY - scrollY,
                nextHeaderTop: nextTop,
                lastItemTop: isLast ? day.lastY.map { $0 - scrollY } : nil,
                lastItemHeight: isLast ? day.lastHeight : nil,
                sectionVisible: true
            ) {
                return Stuck(id: day.id, y: y)
            }
        }
        return nil
    }

    /// `days` 已按 `headerY` 升序排列且均已布局得到 `headerY`。用 O(log D) 二分查找当前吸顶天。
    static func stuckSorted(pinY: CGFloat, scrollY: CGFloat, days: [DayMark]) -> Stuck? {
        guard !days.isEmpty else { return nil }
        var lo = 0
        var hi = days.count - 1
        var candidate = -1
        while lo <= hi {
            let mid = (lo + hi) >> 1
            guard let hy = days[mid].headerY else {
                return stuck(pinY: pinY, scrollY: scrollY, days: days)
            }
            if hy - scrollY < pinY {
                candidate = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        guard candidate >= 0 else { return nil }
        let day = days[candidate]
        guard let headerY = day.headerY else { return nil }
        let isLast = candidate == days.count - 1
        let nextTop: CGFloat? = isLast ? nil : days[candidate + 1].headerY.map { $0 - scrollY }
        guard let y = DateStick.stickY(
            pinY: pinY,
            headerHeight: day.headerHeight,
            headerTop: headerY - scrollY,
            nextHeaderTop: nextTop,
            lastItemTop: isLast ? day.lastY.map { $0 - scrollY } : nil,
            lastItemHeight: isLast ? day.lastHeight : nil,
            sectionVisible: true
        ) else { return nil }
        return Stuck(id: day.id, y: y)
    }

    /// 滑块当前落在哪一天。`anchors` 是各天在内容坐标里的升序落点，用 O(log D) 二分查找。
    static func scrubIndex(scrollY: CGFloat, pinY: CGFloat, anchors: [CGFloat]) -> Int {
        guard !anchors.isEmpty else { return 0 }
        var lo = 0
        var hi = anchors.count - 1
        var result = 0
        while lo <= hi {
            let mid = (lo + hi) >> 1
            if anchors[mid] - scrollY <= pinY + 1 {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }

    static func scrubThumbY(index: Int, count: Int, top: CGFloat, bottom: CGFloat, thumbHeight: CGFloat) -> CGFloat {
        let travel = max(1, bottom - top - thumbHeight)
        let t: CGFloat = count <= 1 ? 0 : CGFloat(index) / CGFloat(count - 1)
        return top + t * travel
    }
}

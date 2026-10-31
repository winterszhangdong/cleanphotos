import CoreGraphics
import Foundation

/// 垃圾桶预览邻页。先把下标夹进当前列表，空列表不产生区间。
/// Swift 闭区间要求下界 ≤ 上界；列表被系统相册一次删短时，不能用旧下标直接拼 `...`。
enum TrashPreviewWindow {
    static func clampedIndex(_ index: Int, count: Int) -> Int? {
        guard count > 0 else { return nil }
        return min(max(0, index), count - 1)
    }

    /// 当前张和左右各一张。空列表为空数组，下标永远落在 `0..<count`。
    static func neighbors(index: Int, count: Int) -> [Int] {
        span(index: index, count: count, radius: 1)
    }

    static func span(index: Int, count: Int, radius: Int) -> [Int] {
        guard let i = clampedIndex(index, count: count) else { return [] }
        let lower = max(0, i - radius)
        let upper = min(count - 1, i + radius)
        return Array(lower...upper)
    }
}

/// 清理页松手时要不要用最终 translation。捏合单独收尾，不并进平移或上下滑。
enum ReviewGestureEnd {
    enum Axis: Equatable { case horizontal, vertical, pan }
    enum Finish: Equatable {
        /// 方向已定，或抬手时才补定方向：调用方用这次的 translation 重算位移再释放。
        case sample
        /// 放大后的平移。偏移是 panBase + 最终 translation，还没按视口夹取。
        case pan(CGSize)
        /// 这次触摸里捏合过。clearPan 表示拖动轴还停在平移上，调用方只清轴，不提交滑动。
        case pinch(clearPan: Bool)
    }

    static func finish(pinched: Bool, axis: Axis?, translation: CGSize, panBase: CGSize) -> Finish {
        if pinched { return .pinch(clearPan: axis == .pan) }
        if axis == .pan { return .pan(panOffset(base: panBase, translation: translation)) }
        return .sample
    }

    /// 跟手位移：总 translation 减去定方向时扣掉的死区。松手和拖动中用同一个式子。
    static func displacement(raw: CGFloat, slop: CGFloat) -> CGFloat { raw - slop }

    static func panOffset(base: CGSize, translation: CGSize) -> CGSize {
        CGSize(width: base.width + translation.width, height: base.height + translation.height)
    }
}

/// 放大后拖动的边界。fit 用扣除上下安全区后的方框，和清理页照片的实际矩形一致。
enum ZoomClamp {
    struct Fit: Equatable {
        var size: CGSize
        var centerShiftY: CGFloat
    }

    static func fit(pixelWidth: Int, pixelHeight: Int, screen: CGSize, top: CGFloat, bottom: CGFloat) -> Fit {
        let box = CGSize(width: screen.width, height: screen.height - top - bottom)
        let shift = (top - bottom) / 2
        guard pixelWidth > 0, pixelHeight > 0, box.width > 0, box.height > 0 else {
            return Fit(size: .zero, centerShiftY: shift)
        }
        let s = min(box.width / CGFloat(pixelWidth), box.height / CGFloat(pixelHeight))
        return Fit(size: CGSize(width: CGFloat(pixelWidth) * s, height: CGFloat(pixelHeight) * s), centerShiftY: shift)
    }

    /// 屏幕坐标里把缩放后的照片边限制在视口内。某一轴缩放后仍不比视口宽/高，这一轴不能拖。
    /// 竖直方向要扣掉安全区造成的中心偏移（渲染时先偏移再绕屏幕中心缩放）。
    static func offset(proposed: CGSize, zoom: CGFloat, fit: CGSize, screen: CGSize, centerShiftY: CGFloat) -> CGSize {
        guard zoom > 1, fit.width > 0, fit.height > 0 else { return .zero }
        let visualW = fit.width * zoom
        let visualH = fit.height * zoom
        let ox: CGFloat
        if visualW <= screen.width {
            ox = 0
        } else {
            let limit = (visualW - screen.width) / 2
            ox = min(limit, max(-limit, proposed.width))
        }
        let oy: CGFloat
        if visualH <= screen.height {
            oy = 0
        } else {
            let shift = centerShiftY * zoom
            let limit = (visualH - screen.height) / 2
            oy = min(limit - shift, max(-limit - shift, proposed.height))
        }
        return CGSize(width: ox, height: oy)
    }
}

/// 多选刷新后保留的 id。范围是当天完整列表，不是格子上画出来的前几张。
enum SelectionPrune {
    static func remaining(selected: Set<String>, scope: Set<String>) -> Set<String> {
        selected.intersection(scope)
    }
}

/// 图库还在吐首屏时不打开清理会话，避免把不完整队列写进账本。已打开的会话也不换一批照片重建。
enum ReviewOpenGate {
    static func allows(partial: Bool, sessionOpen: Bool) -> Bool {
        !partial && !sessionOpen
    }
}

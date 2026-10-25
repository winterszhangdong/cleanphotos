package com.winters.cleanphotos.feature.review

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

import com.winters.cleanphotos.core.design.GestureTokens
import kotlin.math.abs
import kotlin.math.sign

/**
 * 清理页手势的纯计算（interaction.md「滑动手感硬性要求」），单位全部是 px / px·s⁻¹，
 * 由调用处按 density 把 GestureTokens 的 dp 换算好传进来。
 */
internal data class ReviewThresholds(
    val vertical: Float,
    val verticalFlingVelocity: Float,
    val minFlingDistance: Float,
    val horizontal: Float,
    val horizontalFlingVelocity: Float,
    val effectCap: Float,
    val edgeLength: Float,
)

internal enum class DragAxis { None, Horizontal, Vertical, Pan }

internal enum class VerticalRelease { Up, Down, None }

internal object ReviewGestureMath {

    /** 水平翻页阈值：屏宽 12%，48–96dp。 */
    fun horizontalThreshold(widthPx: Float, minPx: Float, maxPx: Float): Float =
        (widthPx * GestureTokens.HORIZONTAL_THRESHOLD_FRACTION).coerceIn(minPx, maxPx)

    /** 越过 slop 后的方向：主轴至少是另一轴的 1.2 倍，否则继续等。 */
    /** 定方向那一刻交给 onDrag 的首段位移：沿选定轴、扣掉 touch slop（不足 slop 时为 0）。 */
    fun slopTrimmedStart(axis: DragAxis, total: Offset, slop: Float): Offset = when (axis) {
        DragAxis.Horizontal -> Offset(total.x - sign(total.x) * minOf(slop, abs(total.x)), 0f)
        DragAxis.Vertical -> Offset(0f, total.y - sign(total.y) * minOf(slop, abs(total.y)))
        else -> total
    }

    /** 抬起事件自带的位移该怎么处理。[startAxis] 非空 = 抬起前尚未定方向，需先补一次 onDragStart。 */
    data class UpDrag(val axis: DragAxis, val startAxis: Boolean, val delta: Offset)

    /**
     * 抬起事件里的最后一段位移（主线程忙时多个 MOVE 合并、或抬起点与最后一个 MOVE 不同）也要算进拖动，
     * 否则快速连滑会把过阈值的一下判成没过阈值甚至点按。[total] 已含 [upDelta]。返回 null = 无需处理。
     */
    fun upEventDrag(axis: DragAxis, total: Offset, upDelta: Offset, slop: Float, zoomed: Boolean): UpDrag? {
        if (upDelta == Offset.Zero) return null
        if (axis != DragAxis.None) return UpDrag(axis, startAxis = false, delta = upDelta)
        if (total.getDistance() <= slop) return null
        // 放大后整段位移只出现在抬起事件里时，按平移处理，不能当成点按或双击。
        if (zoomed) return UpDrag(DragAxis.Pan, startAxis = true, delta = slopTrimmedStart(DragAxis.Pan, total, slop))
        val decided = axisOf(total.x, total.y)
        if (decided == DragAxis.None) return null
        return UpDrag(decided, startAxis = true, delta = slopTrimmedStart(decided, total, slop))
    }

    fun axisOf(dx: Float, dy: Float): DragAxis = when {
        abs(dx) >= abs(dy) * GestureTokens.DIRECTION_RATIO -> DragAxis.Horizontal
        abs(dy) >= abs(dx) * GestureTokens.DIRECTION_RATIO -> DragAxis.Vertical
        else -> DragAxis.None
    }

    /**
     * 上下松手：距离过阈值，或朝该方向的速度过甩动阈值且位移至少 24dp。
     * 速度与位移方向相反（往回甩）不触发。
     */
    fun verticalRelease(dy: Float, vy: Float, t: ReviewThresholds): VerticalRelease {
        val byDistance = abs(dy) > t.vertical
        val byFling = abs(dy) >= t.minFlingDistance && abs(vy) > t.verticalFlingVelocity && sign(vy) == sign(dy)
        if (!byDistance && !byFling) return VerticalRelease.None
        if (byDistance && vy != 0f && sign(vy) != sign(dy) && abs(vy) > t.verticalFlingVelocity) return VerticalRelease.None
        return if (dy < 0f) VerticalRelease.Up else VerticalRelease.Down
    }

    /**
     * 左右松手：返回 -1 = 去下一张（左滑），1 = 去上一张（右滑），0 = 回位。
     * 距离过阈值，或朝该方向甩动速度过 600dp/s；朝反方向用力甩回时不翻。
     */
    fun horizontalRelease(dx: Float, vx: Float, t: ReviewThresholds): Int {
        if (dx == 0f) return 0
        val sameDir = vx == 0f || sign(vx) == sign(dx)
        val byFling = abs(vx) > t.horizontalFlingVelocity && sign(vx) == sign(dx)
        val byDistance = abs(dx) > t.horizontal && (sameDir || abs(vx) <= t.horizontalFlingVelocity)
        return if (byFling || byDistance) sign(dx).toInt() else 0
    }

    /** 拖动进度 0..1（封顶 170dp）。 */
    fun effectFraction(dy: Float, t: ReviewThresholds): Float = (abs(dy) / t.effectCap).coerceIn(0f, 1f)

    /** 决定 3：上下都随距离 1 → 0.9。 */
    fun dragScale(dy: Float, t: ReviewThresholds): Float =
        1f - (1f - GestureTokens.DRAG_MIN_SCALE) * effectFraction(dy, t)

    /** 决定 4：下滑饱和 1 → 0.35，连续变化，过阈值不跳档。 */
    fun dragSaturation(dy: Float, t: ReviewThresholds): Float =
        if (dy <= 0f) 1f else 1f - (1f - GestureTokens.DRAG_MIN_SATURATION) * effectFraction(dy, t)

    /** 决定 4：下滑亮度 1 → 0.55。 */
    fun dragBrightness(dy: Float, t: ReviewThresholds): Float =
        if (dy <= 0f) 1f else 1f - (1f - GestureTokens.DRAG_MIN_BRIGHTNESS) * effectFraction(dy, t)

    /** 提示圆描边进度 = 距离 / 阈值。 */
    fun cueProgress(dy: Float, t: ReviewThresholds): Float = (abs(dy) / t.vertical).coerceIn(0f, 1f)

    /** 左右翻页进入页亮度 0.7 → 1，随位移。 */
    fun enteringBrightness(dx: Float, width: Float): Float {
        val f = (abs(dx) / width).coerceIn(0f, 1f)
        return GestureTokens.PAGE_ENTER_BRIGHTNESS + (1f - GestureTokens.PAGE_ENTER_BRIGHTNESS) * f
    }

    /** 会话首尾越界阻尼（决定 1：一天首尾不阻尼，只在整个会话首尾）。 */
    fun horizontalOffset(rawDx: Float, hasPrevious: Boolean, hasNext: Boolean, t: ReviewThresholds): Float = when {
        rawDx > 0f && !hasPrevious -> GestureTokens.edgeResistance(rawDx, t.edgeLength)
        rawDx < 0f && !hasNext -> GestureTokens.edgeResistance(rawDx, t.edgeLength)
        else -> rawDx
    }

    /** 照片保持原比例 Fit 进容器后的尺寸；宽高未知时返回容器尺寸（内容在容器里按 Fit 画）。 */
    fun fitSize(photoWidth: Int?, photoHeight: Int?, containerWidth: Float, containerHeight: Float): Size {
        if (photoWidth == null || photoHeight == null) return Size(containerWidth, containerHeight)
        val scale = minOf(containerWidth / photoWidth, containerHeight / photoHeight)
        return Size(photoWidth * scale, photoHeight * scale)
    }
}

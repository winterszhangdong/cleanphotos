package com.winters.cleanphotos.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 范围刻度的位置和透明度。进度以页为单位，可以为小数，也可以滑出两端。
 * 颜色是相纸白乘 [alpha]，不另换色。
 */
object ScopeDialMath {
    const val MIN_ALPHA = 0.22f
    const val FALLOFF = 0.55f

    /** 进入页亮度 0.7：在页上叠一层暗室色，透明度是这个数。 */
    const val PAGE_DIM = 0.3f

    fun alpha(distance: Float): Float = maxOf(MIN_ALPHA, 1f - FALLOFF * distance)

    /** 第 [index] 个标签的文字中心，相对第一段文字的左边缘。 */
    fun center(index: Int, widths: FloatArray, gap: Float): Float {
        var x = 0f
        for (i in 0 until index) x += widths[i] + gap
        return x + widths[index] / 2f
    }

    /**
     * 进度 [progress] 处的文字中心。相邻标签之间线性，两端按相邻中心的间距外推。
     * 只有一个标签时，外推步长是文字宽加间距。
     */
    fun centerAt(progress: Float, widths: FloatArray, gap: Float): Float {
        if (widths.isEmpty()) return 0f
        val last = widths.lastIndex
        if (last == 0) return widths[0] / 2f + progress * (widths[0] + gap)
        val i0 = floor(progress.toDouble()).toInt()
        val t = progress - i0
        return at(i0, widths, gap, last) + (at(i0 + 1, widths, gap, last) - at(i0, widths, gap, last)) * t
    }

    private fun at(index: Int, widths: FloatArray, gap: Float, last: Int): Float = when {
        index < 0 -> {
            val step = center(1, widths, gap) - center(0, widths, gap)
            center(0, widths, gap) + index * step
        }
        index > last -> {
            val step = center(last, widths, gap) - center(last - 1, widths, gap)
            center(last, widths, gap) + (index - last) * step
        }
        else -> center(index, widths, gap)
    }

    /** 让 [progress] 的文字中心落在视口中心。 */
    fun translationX(viewportCenter: Float, progress: Float, widths: FloatArray, gap: Float): Float =
        viewportCenter - centerAt(progress, widths, gap)

    /** 当前进度这一步的中心间距。手指向左移过这么远，页进度加 1。 */
    fun pageStride(progress: Float, widths: FloatArray, gap: Float): Float {
        if (widths.size <= 1) {
            val width = widths.firstOrNull() ?: 0f
            return (width + gap).coerceAtLeast(1f)
        }
        val i = floor(progress.toDouble()).toInt().coerceIn(0, widths.lastIndex - 1)
        return (center(i + 1, widths, gap) - center(i, widths, gap)).coerceAtLeast(1f)
    }

    /** 手指位移换成页进度。向左为负的 [dx] 得到正的页数。 */
    fun dragPages(dx: Float, stride: Float): Float = -dx / stride.coerceAtLeast(1f)

    /** 热区：文字左右各延伸半个间距，并且至少 [minTouch] 宽。 */
    fun hitWidth(textWidth: Float, gap: Float, minTouch: Float): Float = maxOf(textWidth + gap, minTouch)

    /** [contentX] 落在哪个标签的热区里。重叠时取离文字中心更近的。点在外面返回 -1。 */
    fun hitIndex(contentX: Float, widths: FloatArray, gap: Float, minTouch: Float): Int {
        var best = -1
        var bestDist = Float.POSITIVE_INFINITY
        for (i in widths.indices) {
            val c = center(i, widths, gap)
            val distance = abs(contentX - c)
            if (distance <= hitWidth(widths[i], gap, minTouch) / 2f && distance < bestDist) {
                best = i
                bestDist = distance
            }
        }
        return best
    }
}

/** 布局阶段写下文字宽度，绘制阶段读。不放进组合状态。 */
private class DialMeasures {
    var widths: FloatArray = FloatArray(0)
    var gapPx: Float = 0f
    var minTouchPx: Float = 0f
    var viewportWidth: Float = 0f
}

/**
 * 照片页范围刻度。选中项由 [progress] 居中（页单位，可为小数），短线固定在行正中。
 * [progress] 只在 graphicsLayer 里读。
 * [selectedIndex] 只给读屏报当前停稳的项。
 * 横滑通过 [onDrag] 交给外面的 pager：通道里是页进度增量，松手后用第二个参数读页/秒。一次拖动只开一轮滚动。
 */
@Composable
fun ScopeDial(
    labels: List<String>,
    progress: () -> Float,
    onSelect: (Int) -> Unit,
    selectedIndex: Int,
    scrollEnabled: Boolean,
    onDrag: suspend (ReceiveChannel<Float>, () -> Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { Dimens.ScopeDialGap.toPx() }
    val minTouchPx = with(density) { Dimens.MinTouch.toPx() }
    val fadePx = with(density) { Dimens.ScopeDialEdgeFade.toPx() }
    val measures = remember { DialMeasures() }
    measures.gapPx = gapPx
    measures.minTouchPx = minTouchPx
    val progressState = rememberUpdatedState(progress)
    val onSelectState = rememberUpdatedState(onSelect)
    val scrollEnabledState = rememberUpdatedState(scrollEnabled)
    val onDragState = rememberUpdatedState(onDrag)
    Box(
        modifier
            .fillMaxWidth()
            .height(Dimens.ScopeRowHeight)
            .onSizeChanged { measures.viewportWidth = it.width.toFloat() }
            .clipToBounds()
            .pointerInput(Unit) {
                detectScopeDial(
                    scrollEnabled = { scrollEnabledState.value },
                    stride = {
                        ScopeDialMath.pageStride(progressState.value(), measures.widths, measures.gapPx)
                    },
                    hitIndex = { localX ->
                        val translation = ScopeDialMath.translationX(
                            measures.viewportWidth / 2f,
                            progressState.value(),
                            measures.widths,
                            measures.gapPx,
                        )
                        ScopeDialMath.hitIndex(
                            localX - translation,
                            measures.widths,
                            measures.gapPx,
                            measures.minTouchPx,
                        )
                    },
                    onSelect = { onSelectState.value(it) },
                    onDrag = { channel, velocity -> onDragState.value(channel, velocity) },
                )
            }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to DarkroomColors.Room,
                        1f to Color.Transparent,
                        startX = 0f,
                        endX = fadePx,
                    ),
                    size = Size(fadePx, size.height),
                )
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        1f to DarkroomColors.Room,
                        startX = size.width - fadePx,
                        endX = size.width,
                    ),
                    topLeft = Offset(size.width - fadePx, 0f),
                    size = Size(fadePx, size.height),
                )
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    measures.viewportWidth = size.width
                    translationX = ScopeDialMath.translationX(
                        size.width / 2f,
                        progress(),
                        measures.widths,
                        measures.gapPx,
                    )
                },
        ) {
            Layout(
                content = {
                    labels.forEachIndexed { index, label ->
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .semantics {
                                    role = Role.Tab
                                    selected = index == selectedIndex
                                    onClick {
                                        onSelect(index)
                                        true
                                    }
                                },
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Text(
                                label,
                                style = DarkroomText.ScopeDial,
                                color = DarkroomColors.Paper,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier
                                    .padding(top = Dimens.ScopeDialTextTop)
                                    .graphicsLayer { alpha = ScopeDialMath.alpha(abs(index - progress())) },
                            )
                        }
                    }
                },
            ) { measurables, constraints ->
                val widths = FloatArray(measurables.size) { i ->
                    measurables[i].maxIntrinsicWidth(constraints.maxHeight).toFloat()
                }
                measures.widths = widths
                measures.gapPx = gapPx
                val placeables = measurables.mapIndexed { i, measurable ->
                    val hit = ScopeDialMath.hitWidth(widths[i], gapPx, minTouchPx).roundToInt()
                    measurable.measure(Constraints.fixed(hit, constraints.maxHeight))
                }
                val contentWidth = if (widths.isEmpty()) {
                    0
                } else {
                    (ScopeDialMath.center(widths.lastIndex, widths, gapPx) + widths.last() / 2f).roundToInt()
                }
                layout(contentWidth, constraints.maxHeight) {
                    placeables.forEachIndexed { i, placeable ->
                        val center = ScopeDialMath.center(i, widths, gapPx)
                        placeable.place((center - placeable.width / 2f).roundToInt(), 0)
                    }
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = Dimens.ScopeDialIndicatorTop)
                .size(Dimens.NavIndicatorWidth, Dimens.NavIndicatorHeight)
                .background(DarkroomColors.Paper, RoundedCornerShape(50)),
        )
    }
}

/**
 * 点按选中标签，横滑把页进度增量写进通道。竖滑不翻页。
 * 一次横滑只开一个 [onDrag]，松手才关掉通道，让外面在同一轮滚动里吸页。
 * 手势被取消时取消那一轮，不吸页。
 */
private suspend fun PointerInputScope.detectScopeDial(
    scrollEnabled: () -> Boolean,
    stride: () -> Float,
    hitIndex: (localX: Float) -> Int,
    onSelect: (Int) -> Unit,
    onDrag: suspend (ReceiveChannel<Float>, () -> Float) -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    coroutineScope {
        val scope = this
        var active: Job? = null
        try {
            while (isActive) {
                var channel: Channel<Float>? = null
                var velocityPages = 0f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var totalX = 0f
                    var totalY = 0f
                    var vertical = false
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            if (channel != null) {
                                velocityPages = ScopeDialMath.dragPages(tracker.calculateVelocity().x, stride())
                                channel?.close()
                            }
                            break
                        }
                        val delta = change.positionChange()
                        totalX += delta.x
                        totalY += delta.y
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!vertical && channel == null && abs(totalY) > slop && abs(totalY) >= abs(totalX)) {
                            vertical = true
                        }
                        val horizontal = !vertical && abs(totalX) > slop && abs(totalX) > abs(totalY)
                        if (channel == null && horizontal && scrollEnabled()) {
                            val ch = Channel<Float>(Channel.UNLIMITED)
                            channel = ch
                            active?.cancel()
                            active = scope.launch {
                                onDrag(ch) { velocityPages }
                            }
                            val pages = ScopeDialMath.dragPages(totalX, stride())
                            if (pages != 0f) ch.trySend(pages)
                            change.consume()
                        } else if (channel != null) {
                            val pages = ScopeDialMath.dragPages(delta.x, stride())
                            if (pages != 0f) channel?.trySend(pages)
                            change.consume()
                        }
                        if (!change.pressed) {
                            if (channel != null) {
                                velocityPages = ScopeDialMath.dragPages(tracker.calculateVelocity().x, stride())
                                channel?.close()
                            } else if (!vertical && abs(totalX) <= slop && abs(totalY) <= slop) {
                                val index = hitIndex(change.position.x)
                                if (index >= 0) {
                                    change.consume()
                                    active?.cancel()
                                    // awaitEachGesture 自己循环，块后面的代码要等手势协程取消才跑到。
                                    onSelect(index)
                                }
                            }
                            break
                        }
                    }
                }
            }
        } catch (c: CancellationException) {
            active?.cancel()
            throw c
        }
    }
}

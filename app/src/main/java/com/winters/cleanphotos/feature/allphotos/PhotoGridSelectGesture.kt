package com.winters.cleanphotos.feature.allphotos

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.GridScrollClock
import com.winters.cleanphotos.core.design.SCROLL_SETTLE_TAP_GUARD_MS
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 照片网格的点按 / 长按 / 拖选，统一放在网格外层一个 pointerInput 里（缩略图自身不再挂点击，
 * 否则长按松手时缩略图的 clickable 会再触发一次点按）。
 *
 * - 长按超时前抬起、且没被列表滚动消费：点按 [onTap]。
 * - 长按到时（手指没移出触摸阈值）：[onLongPress]（返回 false 表示没开始多选，到此为止），之后不松手拖动时在 Initial 阶段消费移动（列表不再滚动），
 *   每次移动按手指下的格子回调 [onDrag]；手指进入上下 [Dimens.DragSelectEdge] 内时按距离自动滚动。
 */
@Composable
fun Modifier.photoGridGestures(
    state: LazyGridState,
    onTap: (Long) -> Unit,
    onLongPress: (Long) -> Boolean,
    onDrag: (anchorId: Long, currentId: Long) -> Unit,
    hitRects: PhotoHitRects? = null,
): Modifier {
    val tap = rememberUpdatedState(onTap)
    val long = rememberUpdatedState(onLongPress)
    val drag = rememberUpdatedState(onDrag)
    return pointerInput(state) {
        val edge = Dimens.DragSelectEdge.toPx()
        val maxStep = Dimens.DragSelectMaxStep.toPx()
        coroutineScope {
            val scope = this
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val hitId = photoAt(state, down.position, hitRects)
                val indexAtDown = state.firstVisibleItemIndex
                val offsetAtDown = state.firstVisibleItemScrollOffset
                // 长按超时前：抬起 = 点按；移出阈值或被滚动消费 = 放弃
                var tapped = false
                val beforeTimeout = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                        if (!change.pressed) {
                            tapped = !change.isConsumed
                            return@withTimeoutOrNull true
                        }
                        if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            GridScrollClock.mark()
                            return@withTimeoutOrNull false
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") false
                }
                if (beforeTimeout != null) {
                    // 按下到抬起之间列表动过、或仍在滑：当作甩动误触，不开清理页
                    val scrolled = state.isScrollInProgress ||
                        state.firstVisibleItemIndex != indexAtDown ||
                        state.firstVisibleItemScrollOffset != offsetAtDown
                    if (tapped && hitId != null && !scrolled && GridScrollClock.settled(SCROLL_SETTLE_TAP_GUARD_MS)) tap.value(hitId)
                    return@awaitEachGesture
                }
                val anchor = hitId ?: return@awaitEachGesture
                // 长按没生效（列表还没停稳、批量处理中）：不接管后续拖动，也不拿旧起点拖选
                if (!long.value(anchor)) return@awaitEachGesture
                var pointer = down.position
                var lastId = anchor
                fun reselect() {
                    val id = photoAt(state, pointer, hitRects) ?: return
                    if (id != lastId) {
                        lastId = id
                        drag.value(anchor, id)
                    }
                }
                var scroller: Job? = null
                try {
                    scroller = scope.launch {
                        while (isActive) {
                            withFrameNanos { }
                            val h = size.height.toFloat()
                            val step = when {
                                pointer.y < edge -> -maxStep * ((edge - pointer.y) / edge).coerceIn(0f, 1f)
                                pointer.y > h - edge -> maxStep * ((pointer.y - (h - edge)) / edge).coerceIn(0f, 1f)
                                else -> 0f
                            }
                            if (step != 0f) {
                                state.scrollBy(step)
                                reselect()
                            }
                        }
                    }
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        pointer = change.position
                        reselect()
                    }
                } finally {
                    scroller?.cancel()
                }
            }
        }
    }
}

/**
 * 手指是否落在这一格上。
 *
 * [cellTop] 用的是 [LazyGridItemInfo.offset]：原点在内容起点，不含 contentPadding。
 * 放置时才会再加 [androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo.beforeContentPadding]
 * （即 `-viewportStartOffset`）。手指坐标相对网格左上角，所以要比对前加上 [viewportStartOffset]。
 * 照片页顶栏改成叠在列表上之后，网格有一大块顶部内边距；漏加这段时，点上面的格子会命中下面那张。
 */
internal fun pointerHitsGridCell(
    position: Offset,
    cellLeft: Int,
    cellTop: Int,
    cellWidth: Int,
    cellHeight: Int,
    viewportStartOffset: Int,
): Boolean {
    val contentY = position.y + viewportStartOffset
    return position.x >= cellLeft && position.x < cellLeft + cellWidth &&
        contentY >= cellTop && contentY < cellTop + cellHeight
}

/**
 * 照片格在根坐标系里的实际边界。
 * 不放进 snapshot state：滚动和排序位移每帧改表，不能因此重组。
 * 表还是空的（第一帧还没摆好）时退回 [photoAt] 的 layoutInfo。
 * 表里已经有格子时，没命中就返回 null，不再用 layoutInfo：那份 offset 不含 animateItem 的位移。
 */
class PhotoHitRects {
    private val rects = HashMap<Long, Rect>()
    var originX: Float = 0f
    var originY: Float = 0f

    fun isNotEmpty(): Boolean = rects.isNotEmpty()

    /** 根坐标里的边界（印样表起飞位置）。 */
    operator fun get(id: Long): Rect? = rects[id]

    fun put(id: Long, rect: Rect) {
        rects[id] = rect
    }

    fun remove(id: Long) {
        rects.remove(id)
    }

    /** [local] 是网格 pointerInput 里的坐标，加上网格自身在根坐标系的原点。 */
    fun hit(local: Offset): Long? = hitPhotoId(local.x + originX, local.y + originY, rects)
}

/**
 * 根坐标落在哪一张上。重叠时取中心更近的那张（排序位移中两格会叠在一起）。
 * 没有包含这个点的矩形时返回 null。
 */
internal fun hitPhotoId(rootX: Float, rootY: Float, rects: Map<Long, Rect>): Long? {
    var bestId: Long? = null
    var bestDist = Float.POSITIVE_INFINITY
    for ((id, rect) in rects) {
        if (rootX < rect.left || rootX >= rect.right || rootY < rect.top || rootY >= rect.bottom) continue
        val dx = rootX - (rect.left + rect.right) * 0.5f
        val dy = rootY - (rect.top + rect.bottom) * 0.5f
        val dist = dx * dx + dy * dy
        if (dist < bestDist) {
            bestDist = dist
            bestId = id
        }
    }
    return bestId
}

/** 手指下的照片格（key 为 Long 的项）；天标题、年份分隔、空白处返回 null。 */
private fun photoAt(state: LazyGridState, position: Offset, hits: PhotoHitRects?): Long? {
    if (hits != null && hits.isNotEmpty()) return hits.hit(position)
    val layout = state.layoutInfo
    val info = layout.visibleItemsInfo.firstOrNull { item ->
        pointerHitsGridCell(
            position,
            item.offset.x,
            item.offset.y,
            item.size.width,
            item.size.height,
            layout.viewportStartOffset,
        )
    } ?: return null
    return info.key as? Long
}

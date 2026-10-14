package com.winters.cleanphotos.feature.allphotos

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import com.winters.cleanphotos.core.design.Dimens
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 照片网格的点按 / 长按 / 拖选，统一放在网格外层一个 pointerInput 里（缩略图自身不再挂点击，
 * 否则长按松手时缩略图的 clickable 会再触发一次点按）。
 *
 * - 长按超时前抬起、且没被列表滚动消费：点按 [onTap]。
 * - 长按到时（手指没移出触摸阈值）：[onLongPress]，之后不松手拖动时在 Initial 阶段消费移动（列表不再滚动），
 *   每次移动按手指下的格子回调 [onDrag]；手指进入上下 [Dimens.DragSelectEdge] 内时按距离自动滚动。
 */
@Composable
fun Modifier.photoGridGestures(
    state: LazyGridState,
    onTap: (Long) -> Unit,
    onLongPress: (Long) -> Unit,
    onDrag: (anchorId: Long, currentId: Long) -> Unit,
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
                val hitId = photoAt(state, down.position)
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
                            return@withTimeoutOrNull false
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") false
                }
                if (beforeTimeout != null) {
                    if (tapped && hitId != null) tap.value(hitId)
                    return@awaitEachGesture
                }
                val anchor = hitId ?: return@awaitEachGesture
                long.value(anchor)
                var pointer = down.position
                var lastId = anchor
                fun reselect() {
                    val id = photoAt(state, pointer) ?: return
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

/** 手指下的照片格（key 为 Long 的项）；天标题、年份分隔、空白处返回 null。 */
private fun photoAt(state: LazyGridState, position: Offset): Long? {
    val info = state.layoutInfo.visibleItemsInfo.firstOrNull { item ->
        position.x >= item.offset.x && position.x < item.offset.x + item.size.width &&
            position.y >= item.offset.y && position.y < item.offset.y + item.size.height
    } ?: return null
    return info.key as? Long
}

package com.winters.cleanphotos.ui.photopager

import androidx.compose.animation.core.Animatable
import com.winters.cleanphotos.core.design.MotionSprings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 统一手势检测器的缩放状态。
 *
 * 管理 pinch-to-zoom 和 double-tap-zoom 的缩放与平移状态。
 * 由外层（ReviewScreen / TrashPreviewOverlay）持有，通过参数传给 PhotoPager 渲染。
 *
 * 注意：pinch/pan 操作使用 MutableState 直接更新（非 suspend），
 * 确保可以在 AwaitPointerEventScope 中调用。
 * 动画操作（双击缩放、reset）使用 Animatable 的 animateTo。
 */
class PagerZoomState {
    // 使用 MutableState 支持即时更新（非 suspend），同时保持 Compose 响应性
    private val _zoomScale = androidx.compose.runtime.mutableFloatStateOf(1f)
    private val _zoomOffsetX = androidx.compose.runtime.mutableFloatStateOf(0f)
    private val _zoomOffsetY = androidx.compose.runtime.mutableFloatStateOf(0f)

    // 用于动画的 Animatable（双击缩放等需要平滑过渡的场景）
    internal val animScale = Animatable(1f)
    internal val animOffsetX = Animatable(0f)
    internal val animOffsetY = Animatable(0f)

    // 是否正在播放动画（动画期间使用 Animatable 的值）
    private var isAnimating = false

    /** 当前 zoom 缩放倍率 */
    val currentZoomScale: Float get() = if (isAnimating) animScale.value else _zoomScale.floatValue

    /** 当前 zoom X 平移 */
    val currentZoomOffsetX: Float get() = if (isAnimating) animOffsetX.value else _zoomOffsetX.floatValue

    /** 当前 zoom Y 平移 */
    val currentZoomOffsetY: Float get() = if (isAnimating) animOffsetY.value else _zoomOffsetY.floatValue

    /** 是否处于放大态 */
    val isZoomed: Boolean get() = currentZoomScale > 1.01f

    /** 重置缩放到初始状态 */
    suspend fun reset() {
        isAnimating = false
        _zoomScale.floatValue = 1f
        _zoomOffsetX.floatValue = 0f
        _zoomOffsetY.floatValue = 0f
        animScale.snapTo(1f)
        animOffsetX.snapTo(0f)
        animOffsetY.snapTo(0f)
    }

    /** 双击缩放：切换 1x ↔ doubleTapZoom（带动画） */
    suspend fun toggleDoubleTapZoom(tapOffset: Offset, containerSize: IntSize) {
        isAnimating = true
        if (_zoomScale.floatValue > 1f) {
            // 同步 Animatable 起始值
            animScale.snapTo(_zoomScale.floatValue)
            animOffsetX.snapTo(_zoomOffsetX.floatValue)
            animOffsetY.snapTo(_zoomOffsetY.floatValue)
            // 缩小回 1x
            coroutineScope {
                launch { animScale.animateTo(1f, MotionSprings.Settle.spec()) }
                launch { animOffsetX.animateTo(0f, MotionSprings.Settle.spec()) }
                launch { animOffsetY.animateTo(0f, MotionSprings.Settle.spec()) }
            }
            _zoomScale.floatValue = 1f
            _zoomOffsetX.floatValue = 0f
            _zoomOffsetY.floatValue = 0f
        } else {
            // 放大到 doubleTapZoom
            val targetScale = PhotoPagerDoubleTapZoom
            val maxOff = getMaxOffset(targetScale, containerSize)
            val centerX = containerSize.width / 2f
            val centerY = containerSize.height / 2f
            val targetX = (-(tapOffset.x - centerX) * (targetScale - 1f))
                .coerceIn(-maxOff.x, maxOff.x)
            val targetY = (-(tapOffset.y - centerY) * (targetScale - 1f))
                .coerceIn(-maxOff.y, maxOff.y)
            // 同步 Animatable 起始值
            animScale.snapTo(1f)
            animOffsetX.snapTo(0f)
            animOffsetY.snapTo(0f)
            coroutineScope {
                launch { animScale.animateTo(targetScale, MotionSprings.Settle.spec()) }
                launch { animOffsetX.animateTo(targetX, MotionSprings.Settle.spec()) }
                launch { animOffsetY.animateTo(targetY, MotionSprings.Settle.spec()) }
            }
            _zoomScale.floatValue = targetScale
            _zoomOffsetX.floatValue = targetX
            _zoomOffsetY.floatValue = targetY
        }
        isAnimating = false
    }

    /** pinch 变换：即时更新缩放和平移（非 suspend，可在 AwaitPointerEventScope 中调用） */
    fun onPinchTransform(pan: Offset, zoom: Float, containerSize: IntSize) {
        val newScale = clampPhotoPagerZoom(_zoomScale.floatValue * zoom)
        _zoomScale.floatValue = newScale
        if (newScale > 1f) {
            val maxOff = getMaxOffset(newScale, containerSize)
            _zoomOffsetX.floatValue = (_zoomOffsetX.floatValue + pan.x).coerceIn(-maxOff.x, maxOff.x)
            _zoomOffsetY.floatValue = (_zoomOffsetY.floatValue + pan.y).coerceIn(-maxOff.y, maxOff.y)
        } else {
            _zoomOffsetX.floatValue = 0f
            _zoomOffsetY.floatValue = 0f
        }
    }

    /** 放大态单指平移（非 suspend，可在 AwaitPointerEventScope 中调用） */
    fun onZoomedPan(pan: Offset, containerSize: IntSize) {
        val maxOff = getMaxOffset(_zoomScale.floatValue, containerSize)
        _zoomOffsetX.floatValue = (_zoomOffsetX.floatValue + pan.x).coerceIn(-maxOff.x, maxOff.x)
        _zoomOffsetY.floatValue = (_zoomOffsetY.floatValue + pan.y).coerceIn(-maxOff.y, maxOff.y)
    }

    private fun getMaxOffset(currentScale: Float, size: IntSize): Offset {
        if (currentScale <= 1f || size == IntSize.Zero) return Offset.Zero
        val maxX = (currentScale - 1f) * size.width / 2f
        val maxY = (currentScale - 1f) * size.height / 2f
        return Offset(maxX, maxY)
    }
}

@Composable
fun rememberPagerZoomState(): PagerZoomState {
    return remember { PagerZoomState() }
}

/**
 * 统一手势检测器。
 *
 * 在单一 pointerInput 中处理所有手势，避免父子 pointerInput 冲突：
 * - 未放大态单指拖动 → 横滑/垂直滑动（通过 onDrag/onDragEnd 回调）
 * - 双指 pinch → 缩放（通过 zoomState 更新）
 * - 放大态单指拖动 → 平移（通过 zoomState 更新）
 * - 双击 → 切换缩放（通过 onDoubleTap 回调）
 *
 * 手势判定规则：
 * 1. 第一个手指按下后，等待移动或第二个手指
 * 2. 如果第二个手指到来 → pinch 模式
 * 3. 如果只有一个手指且已放大 → 平移模式
 * 4. 如果只有一个手指且未放大 → drag 模式（横滑/垂直）
 * 5. drag 模式中如果第二个手指到来 → 立即切换到 pinch，取消 drag
 */
suspend fun PointerInputScope.detectPagerGestures(
    zoomState: PagerZoomState,
    zoomEnabled: Boolean,
    containerSize: () -> IntSize,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit = {},
    onDoubleTap: (Offset) -> Unit = {}
) {
    // 双击检测状态
    var lastTapTime = 0L
    var lastTapPosition = Offset.Zero

    awaitPointerEventScope {
        while (true) {
            // 等待第一个手指按下
            val downEvent = awaitPointerEvent(PointerEventPass.Main)
            val firstDown = downEvent.changes.firstOrNull { it.pressed } ?: continue
            firstDown.consume()

            val downTime = firstDown.uptimeMillis
            val downPosition = firstDown.position

            // 手势模式
            var mode = GestureMode.Undecided
            var totalDrag = Offset.Zero
            var previousPinchDistance = 0f
            val touchSlop = viewConfiguration.touchSlop
            var wasTap = true // 假设是 tap 直到证明不是

            // 追踪手势直到所有手指抬起
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val pressedChanges = event.changes.filter { it.pressed }
                val allUp = pressedChanges.isEmpty()

                if (allUp) {
                    // 所有手指抬起。抬起事件自带的最后一段位移也要计入，否则快甩会被判成点按（lessons 2026-10-11）
                    event.changes.firstOrNull { it.id == firstDown.id }?.let { up ->
                        val delta = up.position - up.previousPosition
                        if (mode == GestureMode.Drag && delta != Offset.Zero) {
                            onDrag(delta)
                        } else if (mode == GestureMode.Undecided && (totalDrag + delta).getDistance() > touchSlop) {
                            wasTap = false
                            if (!(zoomEnabled && zoomState.isZoomed)) {
                                mode = GestureMode.Drag
                                onDrag(totalDrag + delta)
                            }
                        }
                    }
                    event.changes.forEach { it.consume() }
                    break
                }

                val pointerCount = pressedChanges.size

                when (mode) {
                    GestureMode.Undecided -> {
                        if (pointerCount >= 2 && zoomEnabled) {
                            // 双指 → pinch 模式
                            mode = GestureMode.Pinch
                            wasTap = false
                            val p0 = pressedChanges[0].position
                            val p1 = pressedChanges[1].position
                            previousPinchDistance = (p1 - p0).getDistance()
                            event.changes.forEach { it.consume() }
                        } else if (pointerCount == 1) {
                            val change = pressedChanges.first()
                            val delta = change.position - change.previousPosition
                            totalDrag += delta

                            if (totalDrag.getDistance() > touchSlop) {
                                wasTap = false
                                if (zoomEnabled && zoomState.isZoomed) {
                                    // 放大态 → 平移模式
                                    mode = GestureMode.ZoomedPan
                                    zoomState.onZoomedPan(totalDrag, containerSize())
                                    change.consume()
                                } else {
                                    // 未放大态 → drag 模式
                                    mode = GestureMode.Drag
                                    onDrag(totalDrag)
                                    change.consume()
                                }
                            }
                        }
                    }

                    GestureMode.Drag -> {
                        if (pointerCount >= 2 && zoomEnabled) {
                            // drag 中第二个手指到来 → 切换到 pinch，取消 drag
                            mode = GestureMode.Pinch
                            onDragCancel()
                            val p0 = pressedChanges[0].position
                            val p1 = pressedChanges[1].position
                            previousPinchDistance = (p1 - p0).getDistance()
                            event.changes.forEach { it.consume() }
                        } else if (pointerCount == 1) {
                            val change = pressedChanges.first()
                            val delta = change.position - change.previousPosition
                            if (delta != Offset.Zero) {
                                onDrag(delta)
                            }
                            change.consume()
                        }
                    }

                    GestureMode.Pinch -> {
                        if (pointerCount >= 2) {
                            val p0 = pressedChanges[0].position
                            val p1 = pressedChanges[1].position
                            val currentDistance = (p1 - p0).getDistance()

                            val rawZoomDelta = if (previousPinchDistance > 1f) {
                                currentDistance / previousPinchDistance
                            } else 1f
                            val zoomDelta = adjustPinchZoomDelta(rawZoomDelta)
                            previousPinchDistance = currentDistance

                            val centroid = (p0 + p1) / 2f
                            val prevCentroid = (pressedChanges[0].previousPosition +
                                pressedChanges[1].previousPosition) / 2f
                            val panDelta = centroid - prevCentroid

                            zoomState.onPinchTransform(panDelta, zoomDelta, containerSize())
                            event.changes.forEach { it.consume() }
                        } else if (pointerCount == 1) {
                            // 手指减少到 1 个
                            if (zoomState.isZoomed) {
                                mode = GestureMode.ZoomedPan
                            }
                            event.changes.forEach { it.consume() }
                        }
                    }

                    GestureMode.ZoomedPan -> {
                        if (pointerCount >= 2 && zoomEnabled) {
                            // 平移中第二个手指到来 → 切换到 pinch
                            mode = GestureMode.Pinch
                            val p0 = pressedChanges[0].position
                            val p1 = pressedChanges[1].position
                            previousPinchDistance = (p1 - p0).getDistance()
                            event.changes.forEach { it.consume() }
                        } else if (pointerCount == 1) {
                            val change = pressedChanges.first()
                            val delta = change.position - change.previousPosition
                            if (delta != Offset.Zero) {
                                zoomState.onZoomedPan(delta, containerSize())
                            }
                            change.consume()
                        }
                    }
                }
            }

            // 手势结束处理
            when (mode) {
                GestureMode.Drag -> onDragEnd()
                GestureMode.Undecided -> {
                    // 没有超过 slop 就抬起 → tap
                    if (wasTap && zoomEnabled) {
                        val tapTime = System.currentTimeMillis()
                        val tapDistance = (downPosition - lastTapPosition).getDistance()
                        val timeSinceLastTap = tapTime - lastTapTime

                        if (timeSinceLastTap < DOUBLE_TAP_TIMEOUT_MS && tapDistance < touchSlop * 3) {
                            // 双击
                            onDoubleTap(downPosition)
                            lastTapTime = 0L
                            lastTapPosition = Offset.Zero
                        } else {
                            // 记录为第一次 tap，等待可能的第二次
                            lastTapTime = tapTime
                            lastTapPosition = downPosition
                        }
                    }
                }
                else -> {
                    // Pinch 或 ZoomedPan 结束，不需要额外处理
                }
            }
        }
    }
}

private const val DOUBLE_TAP_TIMEOUT_MS = 300L

private enum class GestureMode {
    Undecided,
    Drag,
    Pinch,
    ZoomedPan
}

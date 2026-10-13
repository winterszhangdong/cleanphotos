package com.winters.cleanphotos.feature.review

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity

/** 清理页手势回调。全部在主线程同步调用，只允许改 MutableState / 启动协程，不得挂起。 */
internal interface ReviewGestureHandler {
    /** 手指按下：打断正在进行的松手动画（翻页动画直接落定）。 */
    fun onDown()
    /** 放大状态下单指拖动改为平移；上下处理手势禁用。 */
    val isZoomed: Boolean
    fun onDragStart(axis: DragAxis)
    fun onDrag(axis: DragAxis, delta: Offset)
    fun onDragEnd(axis: DragAxis, velocity: Velocity)
    /** 拖动中途落下第二根手指：放弃这次拖动，回位，不做任何决定。 */
    fun onDragCancel()
    /** 抬起但没有拖动（点按）。 */
    fun onUpWithoutDrag()
    fun onDoubleTap(position: Offset)
    fun onPinch(centroid: Offset, zoomChange: Float, pan: Offset)
    fun onPinchEnd()
}

/**
 * 决定 5 / lessons 2026-05-09：上下滑、左右滑、双指缩放、双击全部在**同一个** pointerInput 里判定。
 * - 单指一越过 touch slop 就进入拖动，不等双击超时（单击本身没有动作，不需要延迟判定）；
 * - 双击 = 两次都没越过 slop 的快速按下，第二次抬起时立即触发；
 * - 方向越过 slop 后按 1.2 倍主轴判定，一旦确定本次只走该方向；
 * - 拖动起点扣掉 slop，避免越过 slop 那一帧跳一下。
 */
internal suspend fun PointerInputScope.detectReviewGestures(handler: ReviewGestureHandler) {
    var lastTapUpTime = 0L
    var lastTapPosition = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        handler.onDown()
        val slop = viewConfiguration.touchSlop
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var total = Offset.Zero
        var axis = DragAxis.None
        var pastSlop = false
        var pinching = false
        val isSecondTap = down.uptimeMillis - lastTapUpTime < viewConfiguration.doubleTapTimeoutMillis &&
            (down.position - lastTapPosition).getDistance() < slop * 4
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            if (pressed >= 2 || pinching) {
                if (!pinching) {
                    pinching = true
                    if (axis != DragAxis.None) handler.onDragCancel()
                    axis = DragAxis.None
                }
                if (pressed >= 2) {
                    handler.onPinch(event.calculateCentroid(useCurrent = true), event.calculateZoom(), event.calculatePan())
                    event.changes.forEach { it.consume() }
                    continue
                }
                if (pressed == 0) {
                    handler.onPinchEnd()
                    lastTapUpTime = 0L
                    break
                }
                continue
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
            if (!change.pressed) {
                // 抬起事件自带的最后一段位移也计入（见 ReviewGestureMath.upEventDrag）
                val upDelta = change.positionChange()
                total += upDelta
                ReviewGestureMath.upEventDrag(axis, total, upDelta, slop, handler.isZoomed)?.let { up ->
                    if (up.startAxis) {
                        pastSlop = true
                        axis = up.axis
                        handler.onDragStart(axis)
                    }
                    handler.onDrag(axis, up.delta)
                }
                if (axis != DragAxis.None) {
                    tracker.addPosition(change.uptimeMillis, change.position)
                    handler.onDragEnd(axis, tracker.calculateVelocity())
                    lastTapUpTime = 0L
                } else {
                    handler.onUpWithoutDrag()
                    if (!pastSlop && isSecondTap) {
                        handler.onDoubleTap(change.position)
                        lastTapUpTime = 0L
                    } else if (!pastSlop) {
                        lastTapUpTime = change.uptimeMillis
                        lastTapPosition = change.position
                    } else {
                        lastTapUpTime = 0L
                    }
                }
                break
            }
            tracker.addPosition(change.uptimeMillis, change.position)
            val delta = change.positionChange()
            total += delta
            if (axis == DragAxis.None) {
                if (!pastSlop && total.getDistance() > slop) pastSlop = true
                if (pastSlop) {
                    val decided = if (handler.isZoomed) DragAxis.Pan else ReviewGestureMath.axisOf(total.x, total.y)
                    if (decided != DragAxis.None) {
                        axis = decided
                        handler.onDragStart(axis)
                        handler.onDrag(axis, ReviewGestureMath.slopTrimmedStart(axis, total, slop))
                        change.consume()
                    }
                }
            } else {
                handler.onDrag(axis, delta)
                change.consume()
            }
        }
    }
}

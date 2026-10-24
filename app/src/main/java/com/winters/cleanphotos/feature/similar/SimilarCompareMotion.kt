package com.winters.cleanphotos.feature.similar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Velocity
import com.winters.cleanphotos.core.design.GestureTokens
import com.winters.cleanphotos.core.design.MotionDurations
import com.winters.cleanphotos.core.design.MotionSprings
import com.winters.cleanphotos.core.design.SpringToken
import com.winters.cleanphotos.feature.review.DragAxis
import com.winters.cleanphotos.feature.review.ReviewGestureHandler
import com.winters.cleanphotos.feature.review.ReviewGestureMath
import com.winters.cleanphotos.feature.review.ReviewThresholds
import com.winters.cleanphotos.feature.review.VerticalRelease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 对比页手势。照片不飞走：上下滑松手弹回，只改标记。
 * 放大时单指是平移。缩放倍数和归一化中心在切页时保留。
 */
internal class SimilarCompareMotion(
    private val scope: CoroutineScope,
) : ReviewGestureHandler {
    var dx by mutableFloatStateOf(0f)
    var dy by mutableFloatStateOf(0f)
    var zoom by mutableFloatStateOf(1f)
    var zoomOffset by mutableStateOf(Offset.Zero)
    var axis by mutableStateOf(DragAxis.None)

    var width = 0f
    var height = 0f
    var fit = Size.Zero
    var thresholds = ReviewThresholds(1f, 1f, 1f, 1f, 1f, 1f, 1f)
    var animationsEnabled = true
    var accepting = true
    var pageIndex = 0
    var pageCount = 0

    /** -1 下一张，1 上一张。松手决定翻页时立刻调用，不等弹簧结束。 */
    var onCommitPage: (Int) -> Unit = {}
    var onVertical: (up: Boolean) -> Unit = {}

    /** 手指还按在上下滑上。松手立刻变 false，提示不再跟回弹位移。 */
    var fingerVertical by mutableStateOf(false)

    /** 松手那一帧的提示。淡出期间只用这些，不读 dy。上滑留下，下滑移走。 */
    var holding by mutableStateOf(false)
    var heldUp by mutableStateOf(false)
    var heldPast by mutableStateOf(false)
    var heldProgress by mutableFloatStateOf(0f)
    var cueAlpha by mutableFloatStateOf(0f)

    private var rawDx = 0f
    private var rawZoom = 1f
    private var norm = Offset(0.5f, 0.5f)
    private var settleJob: Job? = null
    private var zoomJob: Job? = null
    private var cueJob: Job? = null

    override val isZoomed: Boolean get() = zoom > 1.01f

    override fun onDown() {
        settleJob?.cancel()
        zoomJob?.cancel()
        rawDx = dx
    }

    override fun onDragStart(axis: DragAxis) {
        this.axis = axis
        if (axis == DragAxis.Vertical) {
            cueJob?.cancel()
            cueJob = null
            holding = false
            fingerVertical = true
            // 翻页弹簧被这次按下取消后，剩下的横向位移收回来，停在已经切过去的那张上。
            if (dx != 0f) settleDxHome()
        } else {
            dismissCue()
        }
    }

    override fun onDrag(axis: DragAxis, delta: Offset) {
        when (axis) {
            DragAxis.Horizontal -> {
                rawDx += delta.x
                dx = if (thresholds.edgeLength <= 0f) {
                    rawDx
                } else {
                    ReviewGestureMath.horizontalOffset(rawDx, pageIndex > 0, pageIndex < pageCount - 1, thresholds)
                }
            }
            DragAxis.Vertical -> dy += delta.y
            DragAxis.Pan -> {
                zoomOffset = clampOffset(zoomOffset + delta, zoom)
                captureNorm()
            }
            DragAxis.None -> Unit
        }
    }

    override fun onDragEnd(axis: DragAxis, velocity: Velocity) {
        when (axis) {
            DragAxis.Horizontal -> releaseHorizontal(velocity.x)
            DragAxis.Vertical -> releaseVertical(velocity.y)
            DragAxis.Pan -> captureNorm()
            DragAxis.None -> Unit
        }
    }

    override fun onDragCancel() {
        holdCue()
        springBack(0f, 0f)
    }

    override fun onUpWithoutDrag() {
        if (dx != 0f || dy != 0f) springBack(0f, 0f)
    }

    override fun onSingleTap() = Unit

    override fun onDoubleTap(position: Offset) {
        val target = if (zoom > 1.01f) 1f else GestureTokens.DOUBLE_TAP_SCALE
        val center = Offset(width / 2f, height / 2f)
        val targetOffset = if (target == 1f) Offset.Zero else clampOffset((position - center) * (1f - target), target)
        animateZoom(target, targetOffset, MotionSprings.Settle)
    }

    override fun onPinch(centroid: Offset, zoomChange: Float, pan: Offset) {
        zoomJob?.cancel()
        val center = Offset(width / 2f, height / 2f)
        if (zoom >= GestureTokens.ZOOM_REST_MIN) rawZoom = zoom
        rawZoom = (rawZoom * zoomChange).coerceIn(PINCH_RAW_MIN, GestureTokens.ZOOM_MAX)
        val newZoom = GestureTokens.pinchResistance(rawZoom)
        val c = centroid - center
        val k = if (zoom == 0f) 1f else newZoom / zoom
        zoomOffset = clampOffset((zoomOffset - c) * k + c + pan, newZoom)
        zoom = newZoom
        captureNorm()
    }

    override fun onPinchEnd() {
        if (zoom < GestureTokens.ZOOM_REST_MIN) {
            animateZoom(1f, Offset.Zero, MotionSprings.Bouncy)
        } else {
            animateZoom(zoom, clampOffset(zoomOffset, zoom), MotionSprings.Settle)
        }
    }

    /** 胶片条点选：取消跟手动画，保留缩放，调用方再改下标。 */
    fun prepareJump() {
        settleJob?.cancel()
        zoomJob?.cancel()
        dismissCue()
        captureNorm()
        dx = 0f
        dy = 0f
        rawDx = 0f
        axis = DragAxis.None
    }

    fun offsetForNorm(): Offset = offsetFor(norm, zoom, fit)

    private fun releaseHorizontal(vx: Float) {
        val dir = ReviewGestureMath.horizontalRelease(dx, vx, thresholds)
        val can = when (dir) {
            -1 -> pageIndex < pageCount - 1
            1 -> pageIndex > 0
            else -> false
        }
        if (!can || width <= 0f) {
            springBack(vx, 0f)
            return
        }
        // 先切下标再把位移接上，画面不跳。等弹簧结束再切的话，中途按下会取消弹簧，
        // 接着上下滑把位移弹回 0，就回到翻页前的那张。
        captureNorm()
        val shift = if (dir < 0) width else -width
        pageIndex = (pageIndex - dir).coerceIn(0, pageCount - 1)
        onCommitPage(dir)
        if (!animationsEnabled) {
            dx = 0f
            rawDx = 0f
            axis = DragAxis.None
            return
        }
        dx += shift
        rawDx = dx
        settleJob?.cancel()
        val from = dx
        settleJob = scope.launch {
            animateAxis(from, 0f, vx, MotionSprings.Snappy) {
                dx = it
                rawDx = it
            }
            dx = 0f
            rawDx = 0f
            axis = DragAxis.None
        }
    }

    /** 上下滑开始时，把翻页没走完的横向位移收回 0。不取消这次竖滑。 */
    private fun settleDxHome() {
        settleJob?.cancel()
        val from = dx
        settleJob = scope.launch {
            animateAxis(from, 0f, 0f, MotionSprings.Settle) {
                dx = it
                rawDx = it
            }
        }
    }

    private fun releaseVertical(vy: Float) {
        holdCue()
        val release = ReviewGestureMath.verticalRelease(dy, vy, thresholds)
        if (accepting && release != VerticalRelease.None) onVertical(release == VerticalRelease.Up)
        springBack(0f, vy)
    }

    /**
     * 把提示停在松手这一帧，再淡出。必须在 [onVertical] 之前调用。
     * 回弹会穿过阈值，提示不能再读 dy。
     */
    private fun holdCue() {
        if (!fingerVertical) return
        heldUp = dy < 0f
        heldPast = abs(dy) >= thresholds.vertical
        heldProgress = ReviewGestureMath.cueProgress(dy, thresholds)
        fingerVertical = false
        cueJob?.cancel()
        if (!animationsEnabled) {
            holding = false
            cueAlpha = 0f
            cueJob = null
            return
        }
        holding = true
        cueAlpha = 1f
        cueJob = scope.launch {
            val a = Animatable(1f)
            a.animateTo(0f, tween(MotionDurations.RELEASE_CUE_FADE, easing = LinearEasing)) {
                cueAlpha = value
            }
            if (fingerVertical) return@launch
            cueAlpha = 0f
            holding = false
        }
    }

    private fun dismissCue() {
        cueJob?.cancel()
        cueJob = null
        fingerVertical = false
        holding = false
        cueAlpha = 0f
    }

    private fun springBack(vx: Float, vy: Float) {
        settleJob?.cancel()
        val fromX = dx
        val fromY = dy
        settleJob = scope.launch {
            if (!animationsEnabled) {
                dx = 0f
                dy = 0f
                rawDx = 0f
            } else {
                coroutineScope {
                    launch {
                        animateAxis(fromX, 0f, vx, MotionSprings.Settle) {
                            dx = it
                            rawDx = it
                        }
                    }
                    launch {
                        animateAxis(fromY, 0f, vy, MotionSprings.Settle) { dy = it }
                    }
                }
            }
            axis = DragAxis.None
        }
    }

    private fun animateZoom(targetZoom: Float, targetOffset: Offset, spring: SpringToken) {
        zoomJob?.cancel()
        val fromZoom = zoom
        val fromOffset = zoomOffset
        zoomJob = scope.launch {
            if (!animationsEnabled) {
                zoom = targetZoom
                zoomOffset = targetOffset
            } else {
                val a = Animatable(0f)
                a.animateTo(1f, spring.spec()) {
                    zoom = fromZoom + (targetZoom - fromZoom) * value
                    zoomOffset = fromOffset + (targetOffset - fromOffset) * value
                }
            }
            rawZoom = zoom
            captureNorm()
        }
    }

    private suspend fun animateAxis(
        from: Float,
        to: Float,
        velocity: Float,
        spring: SpringToken,
        apply: (Float) -> Unit,
    ) {
        if (!animationsEnabled) {
            apply(to)
            return
        }
        val a = Animatable(from)
        a.animateTo(to, spring.spec(), initialVelocity = velocity) { apply(value) }
    }

    private fun captureNorm() {
        if (zoom <= 1.01f || fit.width <= 0f || fit.height <= 0f) {
            norm = Offset(0.5f, 0.5f)
            return
        }
        norm = Offset(
            (0.5f - zoomOffset.x / (fit.width * zoom)).coerceIn(0f, 1f),
            (0.5f - zoomOffset.y / (fit.height * zoom)).coerceIn(0f, 1f),
        )
    }

    private fun offsetFor(norm: Offset, zoom: Float, fit: Size): Offset {
        if (zoom <= 1f || fit.width <= 0f || fit.height <= 0f) return Offset.Zero
        val raw = Offset((0.5f - norm.x) * fit.width * zoom, (0.5f - norm.y) * fit.height * zoom)
        return clampOffset(raw, zoom)
    }

    private fun clampOffset(offset: Offset, z: Float): Offset {
        if (z <= 1f) return Offset.Zero
        val maxX = maxOf(0f, (fit.width * z - width) / 2f)
        val maxY = maxOf(0f, (fit.height * z - height) / 2f)
        return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }

    private companion object {
        const val PINCH_RAW_MIN = 0.1f
    }
}

/** 对比页大图亮度。只有手指按着并且往下拖时收到 [GestureTokens.SIMILAR_DRAG_MIN_BRIGHTNESS]，饱和度另算、始终为 1。邻页仍走翻页亮度。 */
internal fun similarHeroBrightness(
    slot: Int,
    fingerVertical: Boolean,
    dy: Float,
    dx: Float,
    width: Float,
    thresholds: ReviewThresholds,
): Float {
    if (slot != 0) return ReviewGestureMath.enteringBrightness(dx, width)
    if (!fingerVertical || dy <= 0f) return 1f
    val floor = GestureTokens.SIMILAR_DRAG_MIN_BRIGHTNESS
    return 1f - (1f - floor) * ReviewGestureMath.effectFraction(dy, thresholds)
}

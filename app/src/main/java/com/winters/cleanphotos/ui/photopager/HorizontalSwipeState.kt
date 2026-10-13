package com.winters.cleanphotos.ui.photopager

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import com.winters.cleanphotos.core.design.GestureTokens
import com.winters.cleanphotos.core.design.MotionSprings
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 纯水平方向的滑动状态。
 *
 * 用于只需要左右翻页手势的场景（如垃圾桶预览）。
 * 清理页需要同时支持水平和垂直方向，应使用 ReviewSwipeState。
 *
 * 水平阈值：屏幕宽度 12%，最小 48dp，最大 96dp
 */
class HorizontalSwipeState(
    private val screenSize: IntSize,
    private val density: Density
) {
    // 水平切换阈值：屏幕宽度 12%，最小 48dp，最大 96dp
    val horizontalThreshold: Float
        get() {
            val raw = screenSize.width * 0.12f
            val minPx: Float = with(density) { Dp(48f).toPx() }
            val maxPx: Float = with(density) { Dp(96f).toPx() }
            return raw.coerceIn(minPx, maxPx)
        }

    private val offsetX = Animatable(0f)

    val currentOffsetX: Float get() = offsetX.value

    // 水平切换进度（-1~1，负数向左/下一张，正数向右/上一张）
    val horizontalProgress: Float
        get() = (currentOffsetX / horizontalThreshold).coerceIn(-1f, 1f)

    val isSwipeLeftTriggered: Boolean
        get() = currentOffsetX < -horizontalThreshold

    val isSwipeRightTriggered: Boolean
        get() = currentOffsetX > horizontalThreshold

    // 手指累计位移（未阻尼）。边界页上照片位移由它经阻尼函数换算，而不是逐帧乘系数
    private var rawX = 0f

    /**
     * 带边界阻尼的水平拖动：第一张向右拉、最后一张向左拉时，
     * 照片位移 = L·(1 − 1/(d·0.55/L + 1))，L = 屏宽 × 0.3。手指走 150dp 照片约走 58dp。
     */
    suspend fun onDrag(
        delta: Float,
        canSwipePrevious: Boolean,
        canSwipeNext: Boolean
    ) {
        // 回弹途中又按下：从当前可见位置接着拖，不跳
        if (rawX == 0f && offsetX.value != 0f) rawX = offsetX.value
        rawX += delta
        val atEdge = (rawX > 0f && !canSwipePrevious) || (rawX < 0f && !canSwipeNext)
        val width = screenSize.width.toFloat()
        val newOffset = if (atEdge) {
            GestureTokens.edgeResistance(rawX, width * GestureTokens.EDGE_RESISTANCE_LENGTH_FRACTION)
        } else {
            rawX.coerceIn(-width, width)
        }
        offsetX.snapTo(newOffset)
    }

    /** 未达到阈值：「落定」弹簧回到中心（约 440ms）。 */
    suspend fun springBack() {
        rawX = 0f
        offsetX.animateTo(0f, MotionSprings.Settle.spec())
    }

    /** 水平达到阈值：平滑滑出当前照片。使用 100ms + EaseOut 让用户能感知到过渡动画。 */
    suspend fun flyHorizontal(targetOffsetX: Float) {
        coroutineScope {
            launch {
                offsetX.animateTo(
                    targetOffsetX,
                    tween(100, easing = EaseOut)
                )
            }
        }
    }

    /** 重置到初始状态 */
    suspend fun reset() {
        rawX = 0f
        offsetX.snapTo(0f)
    }
}

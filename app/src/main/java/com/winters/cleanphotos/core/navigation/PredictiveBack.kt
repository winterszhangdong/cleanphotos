package com.winters.cleanphotos.core.navigation

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import kotlinx.coroutines.Job
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawWithContent
import com.winters.cleanphotos.core.design.ApiTier
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.LocalAnimationsEnabled
import com.winters.cleanphotos.core.design.MotionSprings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 预测性返回（API 34+，ApiTier.hasPredictiveBack；动画关闭时不启用，走普通返回）。
 * 拖动期间整页缩到 0.86、圆角 0→20dp、朝手势方向偏 ≤ 8dp，后面露出上一页（亮度 0.3→0.6）。
 * 缩放用真实布局尺寸而不是 graphicsLayer：松手提交时共享元素从当前缩小后的边界接着收回，不跳。
 */
@Stable
class PredictiveBackState {
    var progress by mutableFloatStateOf(0f)
        internal set
    var fromLeft by mutableStateOf(true)
        internal set
    /** 已提交返回：保持缩小态直到页面离场。 */
    var committed by mutableStateOf(false)
        internal set
}

@Composable
fun rememberPredictiveBack(enabled: Boolean, onBack: () -> Unit): PredictiveBackState {
    val state = remember { PredictiveBackState() }
    val scope = rememberCoroutineScope()
    val back by rememberUpdatedState(onBack)
    val active = enabled && predictiveBackAvailable()
    val springJob = remember { arrayOfNulls<Job>(1) }
    PredictiveBackHandler(enabled = active && !state.committed) { events ->
        // 上一次取消的回弹还在跑：不停掉的话它和这次手势每帧抢着写 progress
        springJob[0]?.cancel()
        try {
            events.collect { e: BackEventCompat ->
                state.fromLeft = e.swipeEdge == BackEventCompat.EDGE_LEFT
                state.progress = e.progress
            }
            state.committed = true
            BackdropSnapshot.returningFromGesture = true
            back()
        } catch (e: CancellationException) {
            springJob[0] = scope.springBack(state)
            throw e
        }
    }
    return state
}

/** 当前设备与设置下是否走预测性返回的跟手动画（API 34+ 且动画开启）。 */
@Composable
fun predictiveBackAvailable(): Boolean = ApiTier.Current.hasPredictiveBack && LocalAnimationsEnabled.current

private fun CoroutineScope.springBack(state: PredictiveBackState) = launch {
    animate(state.progress, 0f, animationSpec = MotionSprings.Settle.spec()) { v, _ -> state.progress = v }
}

/** 整页随返回手势缩小（实际布局尺寸），外层仍占满。 */
fun Modifier.predictiveShrink(state: PredictiveBackState): Modifier = this
    .layout { measurable, constraints ->
        val p = state.progress
        if (p <= 0f || !constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val s = 1f - (1f - BACK_MIN_SCALE) * p
        val w = (constraints.maxWidth * s).roundToInt()
        val h = (constraints.maxHeight * s).roundToInt()
        val placeable = measurable.measure(Constraints.fixed(w, h))
        val shift = BACK_MAX_SHIFT.toPx() * p * if (state.fromLeft) 1f else -1f
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(IntOffset(((constraints.maxWidth - w) / 2f + shift).roundToInt(), (constraints.maxHeight - h) / 2))
        }
    }

/** 缩小后的圆角裁切。进度只在 layer 阶段读：在组合里读会让整页随返回手势每帧重组。 */
fun Modifier.predictiveClip(state: PredictiveBackState): Modifier = graphicsLayer {
    val p = state.progress
    clip = p > 0f
    if (clip) shape = RoundedCornerShape(BACK_MAX_CORNER * p)
}

/** 在缩小的页面后面画上一页快照，亮度 0.3 → 0.6（在暗房底上用遮罩表达）。 */
fun Modifier.predictiveBackdrop(state: PredictiveBackState, snapshot: () -> ImageBitmap?): Modifier = drawBehind {
    val p = state.progress
    if (p <= 0f && !state.committed) return@drawBehind
    drawRect(DarkroomColors.Room)
    val img = snapshot() ?: return@drawBehind
    drawImage(img, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), dstOffset = IntOffset.Zero)
    val brightness = BACK_BG_FROM + (BACK_BG_TO - BACK_BG_FROM) * p
    drawRect(DarkroomColors.Room.copy(alpha = 1f - brightness), Offset.Zero, size)
}

/** 上一页快照：网格页一直把内容录进一个 GraphicsLayer，离开前转成位图交给下一页当返回背景。 */
object BackdropSnapshot {
    var image: ImageBitmap? = null
    /** 本次返回来自预测性返回手势：网格页从 0.6 亮度、原尺寸接上（不再从 0.25 / 0.96 开始）。 */
    var returningFromGesture = false
}

@Composable
fun rememberBackdropRecorder(): GraphicsLayer = rememberGraphicsLayer()

fun Modifier.recordBackdrop(layer: GraphicsLayer): Modifier = drawWithContent {
    layer.record { this@drawWithContent.drawContent() }
    drawLayer(layer)
}

fun CoroutineScope.captureBackdrop(layer: GraphicsLayer) = launch {
    BackdropSnapshot.image = layer.toImageBitmap()
}

private const val BACK_MIN_SCALE = 0.86f
private val BACK_MAX_SHIFT = 8.dp
private val BACK_MAX_CORNER = 20.dp
private const val BACK_BG_FROM = 0.3f
private const val BACK_BG_TO = 0.6f

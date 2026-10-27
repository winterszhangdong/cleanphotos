package com.winters.cleanphotos.feature.contactsheet

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.FilingMotion
import com.winters.cleanphotos.core.design.MotionDurations
import com.winters.cleanphotos.core.design.MotionEasings
import com.winters.cleanphotos.core.design.MotionSprings
import com.winters.cleanphotos.domain.model.PhotoAlbum
import com.winters.cleanphotos.domain.model.PhotoItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 最多飞几张：其余圈中的照片随格子一起淡出，叠的右上角写总张数。 */
private const val MAX_FLYERS = 3

/**
 * 印样表上的拖动、悬停、偷看、飞入状态。位置全部是根坐标；动画值只在绘制阶段读取。
 */
@Stable
internal class FilingVisuals {
    /** 手指拖着照片叠时，最近的那个袋子（路径或 [NEW_ENVELOPE_KEY]）。 */
    var hovered by mutableStateOf<String?>(null)
    /** 悬停计时圈 0..1，满了袋子自己打开（[hoverFan]）。 */
    val dwell = Animatable(0f)
    val hoverFan = Animatable(0f)
    /** 靠近就开始倾斜（不必等悬停计时）。 */
    val leanIn = Animatable(0f)
    var peek by mutableStateOf<PhotoAlbum?>(null)
    val peekFan = Animatable(0f)
    var drag by mutableStateOf<DragStack?>(null)
    var flight by mutableStateOf<FlightRun?>(null)
    var preview by mutableStateOf<PhotoItem?>(null)
    /** 袋子在根坐标里的边界（滚动时每帧更新，不进快照）。 */
    val envelopeRects = HashMap<String, Rect>()
    private var dwellJob: Job? = null

    fun stateFor(path: String): EnvelopeState = EnvelopeState(
        lean = { if (hovered == path) leanIn.value else 0f },
        lift = { if (peek?.relativePath == path) peekFan.value.coerceAtMost(1f) else 0f },
        flap = { flight?.takeIf { it.relativePath == path }?.flap?.value ?: 0f },
        bump = { flight?.takeIf { it.relativePath == path }?.bump?.value ?: 0f },
        dwell = { if (hovered == path) dwell.value else 0f },
        alpha = { dimFor(path)() },
    )

    /** 拖着照片叠或偷看时，其它袋子压到 42%。 */
    fun dimFor(key: String): () -> Float = {
        val focus = hovered ?: peek?.relativePath
        if (focus == null || focus == key) 1f else FilingMotion.DIM_OTHERS
    }

    /** 手指移动：换了目标就重新计时；停够 [FilingMotion.ENVELOPE_DWELL] 自己打开。 */
    suspend fun updateHover(pointer: Offset, scope: kotlinx.coroutines.CoroutineScope, slop: Float) {
        val target = envelopeRects.entries
            .filter { (_, r) -> r.inflate(slop).contains(pointer) }
            .minByOrNull { (_, r) -> (r.center - pointer).getDistanceSquared() }
            ?.key
        if (target == hovered) return
        hovered = target
        dwellJob?.cancel()
        dwell.snapTo(0f)
        hoverFan.snapTo(0f)
        leanIn.snapTo(0f)
        if (target == null) return
        dwellJob = scope.launch {
            launch { leanIn.animateTo(1f, MotionSprings.Gentle.spec()) }
            // 新袋子没有照片可露：只倾斜，不计时
            if (target == NEW_ENVELOPE_KEY) return@launch
            dwell.animateTo(1f, tween(FilingMotion.ENVELOPE_DWELL, easing = LinearEasing))
            hoverFan.animateTo(1f, MotionSprings.Settle.spec())
        }
    }

    suspend fun clearHover() {
        dwellJob?.cancel()
        hovered = null
        dwell.snapTo(0f)
        hoverFan.snapTo(0f)
        leanIn.snapTo(0f)
    }
}

/** 拖动中的照片叠：[pointer] 是手指根坐标。 */
internal class DragStack(val photos: List<PhotoItem>, pointer: Offset) {
    var pointer by mutableStateOf(pointer)
}

/**
 * 一次飞入。[starts] 是圈中照片在胶片上的位置（点袋子时从这里起飞、先收成一叠）；
 * [stackAt] 不为空时照片叠已经在手指下（拖进袋子），直接从那里入袋。
 * 合盖之前袋子上停在 [heldCount] / [heldCover]，合上那一刻 [released]，计数开始滚、露角换新。
 */
internal class FlightRun(
    val seq: Long,
    val photos: List<PhotoItem>,
    val total: Int,
    val relativePath: String,
    val starts: List<Rect?>,
    val stackAt: Offset?,
    val heldCount: Int,
    val heldCover: PhotoItem?,
) {
    val gather = Animatable(if (stackAt != null) 1f else 0f)
    val insert = Animatable(0f)
    val flap = Animatable(0f)
    val bump = Animatable(0f)
    var released by mutableStateOf(false)
    var finished by mutableStateOf(false)

    /** 收叠 → 后仰停住 → 入袋 → 合盖（下压回弹 + 触感）→ 计数 → 翻开。 */
    suspend fun play(onClosed: () -> Unit) {
        if (stackAt == null) gather.animateTo(1f, tween(FilingMotion.GATHER, easing = LinearEasing))
        delay(FilingMotion.STACK_HOLD.toLong())
        insert.animateTo(1f, tween(MotionDurations.MEDIUM, easing = MotionEasings.Accelerate))
        coroutineScope {
            launch {
                bump.animateTo(1f, tween(MotionDurations.SHORT, easing = MotionEasings.Standard))
                bump.animateTo(0f, MotionSprings.Bouncy.spec())
            }
            flap.animateTo(1f, tween(MotionDurations.SHORT, easing = MotionEasings.Standard))
            onClosed()
            released = true
        }
        delay(FilingMotion.FLAP_REOPEN_DELAY.toLong())
        flap.animateTo(0f, MotionSprings.Settle.spec())
        finished = true
    }
}

/**
 * 照片叠的落点：点袋子时在托盘上方正中；拖动时在手指上方（让手指露出来）。
 */
internal fun stackCenter(pointer: Offset?, trayTop: Float, screenWidth: Float, lift: Float): Offset =
    pointer?.let { Offset(it.x, it.y - lift) } ?: Offset(screenWidth / 2f, trayTop - lift * 2.4f)

/**
 * 飞行层：铺满屏幕，坐标与根坐标差 [origin]。入袋阶段按袋口那条线裁掉下面，照片看上去是塞进了袋子正面后面。
 */
@Composable
internal fun FlightLayer(
    run: FlightRun,
    origin: Offset,
    frameSize: Pair<Dp, Dp>,
    mouth: Rect,
    stackPoint: Offset,
) {
    val density = LocalDensity.current
    val (fw, fh) = frameSize
    val mouthTop = mouth.top + with(density) { EnvelopeGeometry.MouthY.toPx() } - origin.y
    val stagger = MotionDurations.STAGGER.toFloat() / FilingMotion.GATHER
    val settle = MotionSprings.Settle.asEasing()
    Box(
        Modifier
            .fillMaxSize()
            .drawWithContent {
                if (run.insert.value > 0f) clipRect(bottom = mouthTop) { this@drawWithContent.drawContent() } else drawContent()
            },
    ) {
        run.photos.take(MAX_FLYERS).forEachIndexed { i, photo ->
            val start = run.starts.getOrNull(i)
            Print(
                photo,
                size = fw,
                modifier = Modifier
                    .layout { measurable, _ ->
                        // 4:3 的白边小相片
                        val w = fw.roundToPx()
                        val h = fh.roundToPx()
                        val p = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
                        layout(w, h) { p.place(IntOffset.Zero) }
                    }
                    .graphicsLayer {
                        val wPx = fw.toPx()
                        val hPx = fh.toPx()
                        val raw = ((run.gather.value - i * stagger) / (1f - (MAX_FLYERS - 1) * stagger)).coerceIn(0f, 1f)
                        val g = if (run.stackAt != null) 1f else settle.transform(raw)
                        val ins = run.insert.value
                        val from = (start?.center ?: stackPoint + origin) - origin
                        val stack = stackPoint
                        val mouthC = Offset(mouth.center.x - origin.x, mouthTop + hPx * FilingMotion.MOUTH_SCALE * 0.6f)
                        val c = lerp(lerp(from, stack, g), mouthC, ins)
                        val startScale = (start?.width ?: wPx) / wPx
                        val scale = lerpF(lerpF(startScale, FilingMotion.STACK_SCALE, g), FilingMotion.MOUTH_SCALE, ins)
                        translationX = c.x - wPx / 2f
                        translationY = c.y - hPx / 2f
                        scaleX = scale
                        scaleY = scale
                        cameraDistance = FilingMotion.CAMERA_DISTANCE * density.density
                        val r = FilingMotion.STACK_ROTATIONS[i % FilingMotion.STACK_ROTATIONS.size]
                        rotationZ = lerpF(r * g, 0f, ins)
                        rotationX = lerpF(FilingMotion.STACK_TILT * g, 0f, ins)
                        alpha = if (run.finished) 0f else 1f
                    },
            )
        }
        if (run.total > 1) CountBadge(run.total, Modifier.graphicsLayer {
            val ins = run.insert.value
            val g = run.gather.value
            alpha = if (g >= 1f && ins < 0.3f) 1f - ins / 0.3f else 0f
            translationX = stackPoint.x + fw.toPx() * FilingMotion.STACK_SCALE / 2f - 10.dp.toPx()
            translationY = stackPoint.y - fh.toPx() * FilingMotion.STACK_SCALE / 2f - 10.dp.toPx()
        })
    }
}

/** 拖动中的照片叠：跟着手指（在手指上方），右上角是张数。 */
@Composable
internal fun DragStackLayer(drag: DragStack, origin: Offset, frameSize: Pair<Dp, Dp>, lift: Float) {
    val (fw, fh) = frameSize
    Box(Modifier.fillMaxSize()) {
        drag.photos.take(MAX_FLYERS).forEachIndexed { i, photo ->
            Print(
                photo,
                size = fw,
                modifier = Modifier
                    .layout { measurable, _ ->
                        val w = fw.roundToPx()
                        val h = fh.roundToPx()
                        val p = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
                        layout(w, h) { p.place(IntOffset.Zero) }
                    }
                    .graphicsLayer {
                        val c = stackCenter(drag.pointer, 0f, 0f, lift) - origin
                        translationX = c.x - fw.toPx() / 2f
                        translationY = c.y - fh.toPx() / 2f
                        scaleX = FilingMotion.STACK_SCALE
                        scaleY = FilingMotion.STACK_SCALE
                        cameraDistance = FilingMotion.CAMERA_DISTANCE * density
                        rotationZ = FilingMotion.STACK_ROTATIONS[i % FilingMotion.STACK_ROTATIONS.size]
                        rotationX = FilingMotion.STACK_TILT
                    },
            )
        }
        CountBadge(drag.photos.size, Modifier.graphicsLayer {
            val c = stackCenter(drag.pointer, 0f, 0f, lift) - origin
            translationX = c.x + fw.toPx() * FilingMotion.STACK_SCALE / 2f - 10.dp.toPx()
            translationY = c.y - fh.toPx() * FilingMotion.STACK_SCALE / 2f - 10.dp.toPx()
        })
    }
}

@Composable
private fun CountBadge(count: Int, modifier: Modifier) {
    Box(
        modifier
            .size(height = 20.dp, width = if (count >= 10) 28.dp else 20.dp)
            .clip(CircleShape)
            .background(DarkroomColors.Paper),
        contentAlignment = Alignment.Center,
    ) {
        Text("$count", style = DarkroomText.EnvelopeCount.copy(color = DarkroomColors.Room))
    }
}

/** 「松手放进「猫」」：玻璃小胶囊，跟在照片叠上方。 */
@Composable
internal fun DropChip(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .clip(shape)
            .background(DarkroomColors.ToastFill)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clipToBounds(),
    ) {
        Text(text, style = DarkroomText.DropChip, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun lerp(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

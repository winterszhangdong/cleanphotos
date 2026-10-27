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

/** 「还有 N 个」卡片在悬停 / 位置表里用的键。 */
internal const val MORE_KEY = "+more"

/** 面板里「+ 新建「xxx」」那一行的键。 */
internal const val NEW_ROW_KEY = "+create"

/**
 * 印样表上的拖动、悬停、偷看、飞入状态。位置全部是根坐标；动画值只在绘制阶段读取。
 */
@Stable
internal class FilingVisuals {
    /** 手指拖着照片叠时，托盘里手指下的目标（路径、[NEW_ENVELOPE_KEY] 或 [MORE_KEY]）。 */
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
    /** 袋子在根坐标里的边界（滚动时每帧更新，不进快照；滚出屏幕的移除）。 */
    val envelopeRects = HashMap<String, Rect>()
    /** 托盘袋子那一行（LazyRow）的根坐标边界：只有这里面露出来的袋子能悬停。 */
    var trayRow: Rect? = null

    /** 名签：[tagKey] 正在弹出 / 停着（[tagIn] 0..1），[tagOutKey] 正在缩回（[tagOut] 1..0）。 */
    var tagKey by mutableStateOf<String?>(null)
        private set
    val tagIn = Animatable(0f)
    var tagOutKey by mutableStateOf<String?>(null)
        private set
    val tagOut = Animatable(0f)

    /** 托盘里刚点 / 刚松手的袋子，点下那一刻的张数和露角（等授权、等飞入；飞入接手或被拒绝后清掉）。 */
    var trayPicked by mutableStateOf<HeldEnvelope?>(null)

    // —— 相册面板 ——
    /** 面板里刚点的那一行（等授权时整行高亮、相片叠打开；被拒绝后清掉）。 */
    var sheetPicked by mutableStateOf<String?>(null)
    /** 行里相片卡座的根坐标边界（飞入的落点在最上面那张相片）。 */
    val rowHolderRects = HashMap<String, Rect>()
    /** 停靠小叠的根坐标中心。 */
    var parkAt: Offset? = null

    /** 贴边自动滚动中：不判定悬停（不倾斜、不计时、不触感）。 */
    var autoScrolling = false
    /** 进入一个新目标（触感）。 */
    var onEnter: (String) -> Unit = {}
    private var dwellJob: Job? = null
    private var tagJob: Job? = null

    /**
     * 格子里的原图显示多少：被拿起的空着（落回时淡回来）；从手指下的叠入袋时也空着，
     * 直到照片离开列表（点袋子起飞的不管，其余圈中的照片随格子一起淡出）。
     */
    fun slotAlpha(id: Long): Float {
        drag?.let { if (id in it.ids) return it.slotAlpha(id) }
        flight?.let { if (it.stackAt != null && id in it.ids) return 0f }
        return 1f
    }

    fun stateFor(path: String): EnvelopeState = EnvelopeState(
        lean = { if (hovered == path) leanIn.value else 0f },
        lift = { if (peek?.relativePath == path) peekFan.value.coerceAtMost(1f) else 0f },
        flap = { flight?.takeIf { it.relativePath == path && !it.intoSheet }?.flap?.value ?: 0f },
        bump = { flight?.takeIf { it.relativePath == path && !it.intoSheet }?.bump?.value ?: 0f },
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
        if (autoScrolling) return
        val row = trayRow?.inflate(slop)
        val target = if (row == null || !row.contains(pointer)) null else envelopeRects.entries
            .filter { (_, r) -> r.inflate(slop).contains(pointer) && r.overlaps(row) }
            .minByOrNull { (_, r) -> (r.center - pointer).getDistanceSquared() }
            ?.key
        if (target == hovered) return
        hovered = target
        showTag(target, scope)
        if (target != null) onEnter(target)
        dwellJob?.cancel()
        dwell.snapTo(0f)
        hoverFan.snapTo(0f)
        leanIn.snapTo(0f)
        if (target == null) return
        dwellJob = scope.launch {
            launch { leanIn.animateTo(1f, MotionSprings.Gentle.spec()) }
            // 新袋子没有照片可露、「还有 N 个」只在松手时打开（用户 2026-10-27 决定）：只倾斜，不计时
            if (target == NEW_ENVELOPE_KEY || target == MORE_KEY) return@launch
            dwell.animateTo(1f, tween(FilingMotion.ENVELOPE_DWELL, easing = LinearEasing))
            hoverFan.animateTo(1f, MotionSprings.Settle.spec())
        }
    }

    /** 名签：旧签缩回（SHORT 加速离场）、新签弹出（Snappy）同时进行。 */
    private fun showTag(key: String?, scope: kotlinx.coroutines.CoroutineScope) {
        if (key == tagKey) return
        val old = tagKey
        val from = tagIn.value
        tagKey = key
        if (old != null) {
            tagJob?.cancel()
            tagOutKey = old
            tagJob = scope.launch {
                tagOut.snapTo(from)
                tagOut.animateTo(0f, tween(MotionDurations.SHORT, easing = MotionEasings.Accelerate))
                tagOutKey = null
            }
        }
        scope.launch {
            tagIn.snapTo(0f)
            if (key != null) tagIn.animateTo(1f, MotionSprings.Snappy.spec())
        }
    }

    suspend fun clearHover(scope: kotlinx.coroutines.CoroutineScope? = null) {
        dwellJob?.cancel()
        hovered = null
        if (scope != null) showTag(null, scope) else {
            tagKey = null
            tagOutKey = null
            tagIn.snapTo(0f)
        }
        dwell.snapTo(0f)
        hoverFan.snapTo(0f)
        leanIn.snapTo(0f)
    }
}

/**
 * 拖动中的照片叠：[pointer] 是手指根坐标，[starts] 是拿起那一刻圈中照片在胶片上的根坐标边界（屏幕外的没有）。
 * [lift] 0 = 还在格子里，1 = 收成手指下的一叠：拿起 0→1，松手落回 1→0（[returning]）。
 * 拿起后原来的格子空着（只留一圈细框），落回最后一段原图交叉淡入。
 */
internal class DragStack(val photos: List<PhotoItem>, pointer: Offset, val starts: Map<Long, Rect> = emptyMap()) {
    var pointer by mutableStateOf(pointer)
    val lift = Animatable(0f)
    var returning by mutableStateOf(false)
        private set
    /** 落回的格子：松手那一刻重新量（列表没动时和 [starts] 一样）。 */
    var homes: Map<Long, Rect> = starts
        private set
    private val index: Map<Long, Int> = photos.withIndex().associate { (i, p) -> p.id to i }
    val ids: Set<Long> get() = index.keys

    /** 第 [i] 张这一刻走到哪（0 = 格子，1 = 叠里）。 */
    fun progress(i: Int): Float = liftProgress(lift.value, i, if (returning) FilingMotion.PUT_BACK else FilingMotion.PICK_UP)

    suspend fun pickUp() {
        lift.animateTo(1f, tween(FilingMotion.PICK_UP, easing = MotionEasings.Decelerate))
    }

    suspend fun putBack(homes: Map<Long, Rect>) {
        this.homes = homes
        returning = true
        lift.animateTo(0f, tween(FilingMotion.PUT_BACK, easing = MotionEasings.Standard))
    }

    fun slotAlpha(id: Long): Float = slotAlphaFor(returning, progress(index[id] ?: 0))
}

/**
 * 圈中格子里的原图显示多少（[t] 是这一张的 [liftProgress]）：拿起后 0（空格）；
 * 落回时这一张走到最后 [FilingMotion.LAND_HANDOFF] 这段从 0 淡到 1，和飞回的小相片交叉淡入。
 */
internal fun slotAlphaFor(returning: Boolean, t: Float): Float =
    if (!returning) 0f else (1f - t / FilingMotion.LAND_HANDOFF).coerceIn(0f, 1f)

/**
 * 第 [index] 张在拿起 / 落回里走到哪（0 = 格子，1 = 叠里）。前几张按 [FilingMotion.LIFT_STAGGER] 错开：
 * 拿起时后面的晚一点起飞，落回时同样的错开让叠先散开、各自落位，两头都正好在 0 和 1 收住。
 */
internal fun liftProgress(lift: Float, index: Int, duration: Int): Float {
    val step = FilingMotion.LIFT_STAGGER.toFloat() / duration
    val span = 1f - FilingMotion.LIFT_STAGGER_STEPS * step
    val k = index.coerceAtMost(FilingMotion.LIFT_STAGGER_STEPS)
    return ((lift - k * step) / span).coerceIn(0f, 1f)
}

/**
 * 一次飞入。[starts] 是圈中照片在胶片上的位置（点袋子时从这里起飞、先收成一叠）；
 * [stackAt] 不为空时照片叠已经在手指下（拖进袋子），直接从那里入袋。
 * 合盖之前袋子上停在 [heldCount] / [heldCover]（面板的行停在 [heldPrints]），合上那一刻 [released]，计数开始滚、露角 / 封面换新。
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
    /** 飞进相册面板里那一行的相片叠（而不是托盘里的袋子）。 */
    val intoSheet: Boolean = false,
    /** 面板行飞入前的相片（[SheetPrints.of]）。 */
    val heldPrints: List<PhotoItem> = emptyList(),
) {
    val ids: Set<Long> = photos.mapTo(HashSet()) { it.id }
    val gather = Animatable(if (stackAt != null) 1f else 0f)
    val insert = Animatable(0f)
    val flap = Animatable(0f)
    val bump = Animatable(0f)
    /** 落进面板行之后飞行的相片淡出（和新封面交叉）。 */
    val fade = Animatable(1f)
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
        coroutineScope {
            if (intoSheet) launch { fade.animateTo(0f, tween(FilingMotion.SHEET_LAND_FADE, easing = LinearEasing)) }
            delay(FilingMotion.FLAP_REOPEN_DELAY.toLong())
            flap.animateTo(0f, MotionSprings.Settle.spec())
        }
        finished = true
    }
}

/** 照片叠的落点：拖动时浮在指尖，中心在手指正上方 [lift]（横向跟手指对齐，不夹边）。 */
internal fun stackCenter(pointer: Offset, lift: Float): Offset = Offset(pointer.x, pointer.y - lift)

/** 点袋子起飞时照片叠停在托盘上方正中。 */
internal fun restingStackCenter(trayTop: Float, screenWidth: Float, lift: Float): Offset =
    Offset(screenWidth / 2f, trayTop - lift * 2.4f)

/** 拖动时照片叠在手指正上方多少（px）。 */
internal class StackPlacement(val lift: Float) {
    fun center(pointer: Offset): Offset = stackCenter(pointer, lift)
    /** 停靠小叠散开时，让叠从 [stack] 这一点出发：反推手指的位置。 */
    fun pointerFor(stack: Offset): Offset = Offset(stack.x, stack.y + lift)
}

/**
 * 飞行层：铺满屏幕，坐标与根坐标差 [origin]。入袋阶段按袋口那条线裁掉下面，照片看上去是塞进了袋子正面后面。
 * [land] 不为空时是飞进面板的一行：不裁，缩到 [landScale] 落在最上面那张相片的位置（层坐标），
 * 相片收拢时跟着下沉 [landSink]，落定后淡出、新封面露出来。
 */
@Composable
internal fun FlightLayer(
    run: FlightRun,
    origin: Offset,
    frameSize: Pair<Dp, Dp>,
    mouth: Rect,
    stackPoint: Offset,
    mouthY: Dp = EnvelopeGeometry.MouthY,
    mouthScale: Float = FilingMotion.MOUTH_SCALE,
    land: Offset? = null,
    landScale: Float = 1f,
    landSink: Float = 0f,
) {
    val density = LocalDensity.current
    val (fw, fh) = frameSize
    val mouthTop = mouth.top + with(density) { mouthY.toPx() } - origin.y
    val stagger = MotionDurations.STAGGER.toFloat() / FilingMotion.GATHER
    val settle = MotionSprings.Settle.asEasing()
    Box(
        Modifier
            .fillMaxSize()
            .drawWithContent {
                if (land == null && run.insert.value > 0f) clipRect(bottom = mouthTop) { this@drawWithContent.drawContent() } else drawContent()
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
                        val mouthC = land?.let { Offset(it.x, it.y + landSink * run.flap.value) }
                            ?: Offset(mouth.center.x - origin.x, mouthTop + hPx * mouthScale * 0.6f)
                        val c = lerp(lerp(from, stack, g), mouthC, ins)
                        val startScale = (start?.width ?: wPx) / wPx
                        val scale = lerpF(lerpF(startScale, FilingMotion.STACK_SCALE, g), if (land != null) landScale else mouthScale, ins)
                        translationX = c.x - wPx / 2f
                        translationY = c.y - hPx / 2f
                        scaleX = scale
                        scaleY = scale
                        cameraDistance = FilingMotion.CAMERA_DISTANCE * density.density
                        val r = FilingMotion.STACK_ROTATIONS[i % FilingMotion.STACK_ROTATIONS.size]
                        rotationZ = lerpF(r * g, 0f, ins)
                        rotationX = lerpF(FilingMotion.STACK_TILT * g, 0f, ins)
                        alpha = if (land != null) run.fade.value else if (run.finished) 0f else 1f
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

/**
 * 拖动中的照片叠：跟着手指（在手指上方），右上角是张数。
 * 拿起时屏幕上能看到的圈中照片都从自己的格子浮起飞过来：前 3 张叠成扇形，其余的飞到叠下面淡掉；落回时反过来。
 */
@Composable
internal fun DragStackLayer(drag: DragStack, origin: Offset, frameSize: Pair<Dp, Dp>, placement: StackPlacement, hidden: () -> Boolean = { false }) {
    val (fw, fh) = frameSize
    // 先画叠下面的（第 4 张起，只有在屏幕上的才飞），叠顶的 3 张最后画
    val flyers = drag.photos.withIndex().filter { (i, p) -> i < MAX_FLYERS || p.id in drag.starts }.sortedByDescending { it.index }
    Box(Modifier.fillMaxSize()) {
        flyers.forEach { (i, photo) ->
            val inStack = i < MAX_FLYERS
            Print(
                photo,
                size = fw,
                lift = { kotlin.math.sin(Math.PI * drag.progress(i)).toFloat() },
                modifier = Modifier
                    .layout { measurable, _ ->
                        val w = fw.roundToPx()
                        val h = fh.roundToPx()
                        val p = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
                        layout(w, h) { p.place(IntOffset.Zero) }
                    }
                    .graphicsLayer {
                        val wPx = fw.toPx()
                        val hPx = fh.toPx()
                        val t = drag.progress(i)
                        val stack = placement.center(drag.pointer) - origin
                        val slot = (if (drag.returning) drag.homes[photo.id] else drag.starts[photo.id])
                        val from = slot?.center?.minus(origin) ?: stack
                        val c = lerp(from, stack, t)
                        val startScale = (slot?.width ?: wPx) / wPx
                        val pop = 1f + FilingMotion.LIFT_POP * kotlin.math.sin(Math.PI * t).toFloat()
                        val endScale = if (inStack) FilingMotion.STACK_SCALE else FilingMotion.STACK_SCALE * 0.9f
                        val scale = lerpF(startScale, endScale, t) * pop
                        translationX = c.x - wPx / 2f
                        translationY = c.y - hPx / 2f
                        scaleX = scale
                        scaleY = scale
                        cameraDistance = FilingMotion.CAMERA_DISTANCE * density
                        rotationZ = if (inStack) FilingMotion.STACK_ROTATIONS[i % FilingMotion.STACK_ROTATIONS.size] * t else 0f
                        rotationX = if (inStack) FilingMotion.STACK_TILT * t else 0f
                        alpha = if (hidden()) 0f else when {
                            // 叠下面的飞到叠里淡掉
                            !inStack -> 1f - t
                            // 不在屏幕上的直接在叠里出现 / 消失
                            slot == null -> t
                            // 落回最后一段让位给格子里的原图
                            drag.returning -> (t / FilingMotion.LAND_HANDOFF).coerceIn(0f, 1f)
                            else -> 1f
                        }
                    },
            )
        }
        CountBadge(drag.photos.size, Modifier.graphicsLayer {
            val c = placement.center(drag.pointer) - origin
            alpha = if (hidden()) 0f else drag.lift.value
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

/** 托盘上方的玻璃小胶囊（「N 张没能放进去」等提示）。 */
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

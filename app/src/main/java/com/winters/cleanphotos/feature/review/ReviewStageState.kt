package com.winters.cleanphotos.feature.review

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import com.winters.cleanphotos.core.design.DevelopProfiles
import com.winters.cleanphotos.core.design.GestureTokens
import com.winters.cleanphotos.core.design.MotionDurations
import com.winters.cleanphotos.core.design.MotionEasings
import com.winters.cleanphotos.core.design.MotionSprings
import com.winters.cleanphotos.domain.model.ReviewPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 离场去向：上滑（收藏 / 取消收藏）或下滑（移到垃圾桶）。 */
internal enum class ExitKind { Up, Down }

/**
 * 一次离场（按用户决定主体位移 250ms 内完成）。
 * 起点是松手时的实际位移、缩放与色调；终点是底部导航里「收藏」/「垃圾桶」的位置（相对屏幕中心）。
 * 进度 0..1 线性推进，形状由 [ExitPath] 计算；不持有任何输入锁。
 */
internal class ExitFlight(
    val id: Long,
    val page: ReviewPage,
    val kind: ExitKind,
    val startY: Float,
    val startScale: Float,
    val startSaturation: Float,
    val startBrightness: Float,
    val velocityY: Float,
    val target: Offset,
    val rise: Float,
) {
    val progress = Animatable(0f)
    /** 主体已经到位（进度 1）：舞台不再画主体层，只剩余影收尾。 */
    var mainDone by mutableStateOf(false)
}

/** 撤回飞回：照片从去处减速飞回中央 450ms，飞到一半开始重新显影；原当前页退后压暗让位。 */
internal class UndoFlight(val key: String, val kind: ExitKind, val from: Offset, val recedeKey: String?) {
    val progress = Animatable(0f)
}

/** 离场 / 撤回的几何与色调（纯函数，便于单测与余影取延迟样本）。 */
internal object ExitPath {
    /** 收藏离场里「顺惯性上冲」所占的时间比例。 */
    const val RISE_FRACTION = 0.28f

    /** 余影最晚一层的延迟（100ms / 250ms）：主体结束后时间轴再走这么多，余影才走完路径。 */
    const val TRAIL_TAIL = 100f / 250f

    data class Pose(val x: Float, val y: Float, val scale: Float, val saturation: Float, val brightness: Float, val alpha: Float)

    fun pose(f: ExitFlight, t: Float): Pose {
        val p = t.coerceIn(0f, 1f)
        val acc = MotionEasings.Accelerate
        return when (f.kind) {
            ExitKind.Up -> {
                val y: Float
                val x: Float
                if (p < RISE_FRACTION) {
                    val q = MotionEasings.Decelerate.transform(p / RISE_FRACTION)
                    y = f.startY - f.rise * q
                    x = 0f
                } else {
                    val q = acc.transform((p - RISE_FRACTION) / (1f - RISE_FRACTION))
                    y = (f.startY - f.rise) + (f.target.y - (f.startY - f.rise)) * q
                    x = f.target.x * q
                }
                val s = f.startScale + (EXIT_END_SCALE - f.startScale) * acc.transform(p)
                Pose(x, y, s, f.startSaturation, f.startBrightness, fadeOut(p))
            }
            ExitKind.Down -> {
                val q = acc.transform(p)
                Pose(
                    x = f.target.x * q,
                    y = f.startY + (f.target.y - f.startY) * q,
                    scale = f.startScale + (EXIT_END_SCALE - f.startScale) * q,
                    saturation = f.startSaturation * (1f - q),
                    brightness = f.startBrightness + (TRASH_END_BRIGHTNESS - f.startBrightness) * q,
                    alpha = fadeOut(p),
                )
            }
        }
    }

    /** 撤回：从去处（scale 0.05）减速飞回中央；后半段重新显影。 */
    fun undoPose(u: UndoFlight, t: Float): Pose {
        val q = MotionEasings.Decelerate.transform(t.coerceIn(0f, 1f))
        val develop = ((t - 0.5f) / 0.5f).coerceIn(0f, 1f)
        val (s0, b0) = when (u.kind) {
            ExitKind.Down -> 0f to TRASH_END_BRIGHTNESS
            ExitKind.Up -> 1f to DevelopProfiles.ReviewNext.brightness.start
        }
        return Pose(
            x = u.from.x * (1f - q),
            y = u.from.y * (1f - q),
            scale = EXIT_END_SCALE + (1f - EXIT_END_SCALE) * q,
            saturation = s0 + (1f - s0) * develop,
            brightness = b0 + (1f - b0) * develop,
            alpha = (t / 0.15f).coerceIn(0f, 1f),
        )
    }

    /**
     * 当前页让位的进度：随飞回前 30%（约 135ms）减速退到 0.94 / 0.35，不在一帧里跳变
     * （P5 录屏 06-undo-sheet：原先第 1 帧就整页压暗，相邻帧差 50）。
     */
    fun recede(t: Float): Float = MotionEasings.Decelerate.transform((t / RECEDE_PORTION).coerceIn(0f, 1f))
    private const val RECEDE_PORTION = 0.3f

    private fun fadeOut(p: Float) = if (p < 0.8f) 1f else 1f - (p - 0.8f) / 0.2f

    const val EXIT_END_SCALE = 0.05f
    /** 沉入时亮度到 0.14、饱和到 0。 */
    const val TRASH_END_BRIGHTNESS = 0.14f
    /** 当前页退后 0.94、亮度 0.35。 */
    const val RECEDE_SCALE = 0.94f
    const val RECEDE_BRIGHTNESS = 0.35f
}

/**
 * 清理页舞台状态：三页自绘（上一张 / 当前 / 下一张）+ 离场层，全部手势即时值都是 MutableState，
 * Animatable 只用于松手后的动画（lessons 2026-05-09）。
 *
 * 「当前页」以 UI 本地的 [currentKey] 为准：翻页 / 处理 / 撤回在同一帧内同时改 key 与位移，
 * 不等 ViewModel 的下一次状态，避免切页那一帧闪一下。
 */
@Stable
internal class ReviewStageState(private val scope: CoroutineScope) {
    var width by mutableFloatStateOf(1f)
    var height by mutableFloatStateOf(1f)
    var thresholds: ReviewThresholds = ReviewThresholds(1f, 1f, 1f, 1f, 1f, 1f, 1f)

    var pages: List<ReviewPage> by mutableStateOf(emptyList())
    var currentKey: String? by mutableStateOf(null)

    var dx by mutableFloatStateOf(0f)
        private set
    var dy by mutableFloatStateOf(0f)
        private set
    private var rawDx = 0f

    /**
     * 小结插页上的上下拖动（用户决定 2026-10-11）：不触发任何操作，只按边缘阻尼跟手一点，
     * 松手用 settle 弹簧回弹。独立于 dy，不触发拖动压暗、提示圆和缩放；不阻塞左右翻页和下一次手势。
     */
    var summaryBounce by mutableFloatStateOf(0f)
        private set
    private var rawBounce = 0f
    private var bounceBase = 0f
    private var bounceJob: Job? = null
    var axis by mutableStateOf(DragAxis.None)
        private set
    var dragging by mutableStateOf(false)
        private set
    var pastThreshold by mutableStateOf(false)
        private set
    /** 过阈值形态 0..1（空心细线圆 → 实心）。 */
    val pastAnim = Animatable(0f)
    val popScale = Animatable(1f)
    /**
     * 松手那一刻「松手收藏」圆钮与底部琥珀光不立即消失，保持满强度后 266ms 淡出
     * （周期 3800ms 的 18% 松手 → 25% 透明），与离场照片同时进行。
     */
    val releaseCue = Animatable(0f)
    var releaseCueUp by mutableStateOf(true)
        private set
    val chromeAlpha = Animatable(1f)

    var entryKey: String? by mutableStateOf(null)
        private set
    val entryScale = Animatable(1f)
    val entryBrightness = Animatable(1f)

    var zoom by mutableFloatStateOf(1f)
        private set
    var zoomOffset by mutableStateOf(Offset.Zero)
        private set

    val flights = mutableStateListOf<ExitFlight>()
    var undoFlight: UndoFlight? by mutableStateOf(null)
        private set

    // 外部回调（由 Screen 每次组合时更新）
    var onSettled: (String) -> Unit = {}
    var onSwipe: (VerticalRelease, Long) -> String? = { _, _ -> null }
    var onThresholdChanged: (Boolean) -> Unit = {}
    var onDayChanged: () -> Unit = {}
    var onExitStarted: (ExitKind) -> Unit = {}
    var exitTarget: (ExitKind) -> Offset = { Offset.Zero }
    var verticalEnabled: () -> Boolean = { true }
    /** 最后一张被处理、没有下一页：不再回退到 ViewModel 的旧 currentKey。撤回时清掉。 */
    var sessionEndedLocally: Boolean = false

    private var settleJob: Job? = null
    /** 上下拖动开始时残留横向位移的回弹（独立于 settleJob，竖拖期间继续）。 */
    private var dxSettleJob: Job? = null
    private var zoomJob: Job? = null
    /** 手指实际捏合的倍率；低于 1× 时显示值走阻尼，最多到 0.8。 */
    private var rawZoom = 1f
    private var entryJob: Job? = null
    private var pendingCommitKey: String? = null
    private var pendingCommitShift = 0f
    private var flightSeq = 0L

    /** 按 (pages 实例, currentKey) 缓存：拖动中每个 MOVE、EdgeHint 每帧都要取相邻页，不能每次整表扫描。 */
    private var indexOfPages: List<ReviewPage>? = null
    private var indexOfKey: String? = null
    private var cachedIndex = -1
    val currentIndex: Int
        get() {
            val p = pages
            val k = currentKey
            if (p !== indexOfPages || k != indexOfKey) {
                cachedIndex = p.indexOfFirst { it.key == k }
                indexOfPages = p
                indexOfKey = k
            }
            return cachedIndex
        }
    /** 撤回后 ViewModel 的新序列还没到的那一帧，当前页先用撤回的照片顶上。 */
    private var pinned: ReviewPage? by mutableStateOf(null)
    val currentPage: ReviewPage? get() = pages.getOrNull(currentIndex) ?: pinned?.takeIf { it.key == currentKey }
    val previousPage: ReviewPage? get() = currentIndex.takeIf { it >= 0 }?.let { pages.getOrNull(it - 1) }
    val nextPage: ReviewPage? get() = currentIndex.takeIf { it >= 0 }?.let { pages.getOrNull(it + 1) }
    /** 上下拖动时垫在下面的那页：与 ViewModel 处理后切到的页一致（下一页，没有则上一页）。 */
    val beneathPage: ReviewPage? get() = nextPage ?: previousPage

    val isVerticalDrag: Boolean get() = axis == DragAxis.Vertical && dy != 0f

    // —— 手势 ——

    val handler = object : ReviewGestureHandler {
        override val isZoomed: Boolean get() = zoom > 1.01f

        override fun onDown() {
            settleJob?.cancel()
            settleJob = null
            dxSettleJob?.cancel()
            dxSettleJob = null
            pendingCommitKey?.let { key ->
                // 翻页动画还没落定就按下：直接落定到目标页，位移连续接上
                pendingCommitKey = null
                commit(key)
                dx += pendingCommitShift
            }
            rawDx = dx
        }

        override fun onDragStart(axis: DragAxis) {
            this@ReviewStageState.axis = axis
            dragging = true
            when (axis) {
                DragAxis.Vertical -> {
                    // 翻页动画中途按下又改成上下拖：剩下的横向位移弹回 0，而不是一帧跳回中间
                    if (dx != 0f) settleDx() else dx = 0f
                    rawDx = 0f
                    // 回弹中又按住上下拖：从当前位置接着跟手，不跳
                    bounceJob?.cancel()
                    bounceBase = summaryBounce
                    rawBounce = 0f
                    scope.launch { chromeAlpha.animateTo(0f, tween(MotionDurations.CHROME_FADE_ON_DRAG, easing = LinearEasing)) }
                }
                DragAxis.Horizontal -> {
                    dy = 0f
                    rawDx = dx
                    // 竖拖淡出后被打断（回弹中改成横滑）：横滑不淡出顶栏，确保它回到不透明
                    restoreChrome()
                }
                else -> Unit
            }
        }

        override fun onDrag(axis: DragAxis, delta: Offset) {
            when (axis) {
                DragAxis.Horizontal -> {
                    rawDx += delta.x
                    dx = ReviewGestureMath.horizontalOffset(rawDx, previousPage != null, nextPage != null, thresholds)
                }
                DragAxis.Vertical -> {
                    if (currentPage is ReviewPage.Summary) {
                        rawBounce += delta.y
                        summaryBounce = bounceBase + GestureTokens.edgeResistance(rawBounce, thresholds.edgeLength)
                        return
                    }
                    if (currentPage !is ReviewPage.Photo || !verticalEnabled()) return
                    dy += delta.y
                    val past = kotlin.math.abs(dy) > thresholds.vertical
                    if (past != pastThreshold) setPast(past)
                }
                DragAxis.Pan -> {
                    zoomOffset = clampZoomOffset(zoomOffset + delta, zoom)
                }
                DragAxis.None -> Unit
            }
        }

        override fun onDragEnd(axis: DragAxis, velocity: Velocity) {
            dragging = false
            when (axis) {
                DragAxis.Horizontal -> releaseHorizontal(velocity.x)
                DragAxis.Vertical -> releaseVertical(velocity.y)
                DragAxis.Pan -> Unit
                DragAxis.None -> Unit
            }
        }

        override fun onDragCancel() {
            dragging = false
            if (pastThreshold) setPast(false)
            releaseSummaryBounce(0f)
            settleBack(0f, 0f)
        }

        override fun onUpWithoutDrag() {
            if (dx != 0f || dy != 0f) settleBack(0f, 0f)
        }

        override fun onDoubleTap(position: Offset) {
            if (currentPage !is ReviewPage.Photo) return
            val target = if (zoom > 1.01f) 1f else GestureTokens.DOUBLE_TAP_SCALE
            val center = Offset(width / 2f, height / 2f)
            val targetOffset = if (target == 1f) Offset.Zero
            else clampZoomOffset((position - center) * (1f - target), target)
            animateZoom(target, targetOffset, MotionSprings.Settle)
        }

        override fun onPinch(centroid: Offset, zoomChange: Float, pan: Offset) {
            if (currentPage !is ReviewPage.Photo) return
            zoomJob?.cancel()
            val center = Offset(width / 2f, height / 2f)
            if (zoom >= GestureTokens.ZOOM_REST_MIN) rawZoom = zoom
            rawZoom = (rawZoom * zoomChange).coerceIn(PINCH_RAW_MIN, GestureTokens.ZOOM_MAX)
            val newZoom = GestureTokens.pinchResistance(rawZoom)
            val c = centroid - center
            val k = newZoom / zoom
            zoomOffset = (zoomOffset - c) * k + c + pan
            zoom = newZoom
        }

        override fun onPinchEnd() {
            if (zoom < GestureTokens.ZOOM_REST_MIN) {
                animateZoom(1f, Offset.Zero, MotionSprings.Bouncy)
            } else {
                animateZoom(zoom, clampZoomOffset(zoomOffset, zoom), MotionSprings.Settle)
            }
        }
    }

    private fun setPast(past: Boolean) {
        pastThreshold = past
        onThresholdChanged(past)
        scope.launch {
            if (past) {
                pastAnim.animateTo(1f, tween(MotionDurations.SHORT, easing = MotionEasings.Decelerate))
            } else {
                pastAnim.animateTo(0f, tween(MotionDurations.MICRO, easing = MotionEasings.Accelerate))
            }
        }
        if (past) scope.launch {
            popScale.snapTo(1f)
            popScale.animateTo(POP_OVERSHOOT, tween(MotionDurations.SHORT, easing = MotionEasings.Decelerate))
            popScale.animateTo(1f, MotionSprings.Settle.spec())
        }
    }

    private fun releaseHorizontal(vx: Float, dir: Int = ReviewGestureMath.horizontalRelease(dx, vx, thresholds)) {
        val target = when (dir) {
            -1 -> nextPage
            1 -> previousPage
            else -> null
        }
        com.winters.cleanphotos.core.util.ReviewTrace.log("hRelease", "dir" to dir, "dx" to dx.toInt(), "vx" to vx.toInt(), "from" to currentKey, "to" to target?.key)
        if (target == null) {
            settleBack(vx, 0f)
            return
        }
        val end = if (dir == -1) -width else width
        pendingCommitKey = target.key
        pendingCommitShift = if (dir == -1) width else -width
        settleJob = scope.launch {
            val a = Animatable(dx)
            a.animateTo(end, MotionSprings.Snappy.spec(), initialVelocity = vx) { dx = value }
            pendingCommitKey = null
            commit(target.key)
            dx = 0f
            rawDx = 0f
            axis = DragAxis.None
        }
    }

    private fun releaseVertical(vy: Float) {
        if (currentPage is ReviewPage.Summary || summaryBounce != 0f) {
            releaseSummaryBounce(vy)
            if (currentPage is ReviewPage.Summary) {
                axis = DragAxis.None
                // 上下拖动开始时把顶栏等淡出了，这里不还原的话翻到下一张照片时它们一直是透明的
                scope.launch { chromeAlpha.animateTo(1f, tween(MotionDurations.SHORT, easing = MotionEasings.Standard)) }
                return
            }
        }
        val photo = (currentPage as? ReviewPage.Photo)
        val release = if (photo != null && verticalEnabled()) ReviewGestureMath.verticalRelease(dy, vy, thresholds) else VerticalRelease.None
        if (release == VerticalRelease.None || photo == null) {
            if (pastThreshold) setPast(false)
            settleBack(0f, vy)
            return
        }
        trigger(photo, release, vy)
    }

    /** 过阈值松手：离场层独立播放，当前页立刻换成下一页并可以接受下一次手势。 */
    private fun trigger(photoPage: ReviewPage.Photo, release: VerticalRelease, vy: Float) {
        val kind = if (release == VerticalRelease.Up) ExitKind.Up else ExitKind.Down
        val y = dy
        val flight = ExitFlight(
            id = ++flightSeq,
            page = photoPage,
            kind = kind,
            startY = y,
            startScale = ReviewGestureMath.dragScale(y, thresholds) * currentEntryScale(photoPage.key),
            startSaturation = ReviewGestureMath.dragSaturation(y, thresholds),
            startBrightness = ReviewGestureMath.dragBrightness(y, thresholds) * currentEntryBrightness(photoPage.key),
            velocityY = vy,
            target = exitTarget(kind),
            rise = if (kind == ExitKind.Up) (kotlin.math.abs(vy) * RISE_PER_VELOCITY).coerceIn(RISE_MIN * thresholds.minFlingDistance, RISE_MAX * thresholds.minFlingDistance) else 0f,
        )
        flights.add(flight)
        scope.launch {
            flight.progress.animateTo(1f, tween(MotionDurations.EXIT_MAIN_MAX, easing = LinearEasing))
            // 主体到位即撤掉主体层；收藏的两层余影比主体晚 50 / 100ms，同速继续走完各自的路径和淡出再移除
            // （原先在主体结束时整体移除，余影停在 0.4 / 0.18 透明度一帧消失）
            flight.mainDone = true
            if (kind == ExitKind.Up) {
                val tail = ExitPath.TRAIL_TAIL
                flight.progress.animateTo(1f + tail, tween((MotionDurations.EXIT_MAIN_MAX * tail).roundToInt(), easing = LinearEasing))
            }
            flights.remove(flight)
        }
        onExitStarted(kind)
        releaseCueUp = kind == ExitKind.Up
        scope.launch {
            releaseCue.snapTo(1f)
            releaseCue.animateTo(0f, tween(MotionDurations.RELEASE_CUE_FADE, easing = LinearEasing))
        }
        val newKey = onSwipe(release, photoPage.photo.id)
        dxSettleJob?.cancel()
        dxSettleJob = null
        dy = 0f
        dx = 0f
        rawDx = 0f
        axis = DragAxis.None
        pastThreshold = false
        scope.launch { pastAnim.snapTo(0f) }
        resetZoom()
        currentKey = newKey
        if (newKey != null) {
            startEntry(newKey)
            noteCurrentDay(tick = true)
        }
        scope.launch { chromeAlpha.animateTo(1f, tween(MotionDurations.SHORT, easing = MotionEasings.Standard)) }
    }

    /** 顶栏 / 渐变 / 元信息回到不透明（已在还原或已是 1 时不重启动画）。 */
    private fun restoreChrome() {
        if (chromeAlpha.targetValue == 1f && chromeAlpha.value == 1f) return
        if (chromeAlpha.isRunning && chromeAlpha.targetValue == 1f) return
        scope.launch { chromeAlpha.animateTo(1f, tween(MotionDurations.SHORT, easing = MotionEasings.Standard)) }
    }

    /** 撤回：照片从去处飞回，原当前页退后压暗让位。 */
    fun undo(page: ReviewPage, kind: ExitKind) {
        val key = page.key
        pinned = page
        settleJob?.cancel()
        pendingCommitKey = null
        flights.removeAll { it.page.key == key }
        dxSettleJob?.cancel()
        dxSettleJob = null
        val recede = currentKey
        val flight = UndoFlight(key, kind, exitTarget(kind), recede)
        undoFlight = flight
        currentKey = key
        dx = 0f
        dy = 0f
        rawDx = 0f
        axis = DragAxis.None
        resetZoom()
        entryKey = null
        noteCurrentDay(tick = false)
        restoreChrome()
        scope.launch {
            flight.progress.animateTo(1f, tween(MotionDurations.LONG, easing = LinearEasing))
            if (undoFlight === flight) undoFlight = null
        }
    }

    /**
     * TalkBack 自定义操作「收藏 / 移入垃圾桶」：与上下滑过阈值松手走同一条路径（离场动画 + 同一个回调）。
     * 返回是否执行。
     */
    fun accessibilitySwipe(up: Boolean): Boolean {
        val photo = currentPage as? ReviewPage.Photo ?: return false
        if (dragging || !verticalEnabled()) return false
        trigger(photo, if (up) VerticalRelease.Up else VerticalRelease.Down, 0f)
        return true
    }

    /** TalkBack「上一张」：与右滑一样走翻页动画。 */
    fun goPrevious() {
        if (previousPage == null || pendingCommitKey != null) return
        axis = DragAxis.Horizontal
        releaseHorizontal(thresholds.horizontalFlingVelocity * 2f, dir = 1)
    }

    /** 小结「下一天」：与左滑一样走翻页动画。 */
    fun goNext() {
        if (nextPage == null || pendingCommitKey != null) return
        axis = DragAxis.Horizontal
        // 静止时 dx = 0，按位移推不出方向，直接指定去下一页
        releaseHorizontal(-thresholds.horizontalFlingVelocity * 2f, dir = -1)
    }

    private fun releaseSummaryBounce(vy: Float) {
        rawBounce = 0f
        bounceBase = 0f
        if (summaryBounce == 0f) return
        bounceJob = scope.launch {
            val a = Animatable(summaryBounce)
            a.animateTo(0f, MotionSprings.Settle.spec(), initialVelocity = vy) { summaryBounce = value }
        }
    }

    private fun settleDx() {
        dxSettleJob?.cancel()
        dxSettleJob = scope.launch {
            val a = Animatable(dx)
            a.animateTo(0f, MotionSprings.Settle.spec()) { dx = value }
        }
    }

    private fun settleBack(vx: Float, vy: Float) {
        dxSettleJob?.cancel()
        dxSettleJob = null
        settleJob = scope.launch {
            launch {
                val a = Animatable(dx)
                a.animateTo(0f, MotionSprings.Settle.spec(), initialVelocity = vx) { dx = value }
                rawDx = 0f
            }
            launch {
                val a = Animatable(dy)
                a.animateTo(0f, MotionSprings.Settle.spec(), initialVelocity = vy) { dy = value }
            }
        }.also { job -> job.invokeOnCompletion { if (!dragging) axis = DragAxis.None } }
        // 顶栏等的还原不挂在 settleJob 下：回弹中再次按下会取消 settleJob，挂在下面会让它们停在半透明 / 全透明
        restoreChrome()
    }

    private fun commit(key: String) {
        currentKey = key
        resetZoom()
        noteCurrentDay(tick = true)
        onSettled(key)
    }

    /** 上一次作为「当前」的照片所在日期；小结插页不改变它。 */
    private var lastPhotoDay: String? = null

    /**
     * 跨天 CLOCK_TICK：每当一张与上一张「当前照片」不同日期的照片成为当前页时恰好触发一次。
     * 经过小结插页（照片 → 小结 → 下一天照片）也算一次跨天，在落到下一天照片时触发。
     * 进入会话的第一张、撤回飞回只同步日期，不触发。
     */
    fun noteCurrentDay(tick: Boolean) {
        val photo = (currentPage as? ReviewPage.Photo)?.photo ?: return
        val day = photo.dateGroup
        val previous = lastPhotoDay
        lastPhotoDay = day
        if (tick && previous != null && previous != day) {
            com.winters.cleanphotos.core.util.ReviewTrace.log("dayTick", "from" to previous, "to" to day, "key" to currentKey)
            onDayChanged()
        }
    }

    private fun startEntry(key: String) {
        entryJob?.cancel()
        entryKey = key
        entryJob = scope.launch {
            entryScale.snapTo(DevelopProfiles.REVIEW_NEXT_SCALE_FROM)
            entryBrightness.snapTo(BENEATH_BRIGHTNESS)
            val t0 = android.os.SystemClock.uptimeMillis()
            com.winters.cleanphotos.core.util.ReviewTrace.log(
                "entryStart", "key" to key, "brightness" to entryBrightness.value, "scale" to entryScale.value,
            )
            val s = launch { entryScale.animateTo(1f, MotionSprings.Settle.spec()) }
            val b = launch { entryBrightness.animateTo(1f, tween(ENTRY_TWEEN_MS, easing = MotionEasings.Develop)) }
            b.join()
            com.winters.cleanphotos.core.util.ReviewTrace.log("entryBrightnessDone", "key" to key, "ms" to android.os.SystemClock.uptimeMillis() - t0)
            s.join()
            com.winters.cleanphotos.core.util.ReviewTrace.log("entryScaleDone", "key" to key, "ms" to android.os.SystemClock.uptimeMillis() - t0)
        }
    }

    fun currentEntryScale(key: String): Float = if (key == entryKey) entryScale.value else 1f
    fun currentEntryBrightness(key: String): Float = if (key == entryKey) entryBrightness.value else 1f

    private fun resetZoom() {
        zoomJob?.cancel()
        zoom = 1f
        rawZoom = 1f
        zoomOffset = Offset.Zero
    }

    private fun animateZoom(targetZoom: Float, targetOffset: Offset, spring: com.winters.cleanphotos.core.design.SpringToken) {
        zoomJob?.cancel()
        val fromZoom = zoom
        val fromOffset = zoomOffset
        zoomJob = scope.launch {
            val a = Animatable(0f)
            a.animateTo(1f, spring.spec()) {
                zoom = fromZoom + (targetZoom - fromZoom) * value
                zoomOffset = fromOffset + (targetOffset - fromOffset) * value
            }
        }
    }

    private fun clampZoomOffset(offset: Offset, z: Float): Offset {
        if (z <= 1f) return Offset.Zero
        // 按照片实际显示的大小算：放大后没超出屏幕的那一轴不能拖，超出的拖到照片边缘为止（不拖出上下环境光）
        val photo = (currentPage as? ReviewPage.Photo)?.photo
        val fit = ReviewGestureMath.fitSize(photo?.width, photo?.height, width, height)
        val maxX = maxOf(0f, (fit.width * z - width) / 2f)
        val maxY = maxOf(0f, (fit.height * z - height) / 2f)
        return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }

    companion object {
        /** 实心 pop 1.14 过冲。 */
        const val POP_OVERSHOOT = 1.14f
        private const val PINCH_RAW_MIN = 0.1f
        /** 决定 7：下一张垫在下面的亮度（≥ 0.6）。 */
        const val BENEATH_BRIGHTNESS = 0.6f
        /**
         * 下一张出场亮度动画时长：≤ 280ms 是「屏幕上看到的」上限。动画从下一帧才开始推进，
         * 实测墙钟会多出约 1 帧，所以这里留出 1 帧（280 − 16）。
         */
        const val ENTRY_TWEEN_MS = 264
        /** 收藏上冲距离 = 松手速度 × 0.04s，限制在 1–3 个最小甩动位移（24–72dp）。 */
        const val RISE_PER_VELOCITY = 0.04f
        const val RISE_MIN = 1f
        const val RISE_MAX = 3f
    }
}

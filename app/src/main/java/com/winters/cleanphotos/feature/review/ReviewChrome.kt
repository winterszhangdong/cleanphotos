package com.winters.cleanphotos.feature.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow as DropShadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomIcons
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.GlassIconButton
import com.winters.cleanphotos.core.design.GlassSpec
import com.winters.cleanphotos.core.design.LocalAnimationsEnabled
import com.winters.cleanphotos.core.design.MotionDurations
import com.winters.cleanphotos.core.design.MotionEasings
import com.winters.cleanphotos.core.design.OdometerText
import com.winters.cleanphotos.core.design.glassBlur
import com.winters.cleanphotos.core.design.plainClickable
import com.winters.cleanphotos.core.design.saturateBrightness
import com.winters.cleanphotos.core.util.DayDisplay
import com.winters.cleanphotos.core.util.FileSizeFormatter
import com.winters.cleanphotos.domain.model.DayCellState
import com.winters.cleanphotos.domain.model.PhotoActionType
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.ReviewScope
import com.winters.cleanphotos.domain.model.SessionDayProgress
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- 进度条

/**
 * 当天进度。一格一张 2dp 高、3dp 间隔；收藏琥珀、移走退影灰·深、看过保留相纸白 78%、
 * 当前相纸白发光（1.6s 呼吸）、未处理虚线；取消收藏空心琥珀格（决定 13）；超过 40 张改连续比例条。
 * 处理一张：该格从左向右填色 280ms（标准曲线），光点 150ms 移到下一格。跨天 150ms 交叉淡入（决定 1）。
 */
@Composable
internal fun ReviewProgressBar(progress: SessionDayProgress?, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = progress,
        transitionSpec = {
            fadeIn(tween(MotionDurations.SHORT, easing = MotionEasings.Standard)) togetherWith
                fadeOut(tween(MotionDurations.SHORT, easing = MotionEasings.Standard))
        },
        modifier = modifier,
        label = "day-progress",
        contentKey = { it?.dayKey },
    ) { p ->
        if (p != null) DayCells(p.cells, p.useContinuousBar)
        else Spacer(Modifier.fillMaxWidth().height(SEG_HEIGHT))
    }
}

@Composable
private fun DayCells(cells: List<DayCellState>, continuous: Boolean) {
    val fills = remember { mutableStateMapOf<Int, Animatable<Float, AnimationVector1D>>() }
    val previous = remember { arrayOfNulls<List<DayCellState>>(1) }
    val currentIndex = cells.indexOf(DayCellState.Current)
    // 周期 T = 5200ms 计：光点转移是透明度交叉——旧格 15%→18% 线性淡出（156ms），新格 16%→20% 淡入（晚 52ms，208ms），不滑动
    var glowFrom by remember { mutableIntStateOf(-1) }
    var glowTo by remember { mutableIntStateOf(currentIndex) }
    val glowCross = remember { Animatable(GLOW_CROSS_MS) }
    LaunchedEffect(cells) {
        val old = previous[0]
        previous[0] = cells
        if (old != null && old.size == cells.size) {
            cells.forEachIndexed { i, s ->
                val was = old[i]
                if (s.isFilled() && !was.isFilled()) {
                    val a = Animatable(0f)
                    fills[i] = a
                    launch { launchFill(a) }
                }
            }
        }
    }
    LaunchedEffect(currentIndex) {
        if (currentIndex != glowTo) {
            glowFrom = glowTo
            glowTo = currentIndex
            glowCross.snapTo(0f)
            glowCross.animateTo(GLOW_CROSS_MS, tween(GLOW_CROSS_MS.toInt(), easing = LinearEasing))
        }
    }
    val animations = LocalAnimationsEnabled.current
    val breath = if (animations) {
        rememberInfiniteTransition(label = "seg-breath").animateFloat(
            initialValue = 1f, targetValue = BREATH_LOW,
            animationSpec = infiniteRepeatable(tween(MotionDurations.PROGRESS_BREATH / 2, easing = androidx.compose.animation.core.EaseInOut), RepeatMode.Reverse),
            label = "seg-breath-v",
        )
    } else null
    Canvas(Modifier.fillMaxWidth().height(SEG_HEIGHT)) {
        val gap = SEG_GAP.toPx()
        val h = size.height
        val radius = CornerRadius(h / 2f)
        if (continuous) {
            drawContinuous(cells, radius)
            return@Canvas
        }
        val n = cells.size.coerceAtLeast(1)
        val w = (size.width - gap * (n - 1)) / n
        cells.forEachIndexed { i, s ->
            val x = i * (w + gap)
            drawCell(s, Offset(x, 0f), Size(w, h), radius, fills[i]?.value ?: 1f)
        }
        val breathAlpha = breath?.value ?: 1f
        fun glow(index: Int, a: Float) {
            if (index < 0 || index >= n || a <= 0f) return
            val x = index * (w + gap)
            drawRoundRect(DarkroomColors.SegCurrentGlow, Offset(x - 2f, -2f), Size(w + 4f, h + 4f), CornerRadius(h), alpha = 0.35f * a)
            drawRoundRect(DarkroomColors.Paper, Offset(x, 0f), Size(w, h), radius, alpha = a)
        }
        val c = glowCross.value
        glow(glowFrom, breathAlpha * (1f - c / GLOW_OUT_MS).coerceIn(0f, 1f))
        glow(glowTo, breathAlpha * ((c - GLOW_IN_DELAY_MS) / GLOW_IN_MS).coerceIn(0f, 1f))
    }
}

private suspend fun launchFill(a: Animatable<Float, AnimationVector1D>) {
    a.animateTo(1f, tween(MotionDurations.MEDIUM, easing = MotionEasings.Standard))
}

private fun DayCellState.isFilled() = this != DayCellState.Current && this != DayCellState.Pending

private fun DrawScope.drawCell(s: DayCellState, at: Offset, size: Size, radius: CornerRadius, fill: Float) {
    when (s) {
        DayCellState.Pending, DayCellState.Current -> drawDashed(at, size)
        else -> {
            if (fill < 1f) drawDashed(at, size)
            val filled = Size(size.width * fill, size.height)
            when (s) {
                DayCellState.Favorited -> drawRoundRect(DarkroomColors.Amber, at, filled, radius)
                DayCellState.Trashed -> drawRoundRect(DarkroomColors.FadeDeep, at, filled, radius)
                DayCellState.Kept -> drawRoundRect(DarkroomColors.SegKept, at, filled, radius)
                DayCellState.Unfavorited -> drawRoundRect(
                    DarkroomColors.Amber, at + Offset(0.5f, 0.5f), Size(filled.width - 1f, filled.height - 1f), radius,
                    style = Stroke(width = 1f),
                )
                else -> Unit
            }
        }
    }
}

/** 未处理：3px 实 / 2px 空的虚线（.segs i 的 repeating-linear-gradient）。 */
private fun DrawScope.drawDashed(at: Offset, size: Size) {
    val dash = SEG_DASH.toPx()
    val step = dash + SEG_DASH_GAP.toPx()
    var x = 0f
    while (x < size.width) {
        drawRect(DarkroomColors.SegPending, Offset(at.x + x, at.y), Size(minOf(dash, size.width - x), size.height))
        x += step
    }
}

private fun DrawScope.drawContinuous(cells: List<DayCellState>, radius: CornerRadius) {
    drawDashed(Offset.Zero, size)
    val per = size.width / cells.size.coerceAtLeast(1)
    cells.forEachIndexed { i, s ->
        val color = when (s) {
            DayCellState.Favorited, DayCellState.Unfavorited -> DarkroomColors.Amber
            DayCellState.Trashed -> DarkroomColors.FadeDeep
            DayCellState.Kept -> DarkroomColors.SegKept
            else -> null
        }
        if (color != null) drawRect(color, Offset(i * per, 0f), Size(per + 0.5f, size.height), alpha = if (s == DayCellState.Unfavorited) 0.4f else 1f)
    }
    val c = cells.indexOf(DayCellState.Current)
    if (c >= 0) drawRoundRect(DarkroomColors.Paper, Offset(c * per - 1f, 0f), Size(per.coerceAtLeast(3f), size.height), radius)
}

// ---------------------------------------------------------------- 顶栏

/** .rv-top：40dp 玻璃返回 / 更多 + 日期（里程表）+ mono 星期·时间。 */
@Composable
internal fun ReviewTopBar(photo: PhotoItem?, onBack: () -> Unit, onMore: (() -> Unit)?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(40.dp)) {
        GlassIconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(DarkroomIcons.Back, contentDescription = "返回", tint = DarkroomColors.Paper, modifier = Modifier.size(20.dp))
        }
        if (photo != null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                val shadow = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.4f), Offset(0f, 2.6f), 21f))
                OdometerText(
                    value = DayDisplay.reviewDayOrdinal(photo),
                    style = DarkroomText.RvDate.merge(shadow),
                    format = { DayDisplay.cleanDateOfEpochDay(it) },
                )
                Text(DayDisplay.reviewMeta(photo), style = DarkroomText.RvDateMeta.merge(shadow))
            }
        }
        if (onMore != null) {
            GlassIconButton(onClick = onMore, modifier = Modifier.align(Alignment.CenterEnd)) {
                Icon(DarkroomIcons.More, contentDescription = "更多", tint = DarkroomColors.Paper, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** .rv-meta：文件名 · 大小 + 两颗方向提示小点（决定 10，累计 20 次后隐藏；横屏只留文件信息）。 */
@Composable
internal fun ReviewMeta(photo: PhotoItem, showHints: Boolean, scope: ReviewScope, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        val name = photo.displayName?.substringBeforeLast('.') ?: "—"
        val size = photo.size?.let { " · " + FileSizeFormatter.format(it) }.orEmpty()
        Text(name + size, style = DarkroomText.RvFile, maxLines = 1)
        if (showHints) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HintDot(DarkroomColors.Amber, if (scope == ReviewScope.Favorites) "上滑取消收藏" else "上滑收藏")
                HintDot(DarkroomColors.Fade, "下滑移走")
            }
        }
    }
}

@Composable
private fun HintDot(color: Color, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(label, style = DarkroomText.RvFile)
    }
}

// ---------------------------------------------------------------- 过阈值提示

/**
 * 未过阈值是 32dp 空心细线圆（描边进度 = 距离 / 阈值）+ 横向文案；
 * 过阈值瞬间变 56dp 实心（上滑琥珀 + 6dp 光晕；下滑相纸白），1.14 过冲 pop。
 */
@Composable
internal fun ReviewCue(state: ReviewStageState, up: Boolean, scope: ReviewScope, modifier: Modifier = Modifier, released: Boolean = false) {
    val past = state.pastThreshold || released
    val accent = if (up) DarkroomColors.Amber else DarkroomColors.Paper
    val unfav = up && scope == ReviewScope.Favorites
    val icon = if (up) (if (unfav) DarkroomIcons.Star else DarkroomIcons.StarSolid) else DarkroomIcons.Trash
    if (!past) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            val ringColor = if (up) DarkroomColors.Amber.copy(alpha = 0.6f) else PAPER_WARM.copy(alpha = 0.32f)
            val textColor = if (up) DarkroomColors.Amber.copy(alpha = 0.8f) else PAPER_WARM.copy(alpha = 0.5f)
            Box(
                Modifier.size(32.dp).drawBehind {
                    val w = 1.5.dp.toPx()
                    drawCircle(ringColor.copy(alpha = ringColor.alpha * 0.45f), radius = size.minDimension / 2f - w / 2f, style = Stroke(w))
                    val p = ReviewGestureMath.cueProgress(state.dy, state.thresholds)
                    drawArc(
                        ringColor, -90f, 360f * p, false,
                        topLeft = Offset(w / 2f, w / 2f), size = Size(size.width - w, size.height - w),
                        style = Stroke(w, cap = StrokeCap.Round),
                    )
                },
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = textColor, modifier = Modifier.size(16.dp)) }
            Text(
                if (up) (if (unfav) "继续上滑取消收藏" else "继续上滑收藏") else "继续下滑",
                style = DarkroomText.CuePre.copy(color = textColor),
            )
        }
    } else {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .graphicsLayer { val k = if (released) 1f else state.popScale.value; scaleX = k; scaleY = k }
                    .size(56.dp)
                    .then(
                        if (up) Modifier.dropShadow(CircleShape, DropShadow(radius = 32.dp, color = DarkroomColors.AmberGlow))
                        else Modifier,
                    )
                    .drawBehind {
                        // 6dp 光晕（box-shadow 0 0 0 6px）
                        drawCircle(
                            if (up) DarkroomColors.Amber.copy(alpha = 0.18f) else DarkroomColors.Paper.copy(alpha = 0.08f),
                            radius = size.minDimension / 2f + 6.dp.toPx(),
                        )
                    }
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = if (up) AMBER_INK else DarkroomColors.Room, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (up) (if (unfav) "松手取消收藏" else "松手收藏") else "松手移到垃圾桶",
                style = DarkroomText.CueTitle.copy(color = accent),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (up) (if (unfav) "还在相册里" else "放进收藏") else "清空前都能找回",
                style = DarkroomText.CueSmall.copy(color = if (up) DarkroomColors.Amber else DarkroomColors.Dim),
            )
        }
    }
}

// ---------------------------------------------------------------- 撤回

/**
 * 玻璃胶囊 56dp + 40dp 圆形小样 + 文案 + 相纸白「撤回」+ 底部 2dp 4 秒倒计时。
 * 移入垃圾桶：小样去色压暗、中性灰；收藏：琥珀。收起 200ms 加速离场。
 */
@Composable
internal fun ReviewUndoBar(
    undo: UndoInfo?,
    visible: Boolean,
    onUndo: () -> Unit,
    onExpired: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val last = remember { arrayOfNulls<UndoInfo>(1) }
    if (undo != null) last[0] = undo
    AnimatedVisibility(
        visible = undo != null && visible,
        modifier = modifier,
        enter = fadeIn(tween(MotionDurations.MEDIUM, easing = MotionEasings.Standard)) +
            slideInVertically(tween(MotionDurations.MEDIUM, easing = MotionEasings.Decelerate)) { it / 3 },
        exit = fadeOut(tween(MotionDurations.UNDO_BAR_EXIT, easing = MotionEasings.Accelerate)) +
            slideOutVertically(tween(MotionDurations.UNDO_BAR_EXIT, easing = MotionEasings.Accelerate)) { it / 3 },
    ) {
        val info = undo ?: last[0] ?: return@AnimatedVisibility
        UndoCapsule(info, onUndo, onExpired)
    }
}

@Composable
private fun UndoCapsule(info: UndoInfo, onUndo: () -> Unit, onExpired: (Long) -> Unit) {
    val fav = info.type != PhotoActionType.MoveToTrash
    val countdown = remember(info.seq) { Animatable(1f) }
    val animations = LocalAnimationsEnabled.current
    LaunchedEffect(info.seq) {
        // 4 秒是真实时间：Compose 动画会乘系统动画缩放，「移除动画」时 tween 立刻结束，撤回条一闪就没了。
        // 细线只做显示；过期由不受缩放影响的 delay 计时。动画关闭时细线保持满格。
        if (animations) launch { countdown.animateTo(0f, tween(MotionDurations.UNDO_COUNTDOWN, easing = LinearEasing)) }
        delay(MotionDurations.UNDO_COUNTDOWN.toLong())
        onExpired(info.seq)
    }
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .dropShadow(shape, DropShadow(radius = 40.dp, color = Color.Black.copy(alpha = 0.5f), offset = DpOffset(0.dp, 16.dp)))
            .clip(shape)
            .glassBlur(UndoGlassSpec)
            .border(1.dp, DarkroomColors.MenuRim, shape),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp).height(56.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UndoThumb(info.photo, fav)
            Column(Modifier.weight(1f)) {
                Text(info.title, style = DarkroomText.UndoTitle, maxLines = 1)
                Text(info.subtitle, style = DarkroomText.UndoSub, maxLines = 1)
            }
            Row(
                Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(DarkroomColors.Paper)
                    .plainClickable(onClick = onUndo)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(DarkroomIcons.Undo, null, tint = DarkroomColors.Room, modifier = Modifier.size(16.dp))
                Text("撤回", style = DarkroomText.UndoAct)
            }
        }
        Canvas(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 28.dp)
                .fillMaxWidth()
                .height(2.dp),
        ) {
            val track = if (fav) DarkroomColors.Amber.copy(alpha = 0.25f) else DarkroomColors.Paper.copy(alpha = 0.12f)
            val bar = if (fav) DarkroomColors.Amber else DarkroomColors.Fade
            drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2f))
            drawRect(bar, size = Size(size.width * countdown.value, size.height))
        }
    }
}

@Composable
private fun UndoThumb(photo: PhotoItem, fav: Boolean) {
    val context = LocalContext.current
    val request = remember(photo.id) {
        ImageRequest.Builder(context).data(photo.uri).size(THUMB_PX).memoryCacheKey("review-thumb-${photo.id}").crossfade(false).build()
    }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(DarkroomColors.Tray2),
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = if (fav) null else UNDO_TRASH_FILTER,
            modifier = Modifier.size(40.dp),
        )
        Box(
            Modifier
                .size(40.dp)
                .border(if (fav) 1.5.dp else 1.dp, if (fav) DarkroomColors.Amber else DarkroomColors.Paper.copy(alpha = 0.16f), CircleShape),
        )
    }
}

/** 撤回胶囊玻璃：rgba(26,24,22,0.78) + blur(24)。 */
private val UndoGlassSpec = GlassSpec(DarkroomColors.UndoGlass, 24.dp)
/** .undo .thumb img：grayscale(0.6) brightness(0.7)。 */
private val UNDO_TRASH_FILTER: ColorFilter = saturateBrightness(0.4f, 0.7f)
private const val THUMB_PX = 160
private const val BREATH_LOW = 0.55f
private val SEG_HEIGHT = 2.dp
private val SEG_GAP = 3.dp
private val SEG_DASH = 3.dp
private val SEG_DASH_GAP = 2.dp
/** .up .ring 图标色 #2A1A04。 */
private val AMBER_INK = Color(0xFF2A1A04)
/** .down.pre 的 rgba(238,238,226)。 */
private val PAPER_WARM = Color(0xFFEEEEE2)

private const val GLOW_OUT_MS = 156f
private const val GLOW_IN_DELAY_MS = 52f
private const val GLOW_IN_MS = 208f
private const val GLOW_CROSS_MS = GLOW_IN_DELAY_MS + GLOW_IN_MS

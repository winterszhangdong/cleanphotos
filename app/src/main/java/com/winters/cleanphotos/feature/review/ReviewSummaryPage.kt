package com.winters.cleanphotos.feature.review

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.winters.cleanphotos.core.design.ApiTier
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomIcons
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.DevelopMatrix
import com.winters.cleanphotos.core.design.LocalAnimationsEnabled
import com.winters.cleanphotos.core.design.MotionEasings
import com.winters.cleanphotos.core.design.MotionSprings
import com.winters.cleanphotos.core.design.plainClickable
import com.winters.cleanphotos.core.design.saturateBrightness
import com.winters.cleanphotos.core.util.DayDisplay
import com.winters.cleanphotos.core.util.FileSizeFormatter
import com.winters.cleanphotos.domain.model.DaySummary
import com.winters.cleanphotos.domain.model.MonthDayState
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.ReviewOutcome
import kotlin.math.roundToInt

/**
 * 小结插页（决定 2）：翻页序列里的一页，不阻塞；任何时刻都可以左滑继续。
 * 入场编排（标题 → 数字 → 比例条 → 月份条 → 下一天卡，约 1.6s），用一条时间轴在绘制阶段派生各段进度。
 */
@Composable
internal fun ReviewSummaryPage(
    summary: DaySummary,
    isCurrent: Boolean,
    showNext: Boolean,
    landscape: Boolean,
    onNext: () -> Unit,
    onBackToPhotos: () -> Unit,
) {
    val animations = LocalAnimationsEnabled.current
    val clock = remember(summary.dayKey) { Animatable(if (animations) 0f else TOTAL_MS) }
    LaunchedEffect(summary.dayKey, isCurrent) {
        if (isCurrent && clock.value == 0f) clock.animateTo(TOTAL_MS, tween(TOTAL_MS.toInt(), easing = LinearEasing))
    }
    fun window(startMs: Float, durMs: Float): Float = ((clock.value - startMs) / durMs).coerceIn(0f, 1f)
    /** 「升起：舒缓 0.85 / 200」：按时间轴直接取弹簧值。 */
    fun rise(startMs: Float): Float = MotionSprings.Gentle.valueAt(clock.value - startMs)

    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // 背景放大 1.2 倍，不裁切会在翻页时盖到相邻照片边缘（lessons 2026-10-12）
    Box(Modifier.fillMaxSize().clipToBounds().background(DarkroomColors.Room)) {
        SummaryBackdrop(summary.previews.firstOrNull()?.photo)
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = status + if (landscape) 40.dp else 64.dp, start = 24.dp, end = 24.dp),
        ) {
            Column(Modifier.graphicsLayer { riseIn(rise(0f)) }) {
                Text(DayDisplay.summaryEyebrow(summary.date), style = DarkroomText.SumEyebrow)
                Spacer(Modifier.height(8.dp))
                Text("这一天整理完了", style = DarkroomText.SumTitle)
            }
            Spacer(Modifier.height(32.dp))
            StackBar(summary) { i -> window(STACK_START + i * 280f, 280f) }
            Spacer(Modifier.height(24.dp))
            Stats(summary) { i -> window(NUM_START + i * 90f, 700f) }
            if (!landscape) {
                Spacer(Modifier.height(32.dp))
                Strip(summary, Modifier.graphicsLayer { alpha = MotionEasings.Decelerate.transform(window(STRIP_START, 400f)) })
                Spacer(Modifier.height(32.dp))
                Month(summary) { i -> MotionSprings.Settle.valueAt(clock.value - TICK_START - i * 22f) }
            }
        }
        if (showNext && summary.nextDay != null) {
            NextDayCard(
                summary,
                onNext,
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = nav + if (landscape) 56.dp else 88.dp)
                    .graphicsLayer { riseIn(rise(NEXT_START)) },
            )
        }
        Text(
            "回到照片",
            style = DarkroomText.Later,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = nav + if (landscape) 20.dp else 40.dp)
                .plainClickable(onClick = onBackToPhotos)
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .graphicsLayer { alpha = rise(NEXT_START).coerceIn(0f, 1f) },
        )
    }
}

/** 升起：translationY 24 → 0 + 淡入，q 为舒缓弹簧值（可略过冲）。 */
private fun androidx.compose.ui.graphics.GraphicsLayerScope.riseIn(q: Float) {
    alpha = q.coerceIn(0f, 1f)
    translationY = (1f - q) * 24.dp.toPx()
}

@Composable
private fun SummaryBackdrop(photo: PhotoItem?) {
    if (photo == null) return
    val context = LocalContext.current
    val request = remember(photo.id) {
        ImageRequest.Builder(context).data(photo.uri).size(BACKDROP_PX).memoryCacheKey("review-sum-${photo.id}").crossfade(false).build()
    }
    val blur = ApiTier.Current.hasBlur
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        // .sum-bg img：blur(36) brightness(0.32) saturate(1.2)；26–30 不模糊：压暗到 0.2 + 暗角
        colorFilter = ColorFilter.colorMatrix(ColorMatrix(DevelopMatrix.compose(if (blur) 0.32f else 0.2f, 1f, 1.2f))),
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { scaleX = 1.2f; scaleY = 1.2f }
            .then(if (blur) Modifier.blur(36.dp) else Modifier.drawWithContent {
                drawContent()
                drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)), radius = size.maxDimension * 0.7f))
            }),
    )
}

private data class Segment(val count: Int, val color: Color, val hollow: Boolean = false)

/** .stack：6dp，琥珀（收藏）/ 退影灰（移走）/ 相纸白 70%（保留）；取消收藏为空心琥珀段（决定 13）。 */
@Composable
private fun StackBar(summary: DaySummary, progress: (Int) -> Float) {
    val segments = listOf(
        Segment(summary.favoritedCount, DarkroomColors.Amber),
        Segment(summary.unfavoritedCount, DarkroomColors.Amber, hollow = true),
        Segment(summary.trashedCount, DarkroomColors.Fade),
        Segment(summary.keptCount, DarkroomColors.Paper.copy(alpha = 0.7f)),
    ).filter { it.count > 0 }
    val total = segments.sumOf { it.count }.coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        val gap = 2.dp.toPx()
        val usable = size.width - gap * (segments.size - 1).coerceAtLeast(0)
        var x = 0f
        val r = CornerRadius(3.dp.toPx())
        segments.forEachIndexed { i, s ->
            val w = usable * s.count / total
            val p = MotionEasings.Standard.transform(progress(i))
            if (s.hollow) {
                drawRoundRect(s.color, Offset(x + 0.75f, 0.75f), Size(w * p - 1.5f, size.height - 1.5f), r, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
            } else {
                drawRoundRect(s.color, Offset(x, 0f), Size(w * p, size.height), r)
            }
            x += w + gap
        }
    }
}

/** .stats：三列（从收藏进入时多一列「取消收藏」），数字从 0 滚到终值，「移走」用暗一档的灰。 */
@Composable
private fun Stats(summary: DaySummary, progress: (Int) -> Float) {
    data class Stat(val value: Int, val label: String, val color: Color, val extra: String? = null)
    val stats = buildList {
        if (summary.favoritedCount > 0 || summary.unfavoritedCount == 0) add(Stat(summary.favoritedCount, "收藏", DarkroomColors.Amber))
        if (summary.unfavoritedCount > 0) add(Stat(summary.unfavoritedCount, "取消收藏", DarkroomColors.Amber.copy(alpha = 0.7f)))
        add(Stat(summary.trashedCount, "移走", DarkroomColors.FadeText, "释放 " + FileSizeFormatter.format(summary.freedBytes)))
        add(Stat(summary.keptCount, "保留", DarkroomColors.Paper))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        stats.forEachIndexed { i, s ->
            Column(Modifier.weight(1f)) {
                val p = MotionEasings.CountDecelerate.transform(progress(i))
                Text((s.value * p).roundToInt().toString(), style = DarkroomText.SumStat.copy(color = s.color))
                Spacer(Modifier.height(4.dp))
                Text(s.label, style = DarkroomText.SumLabel)
                if (s.extra != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(s.extra, style = DarkroomText.SumExtra)
                }
            }
        }
    }
}

/** .strip：前 8 张，收藏描琥珀边，移走去色压到 30% 亮度，不加斜线。上方 1dp 细线 + 24dp 间距。 */
@Composable
private fun Strip(summary: DaySummary, modifier: Modifier) {
    val context = LocalContext.current
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(DarkroomColors.Rim))
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(8) { i ->
                val preview = summary.previews.getOrNull(i)
                Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(4.dp))) {
                    if (preview != null) {
                        val request = remember(preview.photo.id) {
                            ImageRequest.Builder(context).data(preview.photo.uri).size(MINI_PX).crossfade(false).build()
                        }
                        AsyncImage(
                            model = request,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            colorFilter = if (preview.outcome == ReviewOutcome.Trashed) TRASHED_MINI else null,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (preview.outcome == ReviewOutcome.Favorited || preview.outcome == ReviewOutcome.Unfavorited) {
                            Box(
                                Modifier.fillMaxSize().border(
                                    1.5.dp,
                                    if (preview.outcome == ReviewOutcome.Favorited) DarkroomColors.Amber else DarkroomColors.Amber.copy(alpha = 0.5f),
                                    RoundedCornerShape(4.dp),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** .month：每天一根 16dp 竖条；整理完 60%、小结那一天发光（决定 14）、没整理 20%、没照片 / 还没到只剩 4dp 底座。 */
@Composable
private fun Month(summary: DaySummary, progress: (Int) -> Float) {
    val m = summary.month
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${m.month.monthValue}月", style = DarkroomText.MonthHead)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = DarkroomColors.Paper, fontWeight = FontWeight.Medium)) { append(m.doneDays.toString()) }
                    append(" / ${m.daysWithPhotos} 天整理完")
                },
                style = DarkroomText.MonthHeadMono,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            m.days.forEachIndexed { i, s ->
                val (h: Dp, color: Color) = when (s) {
                    MonthDayState.Done -> 16.dp to DarkroomColors.Paper.copy(alpha = 0.6f)
                    MonthDayState.Highlight -> 16.dp to DarkroomColors.Paper
                    MonthDayState.Pending -> 16.dp to DarkroomColors.Paper.copy(alpha = 0.2f)
                    MonthDayState.Empty -> 4.dp to DarkroomColors.Paper.copy(alpha = if (i < summary.date.dayOfMonth) 0.12f else 0.05f)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(h)
                        .graphicsLayer {
                            // 落定弹簧 scaleY 0 → 1，透明度 0.2 → 1
                            val p = progress(i)
                            scaleY = p
                            alpha = 0.2f + 0.8f * p.coerceIn(0f, 1f)
                            transformOrigin = TransformOrigin(0.5f, 1f)
                        }
                        .then(
                            if (s == MonthDayState.Highlight) Modifier.dropShadow(RoundedCornerShape(2.dp), Shadow(radius = 8.dp, color = DarkroomColors.SegCurrentGlow))
                            else Modifier,
                        )
                        .clip(RoundedCornerShape(2.dp))
                        .background(color),
                )
            }
        }
    }
}

/** .next：相纸白实心卡 64dp，圆角 20，带下一天封面 48dp、未看数和箭头。 */
@Composable
private fun NextDayCard(summary: DaySummary, onNext: () -> Unit, modifier: Modifier) {
    val next = summary.nextDay ?: return
    val context = LocalContext.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .dropShadow(shape, Shadow(radius = 40.dp, color = Color.Black.copy(alpha = 0.4f), offset = DpOffset(0.dp, 16.dp)))
            .clip(shape)
            .background(DarkroomColors.Paper)
            .plainClickable(onClick = onNext)
            .padding(start = 8.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val request = remember(next.cover.id) {
            ImageRequest.Builder(context).data(next.cover.uri).size(MINI_PX).crossfade(false).build()
        }
        AsyncImage(
            model = request, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
        )
        Column(Modifier.weight(1f)) {
            val date = com.winters.cleanphotos.domain.model.DayKey.parse(next.dayKey)
            Text("下一天 · " + (date?.let { DayDisplay.cleanDate(it) } ?: "未知日期"), style = DarkroomText.NextTitle)
            Text(if (next.unseenCount > 0) "${next.unseenCount} 张未看" else "都看过了", style = DarkroomText.NextSub)
        }
        Icon(DarkroomIcons.Arrow, null, tint = DarkroomColors.Room, modifier = Modifier.size(20.dp))
    }
}

/** 月份条最后一根（800 + 30×22）还要约 360ms 落定，时间轴放到 1.9s。 */
private const val TOTAL_MS = 1900f
private const val NUM_START = 200f
private const val STACK_START = 300f
private const val STRIP_START = 700f
private const val TICK_START = 800f
private const val NEXT_START = 1000f
private const val BACKDROP_PX = 256
private const val MINI_PX = 160
/** .mini.del img：grayscale(1) brightness(0.3)。 */
private val TRASHED_MINI: ColorFilter = saturateBrightness(0f, 0.3f)

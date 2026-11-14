package com.winters.cleanphotos.feature.allphotos

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import com.winters.cleanphotos.R
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomFonts
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.LocalAnimationsEnabled
import com.winters.cleanphotos.core.design.MotionDurations
import com.winters.cleanphotos.core.design.MotionEasings
import com.winters.cleanphotos.core.locale.currentLocale
import com.winters.cleanphotos.core.util.DayDisplay
import com.winters.cleanphotos.domain.model.PhotoDaySection
import com.winters.cleanphotos.domain.model.SectionHeader
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 日期滑块正按着。
 * [pausing] 给预取窗读，任何线程都能读。
 * [generation] 只在松手时加一，让还停在屏上的格子重组并挂回共享元素。按住时不加，避免把即将划走的格子再组合一遍。
 */
internal class DateScrubActive {
    @Volatile
    var pausing: Boolean = false
        private set

    var generation by mutableIntStateOf(0)
        private set

    fun setScrubbing(active: Boolean) {
        if (pausing == active) return
        pausing = active
        if (!active) generation++
    }
}

/** 滑块上的一站：分组视图锚在日期行，沉浸视图锚在这一天第一张。 */
internal data class ScrubStop(val dayKey: String, val anchorKey: Any)

/** 按当前网格顺序的日期站。平铺（没有标题）不产生站。 */
internal fun scrubStops(sections: List<PhotoDaySection>, immersive: Boolean): List<ScrubStop> {
    return sections.mapNotNull { section ->
        if (section.header == SectionHeader.None) return@mapNotNull null
        val anchor: Any = if (immersive) {
            section.photos.firstOrNull()?.id ?: return@mapNotNull null
        } else {
            section.dayKey
        }
        ScrubStop(section.dayKey, anchor)
    }
}

/**
 * 日期行要落到「现在还露着的顶栏」下面，而不是内容 padding 的起点。
 * 顶栏收起时，padding 那一截还在视口里，offset 为 0 会把上一天露出来。
 * [collapseOffsetPx] 是 0 到 -顶栏高。
 */
internal fun scrubContentScrollOffset(collapseOffsetPx: Float): Int =
    (-collapseOffsetPx).roundToInt().coerceAtLeast(0)

/** 0 在轨道顶（列表开头），1 在轨道底。一天时永远是 0。 */
internal fun scrubDayIndex(fraction: Float, dayCount: Int): Int {
    if (dayCount <= 1) return 0
    return (fraction.coerceIn(0f, 1f) * (dayCount - 1)).roundToInt()
}

/**
 * 手指在轨道里的位置。拇指高度从可滑距离里扣掉，这样拇指中心能贴到两头。
 */
internal fun scrubFractionFromTouch(y: Float, trackTop: Float, trackHeight: Float, thumbHeight: Float): Float {
    val travel = (trackHeight - thumbHeight).coerceAtLeast(1f)
    return ((y - trackTop - thumbHeight / 2f) / travel).coerceIn(0f, 1f)
}

/**
 * 滑块气泡的一行日期。中文「2024年8月3日」，英文「Aug 3, 2024」。
 * 月、日是不补零的数字；界面给它们各留两位，个位数靠右，年、月、日三个字才不会跟着挪。
 */
internal data class ScrubDateLabel(
    val year: String,
    val month: String,
    val day: String,
    val chinese: Boolean,
)

internal fun scrubDateLabel(date: LocalDate, locale: Locale): ScrubDateLabel {
    val chinese = locale.language == "zh"
    return ScrubDateLabel(
        year = date.year.toString(),
        month = if (chinese) date.monthValue.toString() else DayDisplay.monthName(date.monthValue, locale),
        day = date.dayOfMonth.toString(),
        chinese = chinese,
    )
}

/** 读屏用的整句，不含占位空格。 */
internal fun scrubDatePhrase(label: ScrubDateLabel): String =
    if (label.chinese) "${label.year}年${label.month}月${label.day}日" else "${label.month} ${label.day}, ${label.year}"

/** 列表滚到哪，滑块就在哪。不满两项时停在顶上。 */
internal fun scrubFractionForScroll(index: Int, offsetPx: Int, itemHeightPx: Int, itemCount: Int): Float {
    if (itemCount <= 1) return 0f
    val partial = if (itemHeightPx > 0) offsetPx.toFloat() / itemHeightPx else 0f
    return ((index + partial) / (itemCount - 1)).coerceIn(0f, 1f)
}

/**
 * 每个日期站在网格里的项下标，和 [scrubStops] 同一顺序。
 * 拖动时按天下标直接查，不再从第一天扫到目标天。
 */
internal fun scrubItemIndices(sections: List<PhotoDaySection>, immersive: Boolean): IntArray {
    var count = 0
    for (section in sections) {
        if (section.header == SectionHeader.None) continue
        if (immersive && section.photos.isEmpty()) continue
        count++
    }
    val indices = IntArray(count)
    var cursor = 0
    var stop = 0
    for (section in sections) {
        if (!immersive) {
            if (section.yearHeader != null) cursor++
            if (section.header != SectionHeader.None) {
                indices[stop++] = cursor
                cursor++
            }
        } else if (section.header != SectionHeader.None && section.photos.isNotEmpty()) {
            indices[stop++] = cursor
        }
        cursor += section.photos.size
    }
    return indices
}

/** 和 [photoDaySections] 同一套项顺序。找不到时返回 0。 */
internal fun indexOfAnchor(sections: List<PhotoDaySection>, immersive: Boolean, anchorKey: Any): Int {
    var cursor = 0
    for (section in sections) {
        if (!immersive) {
            if (section.yearHeader != null) {
                if (anchorKey == DayExpand.yearKey(section.yearHeader, section.dayKey)) return cursor
                cursor++
            }
            if (section.header != SectionHeader.None) {
                if (anchorKey == section.dayKey) return cursor
                cursor++
            }
        }
        for (photo in section.photos) {
            if (anchorKey == photo.id) return cursor
            cursor++
        }
    }
    return 0
}

/**
 * 「全部」右侧的日期滑块。列表能滚、而且正在滚（或刚松手）时才出现。
 * 按住轨道拖时，旁边写出这一天，松手停在这一天的开头。
 * 不满一屏不出现。位置在布局阶段读，不跟着每一帧重组。
 * 换天用 [LazyGridState.requestScrollToItem]，同一帧里多次调用只量最后一天。
 */
@Composable
internal fun DateScrubber(
    sections: List<PhotoDaySection>,
    immersive: Boolean,
    gridState: LazyGridState,
    topPadding: Dp,
    bottomPadding: Dp,
    collapseOffsetPx: () -> Float,
    scrubActive: DateScrubActive,
    modifier: Modifier = Modifier,
) {
    val stops = remember(sections, immersive) { scrubStops(sections, immersive) }
    val itemIndices = remember(sections, immersive) { scrubItemIndices(sections, immersive) }
    val locale = currentLocale()
    val unknown = stringResource(R.string.date_unknown)
    val description = stringResource(R.string.cd_date_scrubber)
    val density = LocalDensity.current
    val thumbHeightPx = with(density) { Dimens.ScrubberThumbHeight.toPx() }
    val animations = LocalAnimationsEnabled.current
    val alpha = remember { Animatable(0f) }
    var present by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    var scrolling by remember { mutableStateOf(false) }
    var scrollable by remember { mutableStateOf(false) }
    val scrubbing = remember { mutableStateOf(false) }
    val dragFraction = remember { mutableFloatStateOf(Float.NaN) }
    var bubble by remember { mutableStateOf<ScrubBubble?>(null) }
    var trackHeightPx by remember { mutableStateOf(0) }
    val stopsState = rememberUpdatedState(stops)
    val itemIndicesState = rememberUpdatedState(itemIndices)
    val sectionsState = rememberUpdatedState(sections)
    val collapseOffsetState = rememberUpdatedState(collapseOffsetPx)
    val active = scrollable && (scrolling || scrubbing.value)

    LaunchedEffect(gridState) {
        snapshotFlow {
            gridState.isScrollInProgress to (gridState.canScrollForward || gridState.canScrollBackward)
        }.collect { (inProgress, canScroll) ->
            scrolling = inProgress
            scrollable = canScroll
        }
    }
    // 停留计时单独走：写在 snapshotFlow 的 collect 里会把下一次滚动挡住，直到 900ms 结束。
    LaunchedEffect(active, scrollable) {
        if (!scrollable) {
            shown = false
            return@LaunchedEffect
        }
        if (active) {
            shown = true
        } else if (shown) {
            delay(MotionDurations.SCRUBBER_HOLD.toLong())
            shown = false
        }
    }
    LaunchedEffect(shown, animations) {
        if (shown) {
            present = true
            if (animations) {
                alpha.animateTo(1f, tween(MotionDurations.SHORT, easing = MotionEasings.Standard))
            } else {
                alpha.snapTo(1f)
            }
        } else {
            if (animations && present) {
                alpha.animateTo(0f, tween(MotionDurations.SHORT, easing = MotionEasings.Standard))
            } else {
                alpha.snapTo(0f)
            }
            if (!scrubbing.value) present = false
        }
    }

    if (stops.isEmpty() || (!present && !scrubbing.value)) return

    fun labelAt(fraction: Float): ScrubBubble {
        val list = stopsState.value
        val index = scrubDayIndex(fraction, list.size)
        val key = list.getOrNull(index)?.dayKey ?: return ScrubBubble.Unknown
        val date = sectionsState.value.firstOrNull { it.dayKey == key }?.date ?: return ScrubBubble.Unknown
        return ScrubBubble.Dated(scrubDateLabel(date, locale))
    }

    Box(
        modifier
            .fillMaxSize()
            .padding(top = topPadding, bottom = bottomPadding)
            .onSizeChanged { trackHeightPx = it.height }
            .graphicsLayer { this.alpha = alpha.value },
    ) {
        val shown = bubble
        if (shown != null) {
            val bubbleModifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = Dimens.MinTouch + Dimens.ScrubberBubbleGap)
                .offsetThumb(dragFraction, gridState, thumbHeightPx, trackHeightPx)
                .scrubBubbleFrame()
            val bubbleStyle = DarkroomText.MenuItem.copy(
                color = DarkroomColors.Paper,
                fontFeatureSettings = DarkroomFonts.TABULAR,
            )
            when (shown) {
                ScrubBubble.Unknown -> Text(unknown, style = bubbleStyle, modifier = bubbleModifier)
                is ScrubBubble.Dated -> ScrubDateLine(shown.label, locale, bubbleStyle, bubbleModifier)
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = Dimens.ScrubberInset)
                .offsetThumb(dragFraction, gridState, thumbHeightPx, trackHeightPx)
                .width(Dimens.ScrubberThumbWidth)
                .height(Dimens.ScrubberThumbHeight)
                .clip(RoundedCornerShape(Dimens.ScrubberThumbWidth / 2))
                .background(DarkroomColors.Paper),
        )
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(Dimens.MinTouch)
                .semantics { contentDescription = description }
                .pointerInput(thumbHeightPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        scrubbing.value = true
                        scrubActive.setScrubbing(true)
                        var lastIndex = -1
                        var lastOffset = Int.MIN_VALUE
                        try {
                            fun at(y: Float) {
                                val fraction = scrubFractionFromTouch(y, 0f, size.height.toFloat(), thumbHeightPx)
                                dragFraction.floatValue = fraction
                                val next = labelAt(fraction)
                                if (bubble != next) bubble = next
                                val index = scrubDayIndex(fraction, stopsState.value.size)
                                val offset = scrubContentScrollOffset(collapseOffsetState.value())
                                if (index == lastIndex && kotlin.math.abs(offset - lastOffset) < 8) return
                                lastIndex = index
                                lastOffset = offset
                                val item = itemIndicesState.value[index]
                                gridState.requestScrollToItem(item, offset)
                            }
                            at(down.position.y)
                            drag(down.id) { change ->
                                change.consume()
                                at(change.position.y)
                            }
                        } finally {
                            scrubActive.setScrubbing(false)
                            scrubbing.value = false
                            dragFraction.floatValue = Float.NaN
                            bubble = null
                        }
                    }
                },
        )
    }
}

/** 拖的时候跟手指，否则跟列表滚动。只在布局阶段读。 */
private fun Modifier.offsetThumb(
    dragFraction: MutableFloatState,
    gridState: LazyGridState,
    thumbHeightPx: Float,
    trackHeightPx: Int,
): Modifier = offset {
    val dragged = dragFraction.floatValue
    val fraction = if (!dragged.isNaN()) {
        dragged
    } else {
        val info = gridState.layoutInfo
        val itemHeight = info.visibleItemsInfo.firstOrNull()?.size?.height ?: 0
        scrubFractionForScroll(
            gridState.firstVisibleItemIndex,
            gridState.firstVisibleItemScrollOffset,
            itemHeight,
            info.totalItemsCount,
        )
    }
    val travel = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
    IntOffset(0, (fraction.coerceIn(0f, 1f) * travel).roundToInt())
}

private sealed interface ScrubBubble {
    data class Dated(val label: ScrubDateLabel) : ScrubBubble
    data object Unknown : ScrubBubble
}

private fun Modifier.scrubBubbleFrame(): Modifier =
    clip(RoundedCornerShape(Dimens.MenuItemCorner))
        .background(DarkroomColors.Tray)
        .border(Dimens.Hairline, DarkroomColors.Rim, RoundedCornerShape(Dimens.MenuItemCorner))
        .padding(horizontal = Dimens.MenuHeaderPadding, vertical = Dimens.HeaderBottomPadding)

/** 一行日期。月、日各占两位，个位数靠右；英文月份占十二个月里最宽的那个空位。 */
@Composable
private fun ScrubDateLine(label: ScrubDateLabel, locale: Locale, style: TextStyle, modifier: Modifier) {
    Row(
        modifier.clearAndSetSemantics { contentDescription = scrubDatePhrase(label) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label.chinese) {
            Text(label.year, style = style, maxLines = 1, softWrap = false)
            Text("年", style = style, maxLines = 1, softWrap = false)
            DigitSlot(label.month, style)
            Text("月", style = style, maxLines = 1, softWrap = false)
            DigitSlot(label.day, style)
            Text("日", style = style, maxLines = 1, softWrap = false)
        } else {
            MonthSlot(label.month, locale, style)
            Text(" ", style = style, maxLines = 1, softWrap = false)
            DigitSlot(label.day, style)
            Text(", ", style = style, maxLines = 1, softWrap = false)
            Text(label.year, style = style, maxLines = 1, softWrap = false)
        }
    }
}

/** 两位数字的空位。透明的「00」撑开宽度，可见数字靠右，所以「8」和「28」占的地方一样。 */
@Composable
private fun DigitSlot(value: String, style: TextStyle) {
    Box(contentAlignment = Alignment.CenterEnd) {
        Text(
            "00",
            style = style.copy(color = Color.Transparent),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Text(value, style = style, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun MonthSlot(name: String, locale: Locale, style: TextStyle) {
    val measurer = rememberTextMeasurer()
    val widthPx = remember(locale, style) {
        (1..12).maxOf { month ->
            measurer.measure(DayDisplay.monthName(month, locale), style = style, maxLines = 1).size.width
        }
    }
    Box(Modifier.width(with(LocalDensity.current) { widthPx.toDp() })) {
        Text(name, style = style, maxLines = 1, softWrap = false)
    }
}

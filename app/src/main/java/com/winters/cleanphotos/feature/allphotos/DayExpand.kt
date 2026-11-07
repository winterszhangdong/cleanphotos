package com.winters.cleanphotos.feature.allphotos

import com.winters.cleanphotos.domain.model.PhotoDaySection
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.SectionHeader
import kotlin.math.min

/** 照片页按天展开：网格项的键、拖选顺序、收起后的滚动锚点、日期行吸附位置。 */
internal object DayExpand {
    fun displayedPhotos(section: PhotoDaySection, expanded: Boolean): List<PhotoItem> =
        if (expanded && section.overflow.isNotEmpty()) section.photos + section.overflow else section.photos

    /** 屏幕上的格子，按网格顺序。没展开的天不含 [PhotoDaySection.overflow]。 */
    fun gridPhotos(sections: List<PhotoDaySection>, expandedDayKey: String?): List<PhotoItem> =
        sections.flatMap { displayedPhotos(it, it.dayKey == expandedDayKey) }

    /**
     * 和 [photoDaySections] 同一套项键：年份、日期行、照片 id、未展开时的「还有 N 张」。
     * 用来在展开 / 收起前后对齐滚动位置。
     */
    fun gridKeys(sections: List<PhotoDaySection>, expandedDayKey: String?): List<Any> {
        val keys = ArrayList<Any>()
        for (section in sections) {
            if (section.yearHeader != null) keys += yearKey(section.yearHeader, section.dayKey)
            if (section.header != SectionHeader.None) keys += section.dayKey
            val expanded = section.dayKey == expandedDayKey && section.overflow.isNotEmpty()
            displayedPhotos(section, expanded).forEach { keys += it.id }
            if (!expanded && section.overflow.isNotEmpty() && section.header == SectionHeader.Day) {
                keys += moreKey(section.dayKey)
            }
        }
        return keys
    }

    fun yearKey(year: Int, dayKey: String): String = "year-$year-$dayKey"

    fun moreKey(dayKey: String): String = "more-$dayKey"

    /**
     * 展开或收起之后，把原来视口顶上的那一项对回新列表。
     * 纯收起、而且视口已经滑进这一天的照片时，改回到这一天的日期行。
     * 不需要动滚动时返回 null。
     */
    fun scrollAfterToggle(
        sections: List<PhotoDaySection>,
        previousKey: String?,
        nextKey: String?,
        firstIndex: Int,
        firstOffset: Int,
    ): Pair<Int, Int>? {
        if (previousKey == nextKey) return null
        val oldKeys = gridKeys(sections, previousKey)
        val newKeys = gridKeys(sections, nextKey)
        if (firstIndex !in oldKeys.indices) return null
        if (nextKey == null && previousKey != null) {
            val header = newKeys.indexOf(previousKey)
            if (header >= 0 && firstIndex > header) return header to 0
            return null
        }
        if (oldKeys.take(firstIndex) == newKeys.take(firstIndex)) return null
        val kept = newKeys.indexOf(oldKeys[firstIndex])
        if (kept >= 0) return if (kept == firstIndex) null else kept to firstOffset
        for (i in firstIndex + 1 until oldKeys.size) {
            val index = newKeys.indexOf(oldKeys[i])
            if (index >= 0) return index to 0
        }
        return null
    }
}

/** 顶栏还露在外面的高度。collapseOffset 是 0 到 -chromeHeight。 */
internal fun dayHeaderPinY(statusBarPx: Float, chromeHeightPx: Float, collapseOffsetPx: Float): Float {
    val visibleChrome = (chromeHeightPx + collapseOffsetPx).coerceIn(0f, chromeHeightPx)
    return statusBarPx + visibleChrome
}

/** 格子在网格视口里的顶边。offset 不含 contentPadding，放置时才加 -viewportStartOffset。 */
internal fun gridItemVisualTop(offsetY: Int, viewportStartOffset: Int): Int = offsetY - viewportStartOffset

/**
 * 展开那天的日期行叠层该画在哪。返回 null 表示用列表里的原行，不叠。
 * [headerTop] / [lastItemTop] 是视口坐标；最后一项还没进视口时传 null，表示这一天还没到底。
 */
internal fun dayHeaderStickY(
    pinY: Int,
    headerHeight: Int,
    headerTop: Int?,
    lastItemTop: Int?,
    lastItemHeight: Int?,
    sectionVisible: Boolean,
): Int? {
    if (headerTop == null && !sectionVisible) return null
    if (headerTop != null && headerTop >= pinY) return null
    val end = if (lastItemTop != null && lastItemHeight != null) lastItemTop + lastItemHeight else Int.MAX_VALUE
    val y = min(pinY, end - headerHeight)
    if (y <= -headerHeight) return null
    return y
}

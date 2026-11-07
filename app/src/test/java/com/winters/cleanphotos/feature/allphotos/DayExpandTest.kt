package com.winters.cleanphotos.feature.allphotos

import com.winters.cleanphotos.TestPhotoData
import com.winters.cleanphotos.domain.model.PhotoDaySection
import com.winters.cleanphotos.domain.model.SectionHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DayExpandTest {
    @Test
    fun grid_shows_only_the_expanded_days_overflow() {
        val sections = listOf(day("a", 1..8, 9..10, year = 2024), day("b", 11..12, 13..14))
        assertEquals((1L..8).toList() + (11L..12).toList(), DayExpand.gridPhotos(sections, null).map { it.id })
        assertEquals((1L..10).toList() + (11L..12).toList(), DayExpand.gridPhotos(sections, "a").map { it.id })
        assertEquals((1L..8).toList() + (11L..14).toList(), DayExpand.gridPhotos(sections, "b").map { it.id })
    }

    @Test
    fun keys_match_the_grid_items() {
        val sections = listOf(day("a", 1..2, 3..3, year = 2024), day("b", 4..4))
        assertEquals(
            listOf("year-2024-a", "a", 1L, 2L, "more-a", "b", 4L),
            DayExpand.gridKeys(sections, null),
        )
        assertEquals(
            listOf("year-2024-a", "a", 1L, 2L, 3L, "b", 4L),
            DayExpand.gridKeys(sections, "a"),
        )
        val month = day("2026-10", 1..12, header = SectionHeader.Month)
        assertEquals(listOf("2026-10") + (1L..12).toList(), DayExpand.gridKeys(listOf(month), "2026-10"))
    }

    @Test
    fun opening_a_visible_more_row_does_not_scroll() {
        val sections = listOf(day("a", 1..8, 9..10, year = 2024), day("b", 11..12))
        // 「还有 N 张」自己在视口顶上：前缀没变，展开后第一张被挡住的照片落在同一下标。
        val more = DayExpand.gridKeys(sections, null).indexOf("more-a")
        assertNull(DayExpand.scrollAfterToggle(sections, null, "a", more, 12))
        assertNull(DayExpand.scrollAfterToggle(sections, null, "a", 2, 0))
    }

    @Test
    fun opening_pushes_the_anchor_when_items_above_it_change() {
        val sections = listOf(day("a", 1..8, 9..10, year = 2024), day("b", 11..12))
        val headerB = DayExpand.gridKeys(sections, null).indexOf("b")
        assertEquals(
            DayExpand.gridKeys(sections, "a").indexOf("b") to 4,
            DayExpand.scrollAfterToggle(sections, null, "a", headerB, 4),
        )
    }

    @Test
    fun collapse_returns_to_the_date_row_only_after_it_has_left() {
        val sections = listOf(day("a", 1..8, 9..10, year = 2024), day("b", 11..12))
        val header = DayExpand.gridKeys(sections, null).indexOf("a")
        assertNull(DayExpand.scrollAfterToggle(sections, "a", null, header, 8))
        assertEquals(header to 0, DayExpand.scrollAfterToggle(sections, "a", null, header + 3, 8))
        assertNull(DayExpand.scrollAfterToggle(sections, "a", "a", header + 3, 0))
        assertNull(DayExpand.scrollAfterToggle(sections, "a", null, -1, 0))
    }

    @Test
    fun switching_days_while_overflow_is_at_the_top_lands_on_the_next_row() {
        val sections = listOf(day("a", 1..8, 9..10, year = 2024), day("b", 11..18, 19..20))
        val expanded = DayExpand.gridKeys(sections, "a")
        val overflow = expanded.indexOf(9L)
        val next = DayExpand.scrollAfterToggle(sections, "a", "b", overflow + 1, 6)
        assertEquals(DayExpand.gridKeys(sections, "b").indexOf("b") to 0, next)
    }

    @Test
    fun pin_follows_the_collapsing_chrome_and_stops_at_the_status_bar() {
        assertEquals(100f, dayHeaderPinY(40f, 60f, 0f))
        assertEquals(70f, dayHeaderPinY(40f, 60f, -30f))
        assertEquals(40f, dayHeaderPinY(40f, 60f, -60f))
        assertEquals(40f, dayHeaderPinY(40f, 60f, -80f))
    }

    @Test
    fun sticky_header_hides_until_the_real_row_passes_the_pin() {
        assertNull(dayHeaderStickY(200, 50, headerTop = null, lastItemTop = null, lastItemHeight = null, sectionVisible = false))
        assertNull(dayHeaderStickY(200, 50, headerTop = 200, lastItemTop = null, lastItemHeight = null, sectionVisible = true))
        assertNull(dayHeaderStickY(200, 50, headerTop = 240, lastItemTop = 400, lastItemHeight = 100, sectionVisible = true))
        assertEquals(200, dayHeaderStickY(200, 50, headerTop = null, lastItemTop = null, lastItemHeight = null, sectionVisible = true))
        assertEquals(200, dayHeaderStickY(200, 50, headerTop = 40, lastItemTop = null, lastItemHeight = null, sectionVisible = true))
        // 最后一张已经进视口：日期行跟着它的底边上推。
        assertEquals(150, dayHeaderStickY(200, 50, headerTop = null, lastItemTop = 80, lastItemHeight = 120, sectionVisible = true))
        // 最后一张还露着一截：日期行跟着上移，但还没整行离开。
        assertEquals(-10, dayHeaderStickY(200, 50, headerTop = null, lastItemTop = -40, lastItemHeight = 80, sectionVisible = true))
        // 最后一张的底边已经到视口外：叠层整行离开。
        assertNull(dayHeaderStickY(200, 50, headerTop = null, lastItemTop = -80, lastItemHeight = 50, sectionVisible = true))
    }

    private fun day(
        key: String,
        photos: IntRange,
        overflow: IntRange = IntRange.EMPTY,
        year: Int? = null,
        header: SectionHeader = SectionHeader.Day,
    ): PhotoDaySection {
        fun items(ids: IntRange) = ids.map { TestPhotoData.createTestPhoto(it.toLong(), dateGroup = key) }
        val shown = items(photos)
        val rest = items(overflow)
        return PhotoDaySection(
            dayKey = key,
            date = null,
            yearHeader = year,
            photos = shown,
            totalCount = shown.size + rest.size,
            unseenCount = shown.size + rest.size,
            header = header,
            selectableIds = (shown + rest).map { it.id },
            overflow = rest,
        )
    }
}

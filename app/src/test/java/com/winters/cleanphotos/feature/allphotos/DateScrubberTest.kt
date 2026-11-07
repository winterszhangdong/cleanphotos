package com.winters.cleanphotos.feature.allphotos

import com.winters.cleanphotos.TestPhotoData.createTestPhoto
import com.winters.cleanphotos.domain.model.ScopeLayout
import com.winters.cleanphotos.domain.model.SectionHeader
import com.winters.cleanphotos.domain.usecase.BuildPhotoSectionsUseCase
import org.junit.Assert.assertEquals
import org.junit.Test

class DateScrubberTest {
    private val build = BuildPhotoSectionsUseCase()

    private fun sections() = build(
        listOf(
            createTestPhoto(1, "2024-05-20"),
            createTestPhoto(2, "2024-05-20"),
            createTestPhoto(3, "2024-05-19"),
            createTestPhoto(4, "2023-01-02"),
        ),
        listOf(
            createTestPhoto(1, "2024-05-20"),
            createTestPhoto(2, "2024-05-20"),
            createTestPhoto(3, "2024-05-19"),
            createTestPhoto(4, "2023-01-02"),
        ),
        emptySet(),
        capVisible = false,
    )

    @Test
    fun stops_anchor_on_the_date_row_or_the_first_photo() {
        val sections = sections()
        val grouped = scrubStops(sections, immersive = false)
        assertEquals(listOf("2024-05-20", "2024-05-19", "2023-01-02"), grouped.map { it.dayKey })
        assertEquals(listOf("2024-05-20", "2024-05-19", "2023-01-02"), grouped.map { it.anchorKey })
        assertEquals(listOf(1L, 3L, 4L), scrubStops(sections, immersive = true).map { it.anchorKey })
    }

    @Test
    fun scroll_offset_follows_the_hidden_chrome() {
        assertEquals(0, scrubContentScrollOffset(0f))
        assertEquals(0, scrubContentScrollOffset(12f))
        assertEquals(180, scrubContentScrollOffset(-180f))
        assertEquals(181, scrubContentScrollOffset(-180.6f))
    }

    @Test
    fun flat_sections_have_no_stops() {
        val photos = listOf(createTestPhoto(1), createTestPhoto(2))
        val flat = build(photos, photos, emptySet(), layout = ScopeLayout.Flat)
        assertEquals(SectionHeader.None, flat.single().header)
        assertEquals(emptyList<ScrubStop>(), scrubStops(flat, immersive = false))
    }

    @Test
    fun finger_maps_evenly_across_days() {
        assertEquals(0, scrubDayIndex(0f, 1))
        assertEquals(0, scrubDayIndex(1f, 1))
        assertEquals(0, scrubDayIndex(0.2f, 3))
        assertEquals(1, scrubDayIndex(0.5f, 3))
        assertEquals(2, scrubDayIndex(1f, 3))
        assertEquals(0f, scrubFractionFromTouch(5f, 0f, 100f, 10f), 0f)
        assertEquals(1f, scrubFractionFromTouch(95f, 0f, 100f, 10f), 0f)
    }

    @Test
    fun scroll_fraction_follows_items() {
        assertEquals(0f, scrubFractionForScroll(0, 0, 100, 1), 0f)
        assertEquals(0.55f, scrubFractionForScroll(5, 50, 100, 11), 0.001f)
    }

    @Test
    fun anchor_index_matches_the_grid_item_order() {
        val sections = sections()
        assertEquals(0, indexOfAnchor(sections, false, DayExpand.yearKey(2024, "2024-05-20")))
        assertEquals(1, indexOfAnchor(sections, false, "2024-05-20"))
        assertEquals(3, indexOfAnchor(sections, false, 2L))
        assertEquals(4, indexOfAnchor(sections, false, "2024-05-19"))
        assertEquals(6, indexOfAnchor(sections, false, DayExpand.yearKey(2023, "2023-01-02")))
        assertEquals(8, indexOfAnchor(sections, false, 4L))
        assertEquals(0, indexOfAnchor(sections, true, 1L))
        assertEquals(2, indexOfAnchor(sections, true, 3L))
        assertEquals(3, indexOfAnchor(sections, true, 4L))
        assertEquals(0, indexOfAnchor(sections, false, 99L))
    }

    @Test
    fun item_index_lands_on_the_following_day() {
        val sections = sections()
        assertEquals(0, dayIndexAtItem(sections, false, 0))
        assertEquals(0, dayIndexAtItem(sections, false, 3))
        assertEquals(1, dayIndexAtItem(sections, false, 4))
        assertEquals(2, dayIndexAtItem(sections, false, 6))
        assertEquals(2, dayIndexAtItem(sections, false, 100))
        assertEquals(0, dayIndexAtItem(sections, true, 1))
        assertEquals(1, dayIndexAtItem(sections, true, 2))
    }
}

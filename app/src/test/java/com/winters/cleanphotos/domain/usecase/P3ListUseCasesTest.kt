package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.TestPhotoData.createTestPhoto
import com.winters.cleanphotos.core.util.DayDisplay
import com.winters.cleanphotos.domain.model.AppTrashEntry
import com.winters.cleanphotos.domain.model.PhotoFilterMode
import com.winters.cleanphotos.fake.FakePhotoRepository
import com.winters.cleanphotos.fake.FakeSeenStateRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class P3ListUseCasesTest {
    private val build = BuildPhotoSectionsUseCase()

    @Test
    fun sections_year_header_only_when_year_changes() {
        val photos = listOf(
            createTestPhoto(1, "September 21, 2026"),
            createTestPhoto(2, "September 20, 2026"),
            createTestPhoto(3, "December 31, 2025"),
        )
        val s = build(photos, photos, emptySet())
        assertEquals(listOf(2026, null, 2025), s.map { it.yearHeader })
        assertEquals(LocalDate.of(2026, 9, 21), s[0].date)
    }

    @Test
    fun sections_counts_use_day_pool_and_cap_visible_at_8() {
        val day = (1L..14L).map { createTestPhoto(it, "2026-09-21") }
        val seen = (1L..5L).toSet()
        val unseenOnly = day.filter { it.id !in seen }
        val s = build(unseenOnly, dayPool = day, seenIds = seen).single()
        assertEquals(8, s.photos.size)
        assertEquals(14, s.totalCount)
        assertEquals(9, s.unseenCount)
    }

    @Test
    fun unknown_date_has_no_year_and_no_date() {
        val s = build(listOf(createTestPhoto(1, "Unknown date")), emptyList(), emptySet()).single()
        assertNull(s.date)
        assertNull(s.yearHeader)
    }

    @Test(expected = java.time.format.DateTimeParseException::class)
    fun malformed_day_key_fails() {
        build(listOf(createTestPhoto(1, "21/09/2026")), emptyList(), emptySet())
    }

    @Test
    fun filter_pure_overload_uses_given_seen_ids() {
        val photos = (1L..3L).map { createTestPhoto(it) }
        val f = FilterPhotosUseCase(FakeSeenStateRepository())
        assertEquals(listOf(2L, 3L), f(photos, PhotoFilterMode.UnseenOnly, setOf(1L)).map { it.id })
        assertEquals(3, f(photos, PhotoFilterMode.AllPhotos, setOf(1L)).size)
    }

    @Test
    fun unseen_count_and_mark_seen_go_through_repository() = runTest {
        val repo = FakePhotoRepository().apply { setPhotos((1L..4L).map { createTestPhoto(it) }) }
        val seen = FakeSeenStateRepository(setOf(1L))
        val count = ObserveUnseenCountUseCase(repo, seen)
        assertEquals(3, count().first())
        MarkPhotoSeenUseCase(seen)(2L)
        assertEquals(2, count().first())
        assertEquals(setOf(1L, 2L), ObserveSeenIdsUseCase(seen)().first())
    }

    @Test
    fun selected_bytes_and_moved_at() = runTest {
        val photos = (1L..3L).map { createTestPhoto(it) }
        assertEquals(2 * 1024L * 100, SumPhotoBytesUseCase()(photos, setOf(1L, 3L)))
        val repo = FakePhotoRepository()
        repo.trashEntriesFlow.value = listOf(AppTrashEntry(photoId = 7L, movedToTrashAt = 99L))
        assertEquals(mapOf(7L to 99L), ObserveTrashMovedAtUseCase(repo)().first())
    }

    @Test
    fun day_label_and_relative_moved() {
        val l = DayDisplay.dayLabel(LocalDate.of(2026, 9, 2), java.util.Locale.SIMPLIFIED_CHINESE)
        assertEquals("02", l.dayNumeral)
        assertEquals("9月 · 周三", l.monthLabel)
        val zone = ZoneId.of("Asia/Shanghai")
        val now = LocalDate.of(2026, 9, 23).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val moved = LocalDate.of(2026, 9, 21).atTime(23, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(2, DayDisplay.daysSince(moved, now, zone))
        assertEquals(0, DayDisplay.daysSince(now, now, zone))
    }
}

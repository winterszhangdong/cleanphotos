package com.winters.cleanphotos.domain.usecase

import android.net.Uri
import com.winters.cleanphotos.core.util.FileSizeFormatter
import com.winters.cleanphotos.domain.model.DayCellState
import com.winters.cleanphotos.domain.model.DayKey
import com.winters.cleanphotos.domain.model.MonthDayState
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.ReviewLedger
import com.winters.cleanphotos.domain.model.ReviewOutcome
import com.winters.cleanphotos.fake.FakePhotoRepository
import com.winters.cleanphotos.fake.FakeUserPreferencesRepository
import com.winters.cleanphotos.domain.repository.SeenStateRepository
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class DayReviewUseCasesTest {

    private val sep21 = LocalDate.of(2026, 9, 21)
    private val sep20 = LocalDate.of(2026, 9, 20)
    private val sep18 = LocalDate.of(2026, 9, 18)
    private val today = LocalDate.of(2026, 9, 25)

    private fun photo(id: Long, date: LocalDate, size: Long? = 1_000_000L) = PhotoItem(
        id = id, uri = mockk<Uri>(relaxed = true), displayName = "IMG_$id", dateTaken = 1L,
        dateGroup = DayKey.of(date), size = size, width = 1, height = 1,
    )

    /** 会话：9/21 六张（1..6），9/20 两张（7,8），9/18 一张（9）。 */
    private val session = (1L..6L).map { photo(it, sep21) } + listOf(photo(7, sep20), photo(8, sep20), photo(9, sep18))
    private val ledger0 = ReviewLedger(session)

    private fun ledgerAllTouchedDay21(): ReviewLedger = ledger0
        .recordOutcome(1, ReviewOutcome.Favorited)
        .recordOutcome(2, ReviewOutcome.Trashed)
        .recordOutcome(3, ReviewOutcome.Trashed)
        .markViewed(4).markViewed(5).markViewed(6)

    // —— DayKey ——
    @Test fun dayKey_roundTrip_and_unknown() {
        assertEquals(sep21, DayKey.parse(DayKey.of(sep21)))
        assertEquals("September 21, 2026", DayKey.of(sep21))
        assertNull(DayKey.parse(DayKey.UNKNOWN))
    }

    // —— 进度条 ——
    @Test fun progress_cells_follow_session_order_and_states() {
        val l = ledger0.recordOutcome(1, ReviewOutcome.Favorited).recordOutcome(2, ReviewOutcome.Trashed).markViewed(3)
        val p = BuildSessionDayProgressUseCase()(l, currentPhotoId = 4)
        assertEquals(DayKey.of(sep21), p.dayKey)
        assertEquals(
            listOf(DayCellState.Favorited, DayCellState.Trashed, DayCellState.Kept, DayCellState.Current, DayCellState.Pending, DayCellState.Pending),
            p.cells,
        )
        assertFalse(p.useContinuousBar)
    }

    @Test fun progress_only_contains_current_day() {
        val p = BuildSessionDayProgressUseCase()(ledger0, currentPhotoId = 8)
        assertEquals(listOf(DayCellState.Pending, DayCellState.Current), p.cells)
    }

    @Test fun progress_over_40_uses_continuous_bar() {
        val big = ReviewLedger((1L..41L).map { photo(it, sep21) })
        assertTrue(BuildSessionDayProgressUseCase()(big, 1).useContinuousBar)
        val forty = ReviewLedger((1L..40L).map { photo(it, sep21) })
        assertFalse(BuildSessionDayProgressUseCase()(forty, 1).useContinuousBar)
    }

    @Test fun undo_keeps_photo_viewed() {
        val l = ledger0.recordOutcome(1, ReviewOutcome.Trashed).undoOutcome(1)
        assertEquals(DayCellState.Kept, BuildSessionDayProgressUseCase()(l, 2).cells[0])
    }

    // —— 小结插页触发（决定 2）——
    private val should = ShouldInsertDaySummaryUseCase()

    @Test fun summary_inserted_when_all_four_conditions_hold() {
        assertTrue(should(ledgerAllTouchedDay21(), fromPhotoId = 6, toPhotoId = 7))
    }

    @Test fun summary_not_inserted_when_going_back() {
        val l = ledgerAllTouchedDay21().markViewed(7)
        assertFalse(should(l, fromPhotoId = 7, toPhotoId = 6))
    }

    @Test fun summary_not_inserted_within_same_day() {
        assertFalse(should(ledgerAllTouchedDay21(), fromPhotoId = 5, toPhotoId = 6))
    }

    @Test fun summary_not_inserted_when_some_photo_untouched() {
        val l = ledger0.markViewed(1).markViewed(2).markViewed(3).markViewed(4).markViewed(6) // 5 没看过
        assertFalse(should(l, fromPhotoId = 6, toPhotoId = 7))
    }

    @Test fun summary_not_inserted_for_day_with_fewer_than_6() {
        val l = ledgerAllTouchedDay21().markViewed(7).markViewed(8)
        assertFalse(should(l, fromPhotoId = 8, toPhotoId = 9))
    }

    @Test fun summary_at_most_once_per_day_per_session() {
        val l = ledgerAllTouchedDay21().markSummarized(DayKey.of(sep21))
        assertFalse(should(l, fromPhotoId = 6, toPhotoId = 7))
    }

    @Test fun summary_skipped_photos_count_as_kept_and_trigger() {
        // 全部只是划过（看过未处理）也满足
        var l = ledger0
        (1L..6L).forEach { l = l.markViewed(it) }
        assertTrue(should(l, 6, 7))
    }

    @Test fun summary_triggers_at_session_end_when_last_day_done() {
        val l = ReviewLedger((1L..6L).map { photo(it, sep21) }).let { var x = it; (1L..6L).forEach { id -> x = x.markViewed(id) }; x }
        assertTrue(should(l, fromPhotoId = 6, toPhotoId = null))
    }

    @Test fun summary_never_for_unknown_date() {
        val unknown = (1L..6L).map { photo(it, sep21).copy(dateGroup = DayKey.UNKNOWN) }
        var l = ReviewLedger(unknown)
        (1L..6L).forEach { l = l.markViewed(it) }
        assertFalse(should(l, fromPhotoId = 6, toPhotoId = null))
    }

    @Test fun summary_jump_forward_over_days_counts_leaving_day() {
        assertTrue(should(ledgerAllTouchedDay21(), fromPhotoId = 6, toPhotoId = 9))
    }

    // —— 小结内容 ——
    @Test fun summary_content_counts_bytes_previews_and_next_day() {
        val l = ledgerAllTouchedDay21()
        val library = session + listOf(photo(100, sep20)) // 9/20 相册里还有一张不在会话内
        val s = BuildDaySummaryUseCase(BuildMonthProgressUseCase())(
            ledger = l, dayKey = DayKey.of(sep21), libraryPhotos = library,
            processedIds = setOf(1, 2, 3), seenIds = setOf(7), today = today,
        )
        assertEquals(1, s.favoritedCount); assertEquals(2, s.trashedCount); assertEquals(3, s.keptCount)
        assertEquals(2_000_000L, s.freedBytes)
        assertEquals(6, s.previews.size)
        assertEquals(ReviewOutcome.Favorited, s.previews[0].outcome)
        val next = s.nextDay!!
        assertEquals(DayKey.of(sep20), next.dayKey); assertEquals(7L, next.cover.id); assertEquals(1, next.unseenCount)
        assertEquals(MonthDayState.Highlight, s.month.days[20])
        assertEquals(MonthDayState.Pending, s.month.days[19]) // 9/20 有没看过的
        assertEquals(MonthDayState.Pending, s.month.days[17]) // 9/18 照片 9 没看过
        assertEquals(MonthDayState.Empty, s.month.days[0])
    }

    @Test fun summary_previews_capped_at_8_and_last_day_has_no_next() {
        val ten = ReviewLedger((1L..10L).map { photo(it, sep21) })
        val s = BuildDaySummaryUseCase(BuildMonthProgressUseCase())(ten, DayKey.of(sep21), ten.orderedPhotos, emptySet(), emptySet(), today)
        assertEquals(8, s.previews.size)
        assertNull(s.nextDay)
    }

    // —— 月份条（决定 12，跨会话）——
    @Test fun month_done_uses_persisted_seen_and_processed() {
        val library = listOf(photo(1, sep18), photo(2, sep18), photo(3, sep20), photo(4, LocalDate.of(2026, 9, 28)))
        val m = BuildMonthProgressUseCase()(
            month = YearMonth.of(2026, 9), libraryPhotos = library, processedIds = setOf(1L), seenIds = setOf(2L),
            highlightDate = sep21, today = today,
        )
        assertEquals(30, m.days.size)
        assertEquals(MonthDayState.Done, m.days[17])
        assertEquals(MonthDayState.Pending, m.days[19])
        assertEquals(MonthDayState.Highlight, m.days[20])
        assertEquals(MonthDayState.Empty, m.days[27]) // 还没到的天
        assertEquals(1, m.doneDays); assertEquals(2, m.daysWithPhotos)
    }

    @Test fun month_ignores_unknown_date_and_other_months() {
        val unknown = photo(1, sep18).copy(dateGroup = DayKey.UNKNOWN)
        val m = BuildMonthProgressUseCase()(YearMonth.of(2026, 9), listOf(unknown, photo(2, LocalDate.of(2026, 8, 31))), emptySet(), emptySet(), sep21, today)
        assertEquals(0, m.daysWithPhotos)
    }

    // —— 垃圾桶 / 全部看过了（决定 11）——
    @Test fun trash_summary_and_all_seen_stats() = runTest {
        val repo = FakePhotoRepository()
        repo.setTrashedPhotos(listOf(photo(1, sep21, 3_000_000), photo(2, sep21, null), photo(3, sep20, 1_000_000)))
        repo.setFavoritedPhotos(listOf(photo(4, sep21), photo(5, sep21)))
        val trash = ObserveTrashSummaryUseCase(repo)().first()
        assertEquals(3, trash.count); assertEquals(4_000_000L, trash.totalBytes)
        val stats = ObserveAllSeenStatsUseCase(repo, ObserveTrashSummaryUseCase(repo))().first()
        assertEquals(2, stats.favoriteCount); assertEquals(3, stats.trashCount); assertEquals(4_000_000L, stats.trashBytes)
    }

    @Test fun library_day_state_combines_visible_favorites_trash_and_seen() = runTest {
        val repo = FakePhotoRepository()
        repo.setPhotos(listOf(photo(1, sep21)))
        repo.setFavoritedPhotos(listOf(photo(2, sep21)))
        repo.setTrashedPhotos(listOf(photo(3, sep21)))
        val seen = com.winters.cleanphotos.fake.FakeSeenStateRepository(setOf(1L))
        val state = ObserveLibraryDayStateUseCase(repo, seen)().first()
        assertEquals(setOf(1L, 2L, 3L), state.libraryPhotos.map { it.id }.toSet())
        assertEquals(setOf(2L, 3L), state.processedIds)
        assertEquals(setOf(1L), state.seenIds)
    }

    // —— 方向提示小点（决定 10）——
    @Test fun direction_hints_hidden_after_20_actions() = runTest {
        val prefs = FakeUserPreferencesRepository()
        val show = ObserveShowDirectionHintsUseCase(prefs)
        val record = RecordSwipeActionUseCase(prefs)
        repeat(19) { record() }
        assertTrue(show().first())
        record()
        assertFalse(show().first())
    }

    // —— 文件大小文案 ——
    @Test fun file_size_format_matches_draft() {
        assertEquals("3.1 MB", FileSizeFormatter.format((3.1 * 1024 * 1024).toLong()))
        assertEquals("31 MB", FileSizeFormatter.format(31L * 1024 * 1024))
        assertEquals("1.2 GB", FileSizeFormatter.format((1.2 * 1024 * 1024 * 1024).toLong()))
        assertEquals("0.0 KB", FileSizeFormatter.format(0))
        // 进位按四舍五入后的值判断
        assertEquals("1.0 MB", FileSizeFormatter.format(1024L * 1024 - 1))
        assertEquals("10 MB", FileSizeFormatter.format((9.97 * 1024 * 1024).toLong()))
    }

    @Test(expected = IllegalStateException::class) fun ledger_unknown_photo_fails_fast() {
        ledger0.markViewed(999)
    }
}

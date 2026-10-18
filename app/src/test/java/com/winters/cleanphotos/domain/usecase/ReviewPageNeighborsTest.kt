package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.TestPhotoData.createTestPhoto
import com.winters.cleanphotos.domain.model.ReviewLedger
import com.winters.cleanphotos.domain.model.ReviewOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/** 清理页滑动只取相邻页（A-D13）：结果必须和整条序列里取 index±1 完全一致。 */
class ReviewPageNeighborsTest {
    private val useCase = BuildReviewPagesUseCase(ShouldInsertDaySummaryUseCase())

    // 4 天，每天 5~8 张（小结门槛 6 张，两种情况都覆盖）
    private val photos = run {
        var id = 0L
        listOf("2024-05-23" to 7, "2024-05-22" to 5, "2024-05-21" to 8, "2024-05-20" to 6).flatMap { (day, n) ->
            (1..n).map { createTestPhoto(id = ++id, dateGroup = day) }
        }
    }

    @Test
    fun `随机场景下相邻页与整条序列一致`() {
        val rnd = Random(42)
        val days = photos.map { it.dateGroup }.distinct()
        repeat(400) {
            var ledger = ReviewLedger(photos)
            photos.forEach { p ->
                when (rnd.nextInt(4)) {
                    0 -> ledger = ledger.markViewed(p.id)
                    1 -> ledger = ledger.recordOutcome(p.id, ReviewOutcome.Trashed)
                    2 -> if (rnd.nextBoolean()) ledger = ledger.markViewed(p.id)
                }
            }
            if (rnd.nextInt(3) == 0) ledger = ledger.markSummarized(days.random(rnd))
            val removed = photos.filter { rnd.nextInt(5) == 0 }.map { it.id }.toSet()
            val current = if (rnd.nextInt(6) == 0) null else photos.random(rnd).id
            val active = if (rnd.nextInt(4) == 0) days.random(rnd) else null

            val pages = useCase(ledger, removed, current, active)
            photos.forEach { p ->
                val n = useCase.neighborsOf(ledger, removed, current, active, p.id)
                val i = pages.indexOfFirst { it.key == "p${p.id}" }
                if (i < 0) {
                    assertNull(n)
                } else {
                    val ctx = "photo=${p.id} removed=$removed current=$current active=$active"
                    assertEquals(ctx, pages.getOrNull(i - 1), n!!.previous)
                    assertEquals(ctx, pages.getOrNull(i + 1), n.next)
                }
            }
        }
    }

    @Test
    fun `不在会话中的照片返回 null`() {
        assertNull(useCase.neighborsOf(ReviewLedger(photos), emptySet(), null, null, 999L))
    }
}

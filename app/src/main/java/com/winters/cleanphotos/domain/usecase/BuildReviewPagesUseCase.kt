package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.ReviewLedger
import com.winters.cleanphotos.domain.model.ReviewPage
import javax.inject.Inject

/**
 * 清理页翻页序列：会话初始顺序里还没被移走的照片，加上小结插页（决定 2）。
 *
 * 小结插页的位置：
 * - 正在看的小结（activeSummaryDay）：放在这一天之后、下一组日期的第一张之前；
 * - 预判：当前是一张照片，并且「离开它往后走」就会满足小结条件（把它记为看过后调用
 *   [ShouldInsertDaySummaryUseCase]），就把这一天的小结提前放在它后面。
 *   这样拖动中垫在下面 / 侧边的就是小结，UI 不需要等 ViewModel 决定。
 * 往回滑不触发；一天在会话里最多出现一次（看过的小结不再预判插入）。
 */
class BuildReviewPagesUseCase @Inject constructor(
    private val shouldInsertDaySummary: ShouldInsertDaySummaryUseCase,
) {
    operator fun invoke(
        ledger: ReviewLedger,
        removedIds: Set<Long>,
        currentPhotoId: Long?,
        activeSummaryDay: String?,
    ): List<ReviewPage> {
        val remaining = ledger.orderedPhotos.filter { it.id !in removedIds }
        val pages: MutableList<ReviewPage> = remaining.mapTo(mutableListOf()) { ReviewPage.Photo(it) }
        if (activeSummaryDay != null) {
            val lastOfDay = ledger.orderedPhotos.indexOfLast { it.dateGroup == activeSummaryDay }
            val at = remaining.indexOfFirst { ledger.indexOf(it.id) > lastOfDay }.let { if (it < 0) pages.size else it }
            pages.add(at, ReviewPage.Summary(activeSummaryDay))
            return pages
        }
        if (currentPhotoId != null && currentPhotoId !in removedIds) {
            val at = remaining.indexOfFirst { it.id == currentPhotoId }
            if (at >= 0) {
                val next = remaining.getOrNull(at + 1)
                val lookahead = ledger.markViewed(currentPhotoId)
                if (shouldInsertDaySummary(lookahead, currentPhotoId, next?.id)) {
                    pages.add(at + 1, ReviewPage.Summary(ledger.photo(currentPhotoId).dateGroup))
                }
            }
        }
        return pages
    }
}

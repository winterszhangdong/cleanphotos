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

    /**
     * [photoId] 在 [invoke] 结果里的前一页和后一页，不生成整条序列（清理页每次滑动在主线程调用）。
     * 返回 null 表示它不在序列里。结果与 `invoke(...)` 里取 index±1 完全一致（见单测）。
     */
    fun neighborsOf(
        ledger: ReviewLedger,
        removedIds: Set<Long>,
        currentPhotoId: Long?,
        activeSummaryDay: String?,
        photoId: Long,
    ): Neighbors? {
        if (photoId in removedIds) return null
        val i = ledger.indexOrNull(photoId) ?: return null
        val photos = ledger.orderedPhotos
        val nextPhoto = (i + 1 until photos.size).firstOrNull { photos[it].id !in removedIds }
        val prevPhoto = (i - 1 downTo 0).firstOrNull { photos[it].id !in removedIds }
        fun photoPage(j: Int?) = j?.let { ReviewPage.Photo(photos[it]) }

        if (activeSummaryDay != null) {
            val lastOfDay = photos.indexOfLast { it.dateGroup == activeSummaryDay }
            // 小结插在「会话索引 > lastOfDay 的第一张剩余照片」之前（没有就放在最后）
            val summary = ReviewPage.Summary(activeSummaryDay)
            val next = if (i <= lastOfDay && (nextPhoto == null || nextPhoto > lastOfDay)) summary else photoPage(nextPhoto)
            val prev = if (i > lastOfDay && (prevPhoto == null || prevPhoto <= lastOfDay)) summary else photoPage(prevPhoto)
            return Neighbors(prev, next)
        }
        var next: ReviewPage? = photoPage(nextPhoto)
        var prev: ReviewPage? = photoPage(prevPhoto)
        if (currentPhotoId != null && currentPhotoId !in removedIds) {
            val c = ledger.indexOrNull(currentPhotoId)
            if (c != null) {
                val afterCurrent = (c + 1 until photos.size).firstOrNull { photos[it].id !in removedIds }
                val insert = (c == i || afterCurrent == i) && shouldInsertDaySummary(
                    ledger.markViewed(currentPhotoId), currentPhotoId, afterCurrent?.let { photos[it].id },
                )
                if (insert) {
                    val summary = ReviewPage.Summary(photos[c].dateGroup)
                    if (c == i) next = summary else prev = summary
                }
            }
        }
        return Neighbors(prev, next)
    }

    data class Neighbors(val previous: ReviewPage?, val next: ReviewPage?)
}

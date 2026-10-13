package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.DayKey
import com.winters.cleanphotos.domain.model.DaySummary
import com.winters.cleanphotos.domain.model.NextDayInfo
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.ReviewLedger
import com.winters.cleanphotos.domain.model.ReviewOutcome
import com.winters.cleanphotos.domain.model.SummaryPreview
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

/**
 * 小结插页内容：收藏 / 移走 / 保留 / 可释放、前 8 张缩略条、本月每天进度、下一天。
 * 「下一天」= 会话顺序里的下一组日期，未看数按持久化看过状态计算。
 */
class BuildDaySummaryUseCase @Inject constructor(
    private val buildMonthProgress: BuildMonthProgressUseCase,
) {
    operator fun invoke(
        ledger: ReviewLedger,
        dayKey: String,
        libraryPhotos: List<PhotoItem>,
        processedIds: Set<Long>,
        seenIds: Set<Long>,
        today: LocalDate,
    ): DaySummary {
        val date = DayKey.parse(dayKey) ?: error("没有日期的照片不生成小结")
        val photos = ledger.photosOfDay(dayKey)
        require(photos.isNotEmpty()) { "会话中没有 $dayKey 的照片" }
        val outcomes = photos.map { ledger.outcomes[it.id] }
        val trashed = photos.filter { ledger.outcomes[it.id] == ReviewOutcome.Trashed }
        val days = ledger.dayKeysInOrder
        val nextKey = days.getOrNull(days.indexOf(dayKey) + 1)
        val nextDay = nextKey?.let { key ->
            val nextPhotos = ledger.photosOfDay(key)
            NextDayInfo(
                dayKey = key,
                cover = nextPhotos.first(),
                unseenCount = nextPhotos.count { it.id !in seenIds && it.id !in ledger.viewedIds },
            )
        }
        return DaySummary(
            dayKey = dayKey,
            date = date,
            favoritedCount = outcomes.count { it == ReviewOutcome.Favorited },
            unfavoritedCount = outcomes.count { it == ReviewOutcome.Unfavorited },
            trashedCount = trashed.size,
            keptCount = outcomes.count { it == null },
            freedBytes = trashed.sumOf { it.size ?: 0L },
            previews = photos.take(PREVIEW_COUNT).map { SummaryPreview(it, ledger.outcomes[it.id]) },
            month = buildMonthProgress(
                month = YearMonth.from(date),
                libraryPhotos = libraryPhotos,
                processedIds = processedIds,
                seenIds = seenIds + ledger.viewedIds,
                highlightDate = date,
                today = today,
            ),
            nextDay = nextDay,
        )
    }

    companion object {
        const val PREVIEW_COUNT = 8
    }
}

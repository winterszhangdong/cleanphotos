package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.DayKey
import com.winters.cleanphotos.domain.model.MonthDayState
import com.winters.cleanphotos.domain.model.MonthProgress
import com.winters.cleanphotos.domain.model.PhotoItem
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

/**
 * 决定 12：月份条「整理完的天」跨会话计算——这一天相册里的照片（含已收藏、已在垃圾桶）
 * 全部看过或处理过即算 Done。收藏 / 垃圾桶里的照片本身就是处理过的。
 *
 * @param libraryPhotos 相册全部照片：照片页可见 + 收藏 + 垃圾桶
 * @param processedIds 已收藏或在垃圾桶中的照片 id
 * @param seenIds 持久化的看过状态
 * @param highlightDate 小结对应的那一天（发光）
 * @param today 今天；晚于今天的日子是 Empty
 */
class BuildMonthProgressUseCase @Inject constructor() {
    operator fun invoke(
        month: YearMonth,
        libraryPhotos: List<PhotoItem>,
        processedIds: Set<Long>,
        seenIds: Set<Long>,
        highlightDate: LocalDate,
        today: LocalDate,
    ): MonthProgress {
        val byDate: Map<LocalDate, List<PhotoItem>> = libraryPhotos
            .mapNotNull { p -> DayKey.parse(p.dateGroup)?.let { it to p } }
            .filter { YearMonth.from(it.first) == month }
            .groupBy({ it.first }, { it.second })
        val states = (1..month.lengthOfMonth()).map { d ->
            val date = month.atDay(d)
            val photos = byDate[date].orEmpty()
            when {
                date == highlightDate -> MonthDayState.Highlight
                photos.isEmpty() || date.isAfter(today) -> MonthDayState.Empty
                photos.all { it.id in seenIds || it.id in processedIds } -> MonthDayState.Done
                else -> MonthDayState.Pending
            }
        }
        val doneDays = byDate.count { (date, photos) ->
            !date.isAfter(today) && photos.all { it.id in seenIds || it.id in processedIds }
        }
        return MonthProgress(
            month = month,
            days = states,
            doneDays = doneDays,
            daysWithPhotos = byDate.keys.count { !it.isAfter(today) },
        )
    }
}

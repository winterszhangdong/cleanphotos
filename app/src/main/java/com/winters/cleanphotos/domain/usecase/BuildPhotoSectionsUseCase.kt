package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.DayKey
import com.winters.cleanphotos.domain.model.PhotoDaySection
import com.winters.cleanphotos.domain.model.PhotoItem
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Inject

/**
 * 把已排序、已筛选的照片切成「年份分隔 + 天」列表。
 *
 * @param visible 要显示的照片（顺序即显示顺序）。
 * @param dayPool 统计每天总数 / 未看数的照片池（照片页传当前范围、未叠「只看没看过」的照片，开关开着时数字仍按范围内整天算）。
 * @param seenIds 看过的照片 id。
 */
class BuildPhotoSectionsUseCase @Inject constructor() {
    operator fun invoke(
        visible: List<PhotoItem>,
        dayPool: List<PhotoItem>,
        seenIds: Set<Long>,
    ): List<PhotoDaySection> {
        val poolByDay = dayPool.groupBy { it.dateGroup }
        var lastYear: Int? = null
        return visible.groupBy { it.dateGroup }.map { (key, photos) ->
            val date = parseDay(key)
            val year = date?.year
            val header = if (year != null && year != lastYear) year else null
            if (year != null) lastYear = year
            val pool = poolByDay[key] ?: photos
            PhotoDaySection(
                dayKey = key,
                date = date,
                yearHeader = header,
                photos = photos.take(PhotoDaySection.MAX_VISIBLE_PHOTOS),
                totalCount = pool.size,
                unseenCount = pool.count { it.id !in seenIds },
            )
        }
    }

    /** 生产数据是 DayKey（"MMMM dd, yyyy"），测试数据是 ISO yyyy-MM-dd；两者以外直接抛错。 */
    private fun parseDay(key: String): LocalDate? {
        if (key == DayKey.UNKNOWN) return null
        return try {
            DayKey.parse(key)
        } catch (_: DateTimeParseException) {
            LocalDate.parse(key, DateTimeFormatter.ISO_LOCAL_DATE)
        }
    }
}

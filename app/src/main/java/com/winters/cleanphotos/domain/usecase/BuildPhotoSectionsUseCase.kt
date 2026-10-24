package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.DayKey
import com.winters.cleanphotos.domain.model.PhotoDaySection
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.ScopeLayout
import com.winters.cleanphotos.domain.model.SectionHeader
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.inject.Inject

/**
 * 把已排序、已筛选的照片切成网格分组。
 *
 * @param visible 要显示的照片（顺序即显示顺序；已经叠过「只看没看过」）。
 * @param dayPool 统计每组总数 / 未看数的照片池（照片页传当前范围、未叠「只看没看过」的照片）。
 * @param seenIds 看过的照片 id。
 * @param layout 按天（每天最多 8 张）、按月或不分组平铺。默认按天，收藏页沿用。
 */
class BuildPhotoSectionsUseCase @Inject constructor() {
    operator fun invoke(
        visible: List<PhotoItem>,
        dayPool: List<PhotoItem>,
        seenIds: Set<Long>,
        layout: ScopeLayout = ScopeLayout.Day,
    ): List<PhotoDaySection> {
        val keyOf = keyOf(layout)
        val poolByKey = dayPool.groupBy(keyOf)
        val cap = if (layout == ScopeLayout.Day) PhotoDaySection.MAX_VISIBLE_PHOTOS else Int.MAX_VALUE
        val header = when (layout) {
            ScopeLayout.Day -> SectionHeader.Day
            ScopeLayout.Month -> SectionHeader.Month
            ScopeLayout.Flat -> SectionHeader.None
        }
        var lastYear: Int? = null
        return visible.groupBy(keyOf).map { (key, members) ->
            val date = dateOf(key, layout)
            val year = date?.year
            val yearHeader = if (year != null && year != lastYear) year else null
            if (year != null) lastYear = year
            val pool = poolByKey[key] ?: members
            PhotoDaySection(
                dayKey = key,
                date = date,
                yearHeader = yearHeader,
                photos = members.take(cap),
                totalCount = pool.size,
                unseenCount = pool.count { it.id !in seenIds },
                header = header,
                selectableIds = members.map { it.id },
            )
        }
    }

    private fun keyOf(layout: ScopeLayout): (PhotoItem) -> String = when (layout) {
        ScopeLayout.Day -> PhotoItem::dateGroup
        ScopeLayout.Month -> ::monthKey
        ScopeLayout.Flat -> { _ -> FLAT_KEY }
    }

    private fun monthKey(photo: PhotoItem): String {
        val date = parseDay(photo.dateGroup) ?: return DayKey.UNKNOWN
        return String.format(Locale.US, "%04d-%02d", date.year, date.monthValue)
    }

    private fun dateOf(key: String, layout: ScopeLayout): LocalDate? = when (layout) {
        ScopeLayout.Day -> parseDay(key)
        ScopeLayout.Month -> monthDate(key)
        ScopeLayout.Flat -> null
    }

    private fun monthDate(key: String): LocalDate? {
        if (key == DayKey.UNKNOWN) return null
        val parts = key.split("-")
        check(parts.size == 2) { "月份键不对：$key" }
        return LocalDate.of(parts[0].toInt(), parts[1].toInt(), 1)
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

    private companion object {
        const val FLAT_KEY = "flat"
    }
}

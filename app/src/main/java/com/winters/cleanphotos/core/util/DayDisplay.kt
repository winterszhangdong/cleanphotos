package com.winters.cleanphotos.core.util

import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoSortOrder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 一天的标题拆成两段，对应界面里的大号日期数字和灰色「M月」。
 * 年份不出现在界面上。
 */
data class DayHeader(
    val dayNumeral: String,
    val monthLabel: String,
)

/**
 * 日期分组的展示规则。分组数据本身仍保留当天全部照片，界面只拿这里的结果来画。
 */
object DayDisplay {
    /** 每个日期分组最多露出的张数。 */
    const val MAX_VISIBLE_PHOTOS = 8

    /**
     * 当前顺序下这一天要画出来的缩略图。超过 8 张时只取前 8 张，不另做溢出入口。
     */
    fun visibleDayPhotos(photos: List<PhotoItem>): List<PhotoItem> {
        return photos.take(MAX_VISIBLE_PHOTOS)
    }

    fun dayHeader(timestampMillis: Long, zone: ZoneId = ZoneId.systemDefault()): DayHeader {
        val date = Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalDate()
        return headerOf(date)
    }

    /** 清理页居中日期，例如 9月21日。 */
    fun cleanPageDate(timestampMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val date = Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalDate()
        return cleanDateOf(date)
    }

    fun cleanPageDateFor(photo: PhotoItem): String {
        val timestamp = photo.dateTaken
        if (timestamp != null && timestamp > 0L) {
            return cleanPageDate(timestamp)
        }
        val header = dayHeaderFromKey(photo.dateGroup)
        return header.monthLabel + header.dayNumeral + "日"
    }

    /**
     * 解析分组键。生产数据是英文全月名，测试数据是 yyyy-MM-dd。
     * 两种以外直接失败，避免画出错误日期。
     */
    fun dayHeaderFromKey(dateKey: String): DayHeader {
        val iso = runCatching {
            LocalDate.parse(dateKey, DateTimeFormatter.ISO_LOCAL_DATE)
        }.getOrNull()
        if (iso != null) {
            return headerOf(iso)
        }
        val us = runCatching {
            LocalDate.parse(dateKey, DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US))
        }.getOrNull()
        if (us != null) {
            return headerOf(us)
        }
        throw IllegalArgumentException("无法解析日期分组：$dateKey")
    }

    /** 照片页排序菜单文案。最早优先对应 OldestFirst。 */
    fun sortLabel(order: PhotoSortOrder): String {
        return when (order) {
            PhotoSortOrder.NewestFirst -> "最新优先"
            PhotoSortOrder.OldestFirst -> "最早优先"
        }
    }

    private fun headerOf(date: LocalDate): DayHeader {
        return DayHeader(
            dayNumeral = date.dayOfMonth.toString(),
            monthLabel = "${date.monthValue}月",
        )
    }

    private fun cleanDateOf(date: LocalDate): String {
        return "${date.monthValue}月${date.dayOfMonth}日"
    }

    private val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** 天标题：两位数日期「02」+「9月 · 周三」。 */
    fun dayLabel(date: LocalDate): DayHeader = DayHeader(
        dayNumeral = String.format(Locale.US, "%02d", date.dayOfMonth),
        monthLabel = "${date.monthValue}月 · ${weekdays[date.dayOfWeek.value - 1]}",
    )

    /** 无拍摄日期的那一组。 */
    val unknownDayLabel = DayHeader(dayNumeral = "—", monthLabel = "未知日期")

    /** 「9月21日拍摄 · 2天前移入」。 */
    fun trashPreviewDates(photo: PhotoItem, movedAt: Long?, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val taken = photo.dateTaken?.takeIf { it > 0L }?.let { cleanPageDate(it, zone) + "拍摄" }
        val moved = movedAt?.let { relativeMoved(it, now, zone) }
        return listOfNotNull(taken, moved).joinToString(" · ")
    }

    /** 「今天移入」「N天前移入」（按自然日）。 */
    fun relativeMoved(movedAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val a = Instant.ofEpochMilli(movedAt).atZone(zone).toLocalDate()
        val b = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val days = java.time.temporal.ChronoUnit.DAYS.between(a, b).coerceAtLeast(0)
        return when (days) {
            0L -> "今天移入"
            else -> "${days}天前移入"
        }
    }

    /** 日期下一行：「周一 · 07:42」；没有拍摄时间只写星期（分组键可解析时）。 */
    fun reviewMeta(photo: PhotoItem, zone: ZoneId = ZoneId.systemDefault()): String {
        val ts = photo.dateTaken?.takeIf { it > 0L }
        if (ts != null) {
            val t = Instant.ofEpochMilli(ts).atZone(zone)
            return weekdays[t.dayOfWeek.value - 1] + " · " + String.format(Locale.US, "%02d:%02d", t.hour, t.minute)
        }
        val date = com.winters.cleanphotos.domain.model.DayKey.parse(photo.dateGroup) ?: return ""
        return weekdays[date.dayOfWeek.value - 1]
    }

    /** 清理页日期里程表的排序值：拍摄日（epochDay），无日期为 Long.MIN_VALUE。 */
    fun reviewDayOrdinal(photo: PhotoItem, zone: ZoneId = ZoneId.systemDefault()): Long {
        val ts = photo.dateTaken?.takeIf { it > 0L }
        if (ts != null) return Instant.ofEpochMilli(ts).atZone(zone).toLocalDate().toEpochDay()
        return com.winters.cleanphotos.domain.model.DayKey.parse(photo.dateGroup)?.toEpochDay() ?: Long.MIN_VALUE
    }

    /** epochDay → 「9月21日」。 */
    fun cleanDateOfEpochDay(epochDay: Long): String =
        if (epochDay == Long.MIN_VALUE) "未知日期" else cleanDateOf(LocalDate.ofEpochDay(epochDay))

    /** 小结眉题：「9月21日 · 周一」。 */
    fun summaryEyebrow(date: LocalDate): String = cleanDateOf(date) + " · " + weekdays[date.dayOfWeek.value - 1]

    /** 小结插页「下一天 · 9月20日」。 */
    fun cleanDate(date: LocalDate): String = cleanDateOf(date)
}

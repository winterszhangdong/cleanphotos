package com.winters.cleanphotos.core.util

import com.winters.cleanphotos.domain.model.PhotoItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 一天的标题拆成两段，对应界面里的大号日期数字和灰色「M月」（英文「Sep」）。
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

    fun dayHeader(timestampMillis: Long, zone: ZoneId, locale: Locale): DayHeader {
        val date = Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalDate()
        return headerOf(date, locale)
    }

    /** 清理页居中日期：中文「9月21日」，英文「Sep 21」。 */
    fun cleanPageDate(timestampMillis: Long, zone: ZoneId, locale: Locale): String {
        val date = Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalDate()
        return cleanDateOf(date, locale)
    }

    /** 照片的拍摄日（没有拍摄时间时用分组键），无法解析返回 null。 */
    fun photoDate(photo: PhotoItem, zone: ZoneId = ZoneId.systemDefault()): LocalDate? {
        val timestamp = photo.dateTaken
        if (timestamp != null && timestamp > 0L) {
            return Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        }
        return parseKey(photo.dateGroup)
    }

    fun cleanPageDateFor(photo: PhotoItem, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String {
        val date = photoDate(photo, zone) ?: throw IllegalArgumentException("无法解析日期分组：${photo.dateGroup}")
        return cleanDateOf(date, locale)
    }

    /**
     * 解析分组键。生产数据是英文全月名，测试数据是 yyyy-MM-dd。
     * 两种以外直接失败，避免画出错误日期。
     */
    fun dayHeaderFromKey(dateKey: String, locale: Locale): DayHeader {
        val date = parseKey(dateKey) ?: throw IllegalArgumentException("无法解析日期分组：$dateKey")
        return headerOf(date, locale)
    }

    private fun parseKey(dateKey: String): LocalDate? {
        runCatching { LocalDate.parse(dateKey, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()?.let { return it }
        return runCatching {
            LocalDate.parse(dateKey, DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US))
        }.getOrNull()
    }

    private fun isChinese(locale: Locale) = locale.language == "zh"

    /** 月份短名：中文「9月」，其他语言用系统本地化的缩写（英文「Sep」）。 */
    private fun monthOf(date: LocalDate, locale: Locale): String =
        if (isChinese(locale)) "${date.monthValue}月" else date.month.getDisplayName(TextStyle.SHORT, locale)

    /** 星期短名：中文「周一」，其他语言用系统本地化的缩写（英文「Mon」）。 */
    fun weekdayOf(date: LocalDate, locale: Locale): String =
        if (isChinese(locale)) weekdays[date.dayOfWeek.value - 1] else date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)

    /** 月份标题（小结页月历）：中文「9月」，英文「Sep」。 */
    fun monthName(monthValue: Int, locale: Locale): String = monthOf(LocalDate.of(2000, monthValue, 1), locale)

    private fun headerOf(date: LocalDate, locale: Locale): DayHeader {
        return DayHeader(
            dayNumeral = date.dayOfMonth.toString(),
            monthLabel = monthOf(date, locale),
        )
    }

    private fun cleanDateOf(date: LocalDate, locale: Locale): String {
        return if (isChinese(locale)) "${date.monthValue}月${date.dayOfMonth}日" else "${monthOf(date, locale)} ${date.dayOfMonth}"
    }

    private val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** 天标题：两位数日期「02」+「9月 · 周三」/「Sep · Wed」。 */
    fun dayLabel(date: LocalDate, locale: Locale): DayHeader = DayHeader(
        dayNumeral = String.format(Locale.US, "%02d", date.dayOfMonth),
        monthLabel = "${monthOf(date, locale)} · ${weekdayOf(date, locale)}",
    )

    /** 无拍摄日期的那一组，[label] 是「未知日期」的本地化文案。 */
    fun unknownDayLabel(label: String) = DayHeader(dayNumeral = "—", monthLabel = label)

    /** 移入垃圾桶至今的自然日数（「今天移入」「N天前移入」由界面按资源拼）。 */
    fun daysSince(movedAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        val a = Instant.ofEpochMilli(movedAt).atZone(zone).toLocalDate()
        val b = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return java.time.temporal.ChronoUnit.DAYS.between(a, b).coerceAtLeast(0).toInt()
    }

    /** 日期下一行：「周一 · 07:42」；没有拍摄时间只写星期（分组键可解析时）。 */
    fun reviewMeta(photo: PhotoItem, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String {
        val ts = photo.dateTaken?.takeIf { it > 0L }
        if (ts != null) {
            val t = Instant.ofEpochMilli(ts).atZone(zone)
            return weekdayOf(t.toLocalDate(), locale) + " · " + String.format(Locale.US, "%02d:%02d", t.hour, t.minute)
        }
        val date = com.winters.cleanphotos.domain.model.DayKey.parse(photo.dateGroup) ?: return ""
        return weekdayOf(date, locale)
    }

    /** 清理页日期里程表的排序值：拍摄日（epochDay），无日期为 Long.MIN_VALUE。 */
    fun reviewDayOrdinal(photo: PhotoItem, zone: ZoneId = ZoneId.systemDefault()): Long {
        val ts = photo.dateTaken?.takeIf { it > 0L }
        if (ts != null) return Instant.ofEpochMilli(ts).atZone(zone).toLocalDate().toEpochDay()
        return com.winters.cleanphotos.domain.model.DayKey.parse(photo.dateGroup)?.toEpochDay() ?: Long.MIN_VALUE
    }

    /** epochDay → 「9月21日」/「Sep 21」；无日期（Long.MIN_VALUE）返回 null，由界面写「未知日期」。 */
    fun cleanDateOfEpochDay(epochDay: Long, locale: Locale): String? =
        if (epochDay == Long.MIN_VALUE) null else cleanDateOf(LocalDate.ofEpochDay(epochDay), locale)

    /** 小结眉题：「9月21日 · 周一」/「Sep 21 · Mon」。 */
    fun summaryEyebrow(date: LocalDate, locale: Locale): String = cleanDateOf(date, locale) + " · " + weekdayOf(date, locale)

    /** 小结插页「下一天 · 9月20日」里的日期。 */
    fun cleanDate(date: LocalDate, locale: Locale): String = cleanDateOf(date, locale)
}

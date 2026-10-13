package com.winters.cleanphotos.domain.model

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * PhotoItem.dateGroup 是 DayKey.of 产出的 "MMMM dd, yyyy"（Locale.US）文本，是全 App 统一的「天」键。
 * 没有任何时间信息的照片是 "Unknown date"，它不属于任何具体日期。
 */
object DayKey {
    const val UNKNOWN = "Unknown date"
    private val format = DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.US)

    fun of(date: LocalDate): String = date.format(format)

    /** UNKNOWN 返回 null；其他格式不对直接抛错。 */
    fun parse(key: String): LocalDate? = if (key == UNKNOWN) null else LocalDate.parse(key, format)
}

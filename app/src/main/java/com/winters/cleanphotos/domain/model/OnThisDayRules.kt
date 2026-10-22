package com.winters.cleanphotos.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「那年今天」：本地月日与今天相同，且年份更早。图片和视频都算。
 * 2 月 29 日只有今天也是 2 月 29 日才会月日相等，非闰年对不上。
 * 时区用 [ZoneId.systemDefault]，与照片按天分组一致。
 */
object OnThisDayRules {
    fun isOnThisDay(
        photo: PhotoItem,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        val taken = photo.dateTaken ?: return false
        if (taken <= 0L) return false
        val date = Instant.ofEpochMilli(taken).atZone(zone).toLocalDate()
        return date.monthValue == today.monthValue &&
            date.dayOfMonth == today.dayOfMonth &&
            date.year < today.year
    }
}

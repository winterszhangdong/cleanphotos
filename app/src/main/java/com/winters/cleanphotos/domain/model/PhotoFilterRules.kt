package com.winters.cleanphotos.domain.model

import java.time.LocalDate

/**
 * 范围判定的唯一入口。关键字、10MB、QQ 前缀、2 月 29 日仍在原来的规则对象里。
 */
object PhotoFilterRules {
    fun matches(photo: PhotoItem, mode: PhotoFilterMode, today: LocalDate): Boolean = when (mode) {
        PhotoFilterMode.AllPhotos -> true
        PhotoFilterMode.Screenshots -> SmartFilterRules.isScreenshot(photo)
        PhotoFilterMode.LargeFiles -> SmartFilterRules.isLargeFile(photo)
        PhotoFilterMode.ChatApps -> SmartFilterRules.isChatApp(photo)
        PhotoFilterMode.OnThisDay -> OnThisDayRules.isOnThisDay(photo, today)
    }
}

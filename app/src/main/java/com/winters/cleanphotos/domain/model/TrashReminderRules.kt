package com.winters.cleanphotos.domain.model

/**
 * 垃圾桶里「放了多久」的阈值。
 * 照片页提醒已经去掉；垃圾桶页用 [AGE_THRESHOLD_MS] 判断最早一张是否够久。
 */
object TrashReminderRules {
    const val AGE_THRESHOLD_MS = 30L * 24 * 60 * 60 * 1000
}

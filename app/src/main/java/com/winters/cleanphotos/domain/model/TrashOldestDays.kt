package com.winters.cleanphotos.domain.model

/**
 * 「清空后可释放」卡片上，最早一张已经放了几天。
 *
 * 一天按 24 小时计，和 [TrashReminderRules.AGE_THRESHOLD_MS] 同一把尺子（不是自然日）。
 * 最早一张的移入时间距 [now] 已满这个阈值时，返回已放的整天数；未满、或没有移入时间时返回 null，
 * 卡片保持原来的副文字。没有提醒间隔。
 */
object TrashOldestDays {
    private const val MS_PER_DAY = 24L * 60 * 60 * 1000L

    fun wholeDays(movedAt: Collection<Long>, now: Long): Int? {
        val earliest = movedAt.minOrNull() ?: return null
        val age = now - earliest
        if (age < TrashReminderRules.AGE_THRESHOLD_MS) return null
        return (age / MS_PER_DAY).toInt()
    }
}

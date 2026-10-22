package com.winters.cleanphotos.domain.model

/**
 * 垃圾桶提醒何时出现、用哪条文案。
 * 「现在」由调用方传入，方便测试固定时间。
 */
object TrashReminderRules {
    /** 1 GB = 1024³ 字节。 */
    const val SIZE_THRESHOLD_BYTES = 1024L * 1024L * 1024L
    const val AGE_THRESHOLD_MS = 30L * 24 * 60 * 60 * 1000
    const val INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

    /** size 为 null 记 0。 */
    fun totalBytes(sizes: Iterable<Long?>): Long = sizes.sumOf { it ?: 0L }

    /**
     * 垃圾桶非空，且（总大小 ≥ 1 GB 或最早一条已放入 ≥ 30 天），并且距上次提醒 ≥ 7 天。
     * 从没提醒过（[lastShownAt] 为 null）视为间隔已满足。两条都满足时返回 [TrashReminderReason.Size]。
     */
    fun evaluate(
        count: Int,
        totalBytes: Long,
        earliestMovedAt: Long?,
        lastShownAt: Long?,
        now: Long,
    ): TrashReminderReason? {
        if (count <= 0) return null
        val intervalOk = lastShownAt == null || now - lastShownAt >= INTERVAL_MS
        if (!intervalOk) return null
        val sizeHit = totalBytes >= SIZE_THRESHOLD_BYTES
        val ageHit = earliestMovedAt != null && now - earliestMovedAt >= AGE_THRESHOLD_MS
        return when {
            sizeHit -> TrashReminderReason.Size
            ageHit -> TrashReminderReason.Age
            else -> null
        }
    }
}

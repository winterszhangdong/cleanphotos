package com.winters.cleanphotos.domain.model

/** 垃圾桶提醒的原因。两条都满足时用 [Size]。 */
enum class TrashReminderReason {
    Size,
    Age,
}

/** 一次可以展示的提醒。[totalBytes] 已把 size 为 null 的照片按 0 计入。 */
data class TrashReminder(
    val reason: TrashReminderReason,
    val totalBytes: Long,
)

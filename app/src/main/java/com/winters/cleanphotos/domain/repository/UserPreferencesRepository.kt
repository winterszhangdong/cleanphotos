package com.winters.cleanphotos.domain.repository

import kotlinx.coroutines.flow.Flow

interface UserPreferencesRepository {
    fun observeHasSeenReviewTutorial(): Flow<Boolean>
    suspend fun setHasSeenReviewTutorial(seen: Boolean)
    /** 累计成功的上滑 / 下滑处理次数（review_swipe_action_count）。 */
    fun observeSwipeActionCount(): Flow<Int>
    suspend fun incrementSwipeActionCount()
    /** 累计已确认永久删除的字节（total_freed_bytes）。只增不减。 */
    fun observeTotalFreedBytes(): Flow<Long>
    /** [bytes] 必须 > 0。 */
    suspend fun addFreedBytes(bytes: Long)

    /** 上次垃圾桶提醒出现的时间（epoch ms）。从没提醒过是 null。 */
    fun observeTrashReminderShownAt(): Flow<Long?>
    suspend fun setTrashReminderShownAt(at: Long)
}

package com.winters.cleanphotos.domain.repository

import kotlinx.coroutines.flow.Flow

interface UserPreferencesRepository {
    fun observeHasSeenReviewTutorial(): Flow<Boolean>
    suspend fun setHasSeenReviewTutorial(seen: Boolean)
    /** 累计已确认永久删除的字节（total_freed_bytes）。只增不减。 */
    fun observeTotalFreedBytes(): Flow<Long>
    /** [bytes] 必须 > 0。 */
    suspend fun addFreedBytes(bytes: Long)
}

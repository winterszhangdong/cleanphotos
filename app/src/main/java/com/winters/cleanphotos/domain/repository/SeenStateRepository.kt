package com.winters.cleanphotos.domain.repository

import kotlinx.coroutines.flow.Flow

/** 「看过」状态（app_seen_entries）的抽象。ViewModel 只能经由 UseCase 读写。 */
interface SeenStateRepository {
    fun observeSeenIds(): Flow<Set<Long>>
    suspend fun markSeen(photoId: Long)
}

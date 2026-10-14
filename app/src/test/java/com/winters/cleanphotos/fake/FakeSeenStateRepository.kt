package com.winters.cleanphotos.fake

import com.winters.cleanphotos.domain.repository.SeenStateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 「看过」状态的内存实现。 */
class FakeSeenStateRepository(initial: Set<Long> = emptySet()) : SeenStateRepository {
    val seenIds = MutableStateFlow(initial)
    override fun observeSeenIds(): StateFlow<Set<Long>> = seenIds
    override suspend fun markSeen(photoId: Long) {
        seenIds.value = seenIds.value + photoId
    }
    var batchMarkCalls = 0
    override suspend fun markSeen(photoIds: Set<Long>) {
        batchMarkCalls++
        seenIds.value = seenIds.value + photoIds
    }
}

package com.winters.cleanphotos.fake

import com.winters.cleanphotos.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fake UserPreferencesRepository 用于测试。
 */
class FakeUserPreferencesRepository(
    initialSeen: Boolean = false
) : UserPreferencesRepository {

    private val hasSeenTutorialFlow = MutableStateFlow(initialSeen)

    override fun observeHasSeenReviewTutorial(): Flow<Boolean> = hasSeenTutorialFlow

    override suspend fun setHasSeenReviewTutorial(seen: Boolean) {
        hasSeenTutorialFlow.value = seen
    }

    val swipeActionCountFlow = MutableStateFlow(0)

    override fun observeSwipeActionCount(): Flow<Int> = swipeActionCountFlow

    override suspend fun incrementSwipeActionCount() {
        swipeActionCountFlow.value += 1
    }

    val totalFreedBytesFlow = MutableStateFlow(0L)

    override fun observeTotalFreedBytes(): Flow<Long> = totalFreedBytesFlow

    override suspend fun addFreedBytes(bytes: Long) {
        require(bytes > 0L) { "bytes 必须 > 0：$bytes" }
        totalFreedBytesFlow.value += bytes
    }
}

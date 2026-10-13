package com.winters.cleanphotos.domain.repository

import kotlinx.coroutines.flow.Flow

interface UserPreferencesRepository {
    fun observeHasSeenReviewTutorial(): Flow<Boolean>
    suspend fun setHasSeenReviewTutorial(seen: Boolean)
    /** 累计成功的上滑 / 下滑处理次数（review_swipe_action_count）。 */
    fun observeSwipeActionCount(): Flow<Int>
    suspend fun incrementSwipeActionCount()
}

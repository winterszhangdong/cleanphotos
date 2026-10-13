package com.winters.cleanphotos.data.repository

import com.winters.cleanphotos.data.preferences.UserPreferencesDataSource
import com.winters.cleanphotos.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserPreferencesRepositoryImpl @Inject constructor(
    private val userPreferencesDataSource: UserPreferencesDataSource
) : UserPreferencesRepository {

    override fun observeHasSeenReviewTutorial(): Flow<Boolean> {
        return userPreferencesDataSource.observeHasSeenReviewTutorial()
    }

    override suspend fun setHasSeenReviewTutorial(seen: Boolean) {
        userPreferencesDataSource.setHasSeenReviewTutorial(seen)
    }

    override fun observeSwipeActionCount(): Flow<Int> = userPreferencesDataSource.observeSwipeActionCount()

    override suspend fun incrementSwipeActionCount() {
        userPreferencesDataSource.incrementSwipeActionCount()
    }
}

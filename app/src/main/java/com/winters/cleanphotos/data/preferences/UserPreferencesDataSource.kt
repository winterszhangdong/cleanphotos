package com.winters.cleanphotos.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.winters.cleanphotos.core.datastore.DataStoreKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserPreferencesDataSource @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private val hasSeenTutorialKey = booleanPreferencesKey(DataStoreKeys.HAS_SEEN_REVIEW_TUTORIAL)

    fun observeHasSeenReviewTutorial(): Flow<Boolean> {
        return dataStore.data.map { preferences ->
            preferences[hasSeenTutorialKey] ?: false
        }
    }

    suspend fun setHasSeenReviewTutorial(seen: Boolean) {
        dataStore.edit { preferences ->
            preferences[hasSeenTutorialKey] = seen
        }
    }

    private val swipeActionCountKey = intPreferencesKey(DataStoreKeys.REVIEW_SWIPE_ACTION_COUNT)

    fun observeSwipeActionCount(): Flow<Int> = dataStore.data.map { it[swipeActionCountKey] ?: 0 }

    suspend fun incrementSwipeActionCount() {
        dataStore.edit { it[swipeActionCountKey] = (it[swipeActionCountKey] ?: 0) + 1 }
    }
}

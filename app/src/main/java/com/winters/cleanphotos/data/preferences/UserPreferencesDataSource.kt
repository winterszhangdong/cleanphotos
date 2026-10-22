package com.winters.cleanphotos.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
        dataStore.edit { incrementSwipeActionCountIn(it) }
    }

    /** 在调用方的 edit 里 +1（同一个 DataStore），让一次滑动的所有写入合成一次。 */
    internal fun incrementSwipeActionCountIn(prefs: MutablePreferences) {
        prefs[swipeActionCountKey] = (prefs[swipeActionCountKey] ?: 0) + 1
    }

    private val trashReminderShownAtKey = longPreferencesKey(DataStoreKeys.TRASH_REMINDER_SHOWN_AT)

    /** 没有写过时是 null，不能当成 0（0 会被当成 1970 年已经提醒过）。 */
    fun observeTrashReminderShownAt(): Flow<Long?> = dataStore.data.map { it[trashReminderShownAtKey] }

    suspend fun setTrashReminderShownAt(at: Long) {
        dataStore.edit { it[trashReminderShownAtKey] = at }
    }
}

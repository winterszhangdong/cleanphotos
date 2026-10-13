package com.winters.cleanphotos.data.datasource

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App 内"已看过"状态持久化数据源。
 *
 * 使用 DataStore<stringSet> 保存已看过的照片 id 集合。
 * 存储格式：Set<String>，每条为 photoId 的字符串表示。
 *
 * 解析规则：严格解析，格式非法时直接抛错，不静默容错。
 */
@Singleton
class AppSeenStateDataSource @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val ENTRIES_KEY = stringSetPreferencesKey("app_seen_entries")
    }

    /**
     * 观察已看过照片 id 集合。
     */
    fun observeSeenIds(): Flow<Set<Long>> {
        return dataStore.data.map { preferences ->
            val rawSet = preferences[ENTRIES_KEY] ?: emptySet()
            rawSet.mapNotNull { it.toLongOrNull() }.toSet()
        }
    }

    /**
     * 标记单张照片为已看过。
     */
    suspend fun markSeen(photoId: Long) {
        dataStore.edit { preferences ->
            val currentSet = preferences[ENTRIES_KEY] ?: emptySet()
            preferences[ENTRIES_KEY] = currentSet + photoId.toString()
        }
    }

    /**
     * 批量标记照片为已看过。
     */
    suspend fun markSeen(photoIds: Set<Long>) {
        if (photoIds.isEmpty()) return
        dataStore.edit { preferences ->
            val currentSet = preferences[ENTRIES_KEY] ?: emptySet()
            preferences[ENTRIES_KEY] = currentSet + photoIds.map { it.toString() }.toSet()
        }
    }

    /**
     * 批量移除已看过记录。
     */
    suspend fun removeEntries(photoIds: Set<Long>) {
        if (photoIds.isEmpty()) return
        dataStore.edit { preferences ->
            val currentSet = preferences[ENTRIES_KEY] ?: return@edit
            val idsToRemove = photoIds.map { it.toString() }.toSet()
            preferences[ENTRIES_KEY] = currentSet - idsToRemove
        }
    }

    /**
     * 清理失效条目：移除所有不在 existingPhotoIds 中的条目。
     * 用于 MediaStore 刷新后，检测外部删除导致的不一致。
     */
    suspend fun removeMissingEntries(existingPhotoIds: Set<Long>) {
        dataStore.edit { preferences ->
            val currentSet = preferences[ENTRIES_KEY] ?: return@edit
            val existingStrings = existingPhotoIds.map { it.toString() }.toSet()
            preferences[ENTRIES_KEY] = currentSet.intersect(existingStrings)
        }
    }
}

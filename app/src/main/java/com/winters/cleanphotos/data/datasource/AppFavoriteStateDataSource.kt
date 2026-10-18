package com.winters.cleanphotos.data.datasource

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.winters.cleanphotos.domain.model.AppFavoriteEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App 内收藏持久化数据源。
 *
 * 使用 DataStore<stringSet> 保存收藏条目。
 * 每条记录编码为固定字符串格式：`photoId,favoritedAt`
 * 同一 photoId 重复写入时覆盖旧值。
 *
 * 解析规则：严格解析，格式非法时直接抛错，不静默容错。
 */
@Singleton
class AppFavoriteStateDataSource @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val ENTRIES_KEY = stringSetPreferencesKey("app_favorite_entries")
        private const val ENTRY_SEPARATOR = ","
    }

    /**
     * 观察所有收藏条目，按 favoritedAt 倒序排列。
     */
    fun observeEntries(): Flow<List<AppFavoriteEntry>> {
        // 只在本键变化时重新解析（同垃圾桶）
        return dataStore.safeData()
            .map { preferences -> preferences[ENTRIES_KEY] ?: emptySet() }
            .distinctUntilChanged()
            .map { rawSet -> parseEntries(rawSet).sortedByDescending { it.favoritedAt } }
    }

    /** 当前条目（在一次 edit 内读取）。 */
    internal fun entriesIn(preferences: Preferences): List<AppFavoriteEntry> =
        parseEntries(preferences[ENTRIES_KEY] ?: emptySet())

    /** 在调用方的 edit 里批量新增 / 覆盖（photoId → favoritedAt）。 */
    internal fun upsertIn(preferences: MutablePreferences, entries: Map<Long, Long>) {
        if (entries.isEmpty()) return
        val currentSet = preferences[ENTRIES_KEY] ?: emptySet()
        val filtered = currentSet.filterNot { parseEntry(it).photoId in entries }.toSet()
        preferences[ENTRIES_KEY] = filtered + entries.map { (id, at) -> encodeEntry(id, at) }
    }

    /** 在调用方的 edit 里批量删除。 */
    internal fun removeIn(preferences: MutablePreferences, photoIds: Set<Long>) {
        if (photoIds.isEmpty()) return
        val currentSet = preferences[ENTRIES_KEY] ?: return
        preferences[ENTRIES_KEY] = currentSet.filterNot { parseEntry(it).photoId in photoIds }.toSet()
    }

    /**
     * 新增或更新一条收藏条目。
     * 同一 photoId 重复写入时覆盖旧值。
     */
    suspend fun upsertEntry(photoId: Long, favoritedAt: Long) {
        dataStore.edit { preferences ->
            val currentSet = preferences[ENTRIES_KEY] ?: emptySet()
            // 移除同一 photoId 的旧条目
            val filtered = currentSet.filterNot { it.startsWith("$photoId$ENTRY_SEPARATOR") }.toSet()
            preferences[ENTRIES_KEY] = filtered + encodeEntry(photoId, favoritedAt)
        }
    }

    /**
     * 批量新增或更新收藏条目（photoId → favoritedAt），一次写入。
     */
    suspend fun upsertEntries(entries: Map<Long, Long>) {
        if (entries.isEmpty()) return
        dataStore.edit { preferences ->
            val currentSet = preferences[ENTRIES_KEY] ?: emptySet()
            val filtered = currentSet.filterNot { parseEntry(it).photoId in entries }.toSet()
            preferences[ENTRIES_KEY] = filtered + entries.map { (id, at) -> encodeEntry(id, at) }
        }
    }

    /**
     * 删除单条收藏条目。
     */
    suspend fun removeEntry(photoId: Long) {
        dataStore.edit { preferences ->
            val currentSet = preferences[ENTRIES_KEY] ?: return@edit
            val filtered = currentSet.filterNot { it.startsWith("$photoId$ENTRY_SEPARATOR") }.toSet()
            preferences[ENTRIES_KEY] = filtered
        }
    }

    /**
     * 批量删除收藏条目。
     */
    suspend fun removeEntries(photoIds: Set<Long>) {
        if (photoIds.isEmpty()) return
        dataStore.edit { preferences ->
            val currentSet = preferences[ENTRIES_KEY] ?: return@edit
            val filtered = currentSet.filterNot { parseEntry(it).photoId in photoIds }.toSet()
            preferences[ENTRIES_KEY] = filtered
        }
    }

    /**
     * 清理失效条目：移除所有不在 existingPhotoIds 中的条目。
     * 用于 MediaStore 刷新后，检测外部删除导致的不一致。
     */
    suspend fun removeMissingEntries(existingPhotoIds: Set<Long>) {
        dataStore.edit { preferences ->
            val currentSet = preferences[ENTRIES_KEY] ?: return@edit
            val validEntries = currentSet.filter { entry ->
                val photoId = parseEntry(entry).photoId
                photoId in existingPhotoIds
            }.toSet()
            preferences[ENTRIES_KEY] = validEntries
        }
    }

    /**
     * 编码单条记录为字符串：`photoId,favoritedAt`
     */
    private fun encodeEntry(photoId: Long, favoritedAt: Long): String {
        return "$photoId$ENTRY_SEPARATOR$favoritedAt"
    }

    /**
     * 解析单条记录。格式非法时直接抛错。
     */
    private fun parseEntry(raw: String): AppFavoriteEntry {
        val parts = raw.split(ENTRY_SEPARATOR)
        require(parts.size == 2) { "收藏条目格式非法：'$raw'，预期格式为 'photoId,favoritedAt'" }
        val photoId = parts[0].toLongOrNull()
            ?: throw IllegalArgumentException("收藏条目 photoId 解析失败：'${parts[0]}'，原始值：'$raw'")
        val favoritedAt = parts[1].toLongOrNull()
            ?: throw IllegalArgumentException("收藏条目 favoritedAt 解析失败：'${parts[1]}'，原始值：'$raw'")
        return AppFavoriteEntry(photoId = photoId, favoritedAt = favoritedAt)
    }

    /**
     * 批量解析所有条目。
     */
    private fun parseEntries(rawSet: Set<String>): List<AppFavoriteEntry> {
        return rawSet.map { parseEntry(it) }
    }

    /** 在调用方的 edit 里清理失效条目。 */
    internal fun removeMissingIn(preferences: MutablePreferences, existingPhotoIds: Set<Long>) {
        val currentSet = preferences[ENTRIES_KEY] ?: return
        val valid = currentSet.filter { parseEntry(it).photoId in existingPhotoIds }.toSet()
        if (valid.size != currentSet.size) preferences[ENTRIES_KEY] = valid
    }
}

package com.winters.cleanphotos.data.datasource

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.winters.cleanphotos.domain.model.AppTrashEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App 内垃圾桶持久化数据源。
 *
 * 使用 DataStore<stringSet> 保存垃圾桶条目。
 * 每条记录编码为固定字符串格式：`photoId,movedToTrashAt,wasFavorited,favoritedAt`
 * 其中 favoritedAt 为 null 时写字面量 `-`。
 * 同一 photoId 重复写入时覆盖旧值。
 *
 * 向后兼容：旧格式条目（2 段）解析时默认 wasFavorited=false, favoritedAt=null。
 * 解析规则：严格解析，格式非法时直接抛错，不静默容错。
 */
@Singleton
class AppTrashStateDataSource @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val ENTRIES_KEY = stringSetPreferencesKey("app_trash_entries")
        private const val ENTRY_SEPARATOR = ","
        private const val NULL_PLACEHOLDER = "-"
    }

    /**
     * 观察所有垃圾桶条目，按 movedToTrashAt 倒序排列。
     */
    fun observeEntries(): Flow<List<AppTrashEntry>> {
        return dataStore.data.map { preferences ->
            val rawSet = preferences[ENTRIES_KEY] ?: emptySet()
            parseEntries(rawSet).sortedByDescending { it.movedToTrashAt }
        }
    }

    /**
     * 新增或更新一条垃圾桶条目（含收藏快照）。
     */
    suspend fun upsertEntry(
        photoId: Long,
        movedToTrashAt: Long,
        wasFavorited: Boolean = false,
        favoritedAt: Long? = null
    ) {
        dataStore.edit { preferences ->
            val currentSet = preferences[ENTRIES_KEY] ?: emptySet()
            // 移除同一 photoId 的旧条目
            val filtered = currentSet.filterNot { it.startsWith("$photoId$ENTRY_SEPARATOR") }.toSet()
            preferences[ENTRIES_KEY] = filtered + encodeEntry(photoId, movedToTrashAt, wasFavorited, favoritedAt)
        }
    }

    /**
     * 删除单条垃圾桶条目。
     */
    suspend fun removeEntry(photoId: Long) {
        dataStore.edit { preferences ->
            val currentSet = preferences[ENTRIES_KEY] ?: return@edit
            val filtered = currentSet.filterNot { it.startsWith("$photoId$ENTRY_SEPARATOR") }.toSet()
            preferences[ENTRIES_KEY] = filtered
        }
    }

    /**
     * 批量删除垃圾桶条目。
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
     * 编码单条记录为字符串：`photoId,movedToTrashAt,wasFavorited,favoritedAt`
     * favoritedAt 为 null 时写字面量 `-`。
     */
    private fun encodeEntry(
        photoId: Long,
        movedToTrashAt: Long,
        wasFavorited: Boolean,
        favoritedAt: Long?
    ): String {
        val favoritedAtStr = favoritedAt?.toString() ?: NULL_PLACEHOLDER
        return "$photoId$ENTRY_SEPARATOR$movedToTrashAt$ENTRY_SEPARATOR$wasFavorited$ENTRY_SEPARATOR$favoritedAtStr"
    }

    /**
     * 解析单条记录。
     * 兼容旧格式（2 段）：默认 wasFavorited=false, favoritedAt=null。
     * 格式非法时直接抛错。
     */
    private fun parseEntry(raw: String): AppTrashEntry {
        val parts = raw.split(ENTRY_SEPARATOR)
        require(parts.size in 2..4) { "垃圾桶条目格式非法：'$raw'，预期格式为 'photoId,movedToTrashAt[,wasFavorited[,favoritedAt]]'" }

        val photoId = parts[0].toLongOrNull()
            ?: throw IllegalArgumentException("垃圾桶条目 photoId 解析失败：'${parts[0]}'，原始值：'$raw'")
        val movedToTrashAt = parts[1].toLongOrNull()
            ?: throw IllegalArgumentException("垃圾桶条目 movedToTrashAt 解析失败：'${parts[1]}'，原始值：'$raw'")

        // 旧格式（2 段）兼容：默认未收藏
        if (parts.size == 2) {
            return AppTrashEntry(photoId = photoId, movedToTrashAt = movedToTrashAt)
        }

        val wasFavorited = parts[2].toBooleanStrictOrNull()
            ?: throw IllegalArgumentException("垃圾桶条目 wasFavorited 解析失败：'${parts[2]}'，原始值：'$raw'")

        // 3 段时 favoritedAt 为 null
        if (parts.size == 3) {
            return AppTrashEntry(
                photoId = photoId,
                movedToTrashAt = movedToTrashAt,
                wasFavorited = wasFavorited,
                favoritedAt = null
            )
        }

        // 4 段完整格式
        val favoritedAt = if (parts[3] == NULL_PLACEHOLDER) {
            null
        } else {
            parts[3].toLongOrNull()
                ?: throw IllegalArgumentException("垃圾桶条目 favoritedAt 解析失败：'${parts[3]}'，原始值：'$raw'")
        }

        return AppTrashEntry(
            photoId = photoId,
            movedToTrashAt = movedToTrashAt,
            wasFavorited = wasFavorited,
            favoritedAt = favoritedAt
        )
    }

    /**
     * 批量解析所有条目。
     */
    private fun parseEntries(rawSet: Set<String>): List<AppTrashEntry> {
        return rawSet.map { parseEntry(it) }
    }
}

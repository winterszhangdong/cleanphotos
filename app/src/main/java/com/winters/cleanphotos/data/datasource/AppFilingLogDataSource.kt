package com.winters.cleanphotos.data.datasource

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.winters.cleanphotos.domain.model.FilingRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 装袋记录（收藏页「已冲洗」）。DataStore<stringSet>，每条 `photoId|filedAt|dayKey|relativePath`。
 * 同一 photoId 只留最近一次；超过 [MAX_ENTRIES] 条时丢最旧的。格式非法直接抛错（同收藏 / 垃圾桶）。
 */
@Singleton
class AppFilingLogDataSource @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private val ENTRIES_KEY = stringSetPreferencesKey("app_filing_log")
        private const val SEP = "|"
        internal const val MAX_ENTRIES = 1000
    }

    /** 按装袋时间倒序。 */
    fun observeEntries(): Flow<List<FilingRecord>> =
        dataStore.safeData()
            .map { it[ENTRIES_KEY] ?: emptySet() }
            .distinctUntilChanged()
            .map { raw -> raw.map(::parse).sortedByDescending { it.filedAt } }

    internal fun upsertIn(preferences: MutablePreferences, records: List<FilingRecord>) {
        if (records.isEmpty()) return
        val ids = records.mapTo(HashSet()) { it.photoId }
        val kept = (preferences[ENTRIES_KEY] ?: emptySet()).map(::parse).filterNot { it.photoId in ids }
        preferences[ENTRIES_KEY] = (kept + records)
            .sortedByDescending { it.filedAt }
            .take(MAX_ENTRIES)
            .mapTo(HashSet(), ::encode)
    }

    internal fun removeIn(preferences: MutablePreferences, photoIds: Set<Long>) {
        if (photoIds.isEmpty()) return
        val current = preferences[ENTRIES_KEY] ?: return
        preferences[ENTRIES_KEY] = current.filterNot { parse(it).photoId in photoIds }.toSet()
    }

    /** 在调用方的 edit 里清掉已经不在图库里的照片。 */
    internal fun removeMissingIn(preferences: MutablePreferences, existingPhotoIds: Set<Long>) {
        val current = preferences[ENTRIES_KEY] ?: return
        val valid = current.filter { parse(it).photoId in existingPhotoIds }.toSet()
        if (valid.size != current.size) preferences[ENTRIES_KEY] = valid
    }

    private fun encode(r: FilingRecord): String {
        require(SEP !in r.relativePath && SEP !in r.dayKey) { "装袋记录字段含分隔符：$r" }
        return listOf(r.photoId, r.filedAt, r.dayKey, r.relativePath).joinToString(SEP)
    }

    private fun parse(raw: String): FilingRecord {
        val parts = raw.split(SEP)
        require(parts.size == 4) { "装袋记录格式非法：'$raw'" }
        return FilingRecord(
            photoId = requireNotNull(parts[0].toLongOrNull()) { "装袋记录 photoId 非法：'$raw'" },
            filedAt = requireNotNull(parts[1].toLongOrNull()) { "装袋记录 filedAt 非法：'$raw'" },
            dayKey = parts[2],
            relativePath = parts[3],
        )
    }
}

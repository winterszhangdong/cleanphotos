package com.winters.cleanphotos.data.datasource

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 已处理的相似组。每条是 id 升序后用逗号连起来的字符串。
 * 格式非法直接抛错，不静默丢掉。
 */
@Singleton
class AppSimilarResolvedDataSource @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    fun observeResolved(): Flow<List<Set<Long>>> =
        dataStore.safeData()
            .map { preferences -> preferences[KEY] ?: emptySet() }
            .distinctUntilChanged()
            .map { raw -> raw.map(::decode).sortedBy { it.minOrNull() ?: 0L } }

    suspend fun addResolved(ids: Set<Long>) {
        val encoded = encode(ids)
        dataStore.edit { preferences ->
            val current = preferences[KEY] ?: emptySet()
            if (encoded !in current) preferences[KEY] = current + encoded
        }
    }

    suspend fun removeResolved(ids: Set<Long>) {
        val encoded = encode(ids)
        dataStore.edit { preferences ->
            val current = preferences[KEY] ?: return@edit
            if (encoded in current) preferences[KEY] = current - encoded
        }
    }

    internal companion object {
        val KEY = stringSetPreferencesKey("app_similar_resolved")

        fun encode(ids: Set<Long>): String {
            require(ids.size >= 2) { "已处理记录至少两张：$ids" }
            return ids.sorted().joinToString(",")
        }

        fun decode(raw: String): Set<Long> {
            val ids = raw.split(',').map { token ->
                token.toLongOrNull() ?: error("已处理记录格式非法：$raw")
            }
            require(ids.size >= 2 && ids.zipWithNext().all { (a, b) -> a < b }) {
                "已处理记录格式非法：$raw"
            }
            return ids.toSet()
        }
    }
}

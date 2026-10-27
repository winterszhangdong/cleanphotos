package com.winters.cleanphotos.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.winters.cleanphotos.data.datasource.AppFilingLogDataSource
import com.winters.cleanphotos.domain.model.FilingRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 装袋记录：同一张只留最近一次、超量丢最旧、失效清理。 */
class AppFilingLogDataSourceTest {
    private val dir: File = Files.createTempDirectory("filing-log").toFile()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "t.preferences_pb") }
    private val source = AppFilingLogDataSource(dataStore)

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun record(id: Long, at: Long, path: String = "Pictures/旅行/") = FilingRecord(id, path, "October 24, 2026", at)

    @Test
    fun `同一张再装一次覆盖旧记录_按时间倒序`() = runBlocking {
        dataStore.edit { source.upsertIn(it, listOf(record(1, 10), record(2, 20))) }
        dataStore.edit { source.upsertIn(it, listOf(record(1, 30, "Pictures/猫/"))) }
        val entries = source.observeEntries().first()
        assertEquals(listOf(1L, 2L), entries.map { it.photoId })
        assertEquals("猫", entries[0].albumName)
    }

    @Test
    fun `超过上限丢最旧的`() = runBlocking {
        val many = (1L..AppFilingLogDataSource.MAX_ENTRIES + 5L).map { record(it, it) }
        dataStore.edit { source.upsertIn(it, many) }
        val entries = source.observeEntries().first()
        assertEquals(AppFilingLogDataSource.MAX_ENTRIES, entries.size)
        assertEquals(6L, entries.last().photoId)
    }

    @Test
    fun `撤回和失效清理删掉对应记录`() = runBlocking {
        dataStore.edit { source.upsertIn(it, listOf(record(1, 10), record(2, 20), record(3, 30))) }
        dataStore.edit { source.removeIn(it, setOf(2L)) }
        dataStore.edit { source.removeMissingIn(it, setOf(1L)) }
        assertEquals(listOf(1L), source.observeEntries().first().map { it.photoId })
    }
}

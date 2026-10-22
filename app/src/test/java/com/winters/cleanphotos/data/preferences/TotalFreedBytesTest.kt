package com.winters.cleanphotos.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.winters.cleanphotos.data.repository.UserPreferencesRepositoryImpl
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

/** total_freed_bytes：默认 0，多次累加，非正数直接失败。 */
class TotalFreedBytesTest {
    private val dir: File = Files.createTempDirectory("freed-bytes").toFile()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "t.preferences_pb") }
    private val dataSource = UserPreferencesDataSource(dataStore)
    private val repository = UserPreferencesRepositoryImpl(dataSource)

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `默认是 0，多次累加`() = runBlocking {
        assertEquals(0L, dataSource.observeTotalFreedBytes().first())
        assertEquals(0L, repository.observeTotalFreedBytes().first())

        dataSource.addFreedBytes(100L)
        dataSource.addFreedBytes(250L)
        assertEquals(350L, dataSource.observeTotalFreedBytes().first())

        repository.addFreedBytes(50L)
        assertEquals(400L, repository.observeTotalFreedBytes().first())
        assertEquals(400L, dataSource.observeTotalFreedBytes().first())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `DataSource 拒绝 0`() = runBlocking {
        dataSource.addFreedBytes(0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `Repository 拒绝负数`() = runBlocking {
        repository.addFreedBytes(-1L)
    }
}

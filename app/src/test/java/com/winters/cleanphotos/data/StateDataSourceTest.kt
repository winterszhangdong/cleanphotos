package com.winters.cleanphotos.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.winters.cleanphotos.domain.model.SwipeSideEffects
import com.winters.cleanphotos.TestPhotoData.createTestPhoto
import com.winters.cleanphotos.data.datasource.AppFavoriteStateDataSource
import com.winters.cleanphotos.data.datasource.AppSeenStateDataSource
import com.winters.cleanphotos.data.datasource.AppTrashStateDataSource
import com.winters.cleanphotos.data.repository.ReviewRepositoryImpl
import com.winters.cleanphotos.data.preferences.UserPreferencesDataSource
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 单张操作合成一次写入后语义不变；其它键的写入不让垃圾桶 / 收藏流重复发射。 */
class StateDataSourceTest {
    private val dir: File = Files.createTempDirectory("state-ds").toFile()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val realStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "t.preferences_pb") }
    /** 数一下真正写文件的次数（edit 走 updateData）。 */
    private var writes = 0
    private val dataStore = object : DataStore<Preferences> by realStore {
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            writes++
            return realStore.updateData(transform)
        }
    }
    private val favorites = AppFavoriteStateDataSource(dataStore)
    private val trash = AppTrashStateDataSource(dataStore)
    private val seen = AppSeenStateDataSource(dataStore)
    private val repo = ReviewRepositoryImpl(mockk(relaxed = true), trash, favorites, seen, UserPreferencesDataSource(dataStore))

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `单张移入垃圾桶_快照收藏_移除收藏_标记看过_恢复后收藏回来`() = runBlocking {
        val p = createTestPhoto(1)
        repo.favoritePhoto(p)
        val favoritedAt = favorites.observeEntries().first().single().favoritedAt
        assertEquals(setOf(1L), seen.observeSeenIds().first())

        repo.movePhotoToTrash(p)
        val entry = trash.observeEntries().first().single()
        assertTrue(entry.wasFavorited)
        assertEquals(favoritedAt, entry.favoritedAt)
        assertTrue(favorites.observeEntries().first().isEmpty())

        repo.restorePhotoFromTrash(p)
        assertTrue(trash.observeEntries().first().isEmpty())
        assertEquals(favoritedAt, favorites.observeEntries().first().single().favoritedAt)
    }

    @Test
    fun `取消收藏与未收藏照片移入垃圾桶`() = runBlocking {
        val p = createTestPhoto(2)
        repo.favoritePhoto(p)
        repo.removeFavorite(p)
        assertTrue(favorites.observeEntries().first().isEmpty())
        repo.movePhotoToTrash(p)
        assertEquals(false, trash.observeEntries().first().single().wasFavorited)
    }

    @Test
    fun `写看过不让垃圾桶流重复发射`() = runBlocking {
        trash.upsertEntry(1L, 10L)
        val emissions = mutableListOf<Int>()
        val job = launch(Dispatchers.IO) { trash.observeEntries().collect { emissions += it.size } }
        delay(200)
        seen.markSeen(5L)
        seen.markSeen(6L)
        delay(200)
        job.cancel()
        assertEquals(listOf(1), emissions)
    }

    @Test
    fun `重复标记看过不改变集合`() = runBlocking {
        seen.markSeen(3L)
        seen.markSeen(3L)
        assertEquals(setOf(3L), seen.observeSeenIds().first())
    }

    @Test
    fun `一次滑动_操作加下一张看过加滑动计数只写一次文件`() = runBlocking {
        val prefs = UserPreferencesDataSource(dataStore)
        writes = 0
        repo.favoritePhoto(createTestPhoto(1), SwipeSideEffects(markSeenId = 2L, countSwipe = true))
        assertEquals(1, writes)
        assertEquals(setOf(1L, 2L), seen.observeSeenIds().first())
        assertEquals(1, prefs.observeSwipeActionCount().first())
        assertEquals(1, favorites.observeEntries().first().size)

        writes = 0
        repo.movePhotoToTrash(createTestPhoto(2), SwipeSideEffects(markSeenId = 3L, countSwipe = true))
        assertEquals(1, writes)
        assertEquals(setOf(1L, 2L, 3L), seen.observeSeenIds().first())
        assertEquals(2, prefs.observeSwipeActionCount().first())
    }
}

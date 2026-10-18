package com.winters.cleanphotos.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 批量接口落到真实 Preferences DataStore：收藏 + 垃圾桶的快照语义。 */
class ReviewRepositoryBatchTest {
    private val dir: File = Files.createTempDirectory("batch-ds").toFile()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "t.preferences_pb") }
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
    fun `批量移入垃圾桶保留收藏快照_收藏页不再有它_恢复后收藏回来`() = runBlocking {
        val p1 = createTestPhoto(1)
        val p2 = createTestPhoto(2)
        favorites.upsertEntry(1L, 111L)

        repo.movePhotosToTrash(listOf(p1, p2))
        val entries = trash.observeEntries().first().associateBy { it.photoId }
        assertEquals(setOf(1L, 2L), entries.keys)
        assertTrue(entries.getValue(1L).wasFavorited)
        assertEquals(111L, entries.getValue(1L).favoritedAt)
        assertEquals(false, entries.getValue(2L).wasFavorited)
        // 收藏条目移除 → 收藏页（收藏 且 不在垃圾桶）看不到
        assertTrue(favorites.observeEntries().first().isEmpty())

        repo.restorePhotosFromTrash(listOf(p1, p2))
        assertTrue(trash.observeEntries().first().isEmpty())
        val fav = favorites.observeEntries().first()
        assertEquals(listOf(1L), fav.map { it.photoId })
        assertEquals(111L, fav.single().favoritedAt)
    }

    @Test
    fun `批量收藏与取消收藏`() = runBlocking {
        repo.favoritePhotos(listOf(createTestPhoto(3), createTestPhoto(4)))
        assertEquals(setOf(3L, 4L), favorites.observeEntries().first().map { it.photoId }.toSet())
        repo.removeFavorites(listOf(createTestPhoto(3)))
        assertEquals(setOf(4L), favorites.observeEntries().first().map { it.photoId }.toSet())
    }
}

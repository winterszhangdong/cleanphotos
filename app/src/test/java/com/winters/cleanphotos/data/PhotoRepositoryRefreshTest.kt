package com.winters.cleanphotos.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.winters.cleanphotos.TestPhotoData.createTestPhoto
import com.winters.cleanphotos.data.datasource.AppFavoriteStateDataSource
import com.winters.cleanphotos.data.datasource.AppSeenStateDataSource
import com.winters.cleanphotos.data.datasource.AppTrashStateDataSource
import com.winters.cleanphotos.data.datasource.MediaStorePhotoDataSource
import com.winters.cleanphotos.data.repository.PhotoRepositoryImpl
import com.winters.cleanphotos.domain.model.PhotoItem
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 照片仓库刷新：并发刷新不让旧结果覆盖新结果；回到前台节流；进程重建后按需加载。 */
class PhotoRepositoryRefreshTest {
    private val dir: File = Files.createTempDirectory("refresh-ds").toFile()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "t.preferences_pb") }
    private val mediaStore = mockk<MediaStorePhotoDataSource>()
    private val queries = AtomicInteger()

    private val repo = PhotoRepositoryImpl(
        context = mockk<Context>(relaxed = true),
        mediaStoreDataSource = mediaStore,
        appTrashStateDataSource = AppTrashStateDataSource(dataStore),
        appFavoriteStateDataSource = AppFavoriteStateDataSource(dataStore),
        appSeenStateDataSource = AppSeenStateDataSource(dataStore),
        dataStore = dataStore,
    )

    init {
        every { mediaStore.queryTrashedPhotos() } returns emptyList()
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `先发起的慢查询后返回_不覆盖后发起的新结果`() = runBlocking {
        val oldList = listOf(createTestPhoto(1))
        val newList = listOf(createTestPhoto(1), createTestPhoto(2))
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        var call = 0
        every { mediaStore.queryAllPhotos() } answers {
            call++
            if (call == 1) {
                // 第一次查询（旧的图库快照）卡住，第二次刷新在它返回前发起
                firstStarted.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
                oldList
            } else {
                newList
            }
        }

        val first = async(Dispatchers.IO) { repo.refreshPhotos() }
        firstStarted.await(5, TimeUnit.SECONDS)
        val second = async(Dispatchers.IO) { repo.refreshPhotos() }
        Thread.sleep(50)
        releaseFirst.countDown()
        withTimeout(5_000) { first.await(); second.await() }

        assertEquals(listOf(1L, 2L), repo.observeAllPhotos().first().map(PhotoItem::id))
    }

    @Test
    fun `回到前台刷新_距上次刷新太近时跳过`() = runBlocking {
        every { mediaStore.queryAllPhotos() } answers { queries.incrementAndGet(); listOf(createTestPhoto(1)) }
        repo.refreshPhotos()
        repo.refreshPhotosIfStale(minIntervalMs = 60_000)
        assertEquals(1, queries.get())
        repo.refreshPhotosIfStale(minIntervalMs = 0)
        assertEquals(2, queries.get())
    }

    @Test
    fun `ensurePhotosLoaded 只在本进程还没加载过时查询`() = runBlocking {
        every { mediaStore.queryAllPhotos() } answers { queries.incrementAndGet(); listOf(createTestPhoto(7)) }
        repo.ensurePhotosLoaded()
        repo.ensurePhotosLoaded()
        assertEquals(1, queries.get())
        assertEquals(listOf(7L), repo.observeAllPhotos().first().map(PhotoItem::id))
    }

    @Test
    fun `首次迁移写入当时在系统垃圾桶里的收藏`() = runBlocking {
        every { mediaStore.queryAllPhotos() } returns listOf(createTestPhoto(1, isFavorite = false))
        every { mediaStore.queryTrashedPhotos() } returns listOf(createTestPhoto(2, isFavorite = true))
        repo.refreshPhotos()

        every { mediaStore.queryAllPhotos() } returns listOf(
            createTestPhoto(1, isFavorite = false),
            createTestPhoto(2, isFavorite = false),
        )
        every { mediaStore.queryTrashedPhotos() } returns emptyList()
        repo.refreshPhotos()

        assertEquals(listOf(2L), repo.observeFavoritedPhotos().first().map(PhotoItem::id))
    }

    @Test
    fun `系统垃圾桶查询失败时不标记迁移完成_下次刷新重试`() = runBlocking {
        every { mediaStore.queryAllPhotos() } returns listOf(createTestPhoto(1, isFavorite = false))
        every { mediaStore.queryTrashedPhotos() } throws RuntimeException("trash query")
        repo.refreshPhotos()
        assertEquals(emptyList<Long>(), repo.observeFavoritedPhotos().first().map(PhotoItem::id))

        every { mediaStore.queryTrashedPhotos() } returns listOf(createTestPhoto(2, isFavorite = true))
        repo.refreshPhotos()

        every { mediaStore.queryAllPhotos() } returns listOf(
            createTestPhoto(1, isFavorite = false),
            createTestPhoto(2, isFavorite = false),
        )
        every { mediaStore.queryTrashedPhotos() } returns emptyList()
        repo.refreshPhotos()

        assertEquals(listOf(2L), repo.observeFavoritedPhotos().first().map(PhotoItem::id))
    }
}

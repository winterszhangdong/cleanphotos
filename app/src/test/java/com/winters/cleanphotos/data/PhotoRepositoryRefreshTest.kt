package com.winters.cleanphotos.data

import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.winters.cleanphotos.TestPhotoData.createTestPhoto
import com.winters.cleanphotos.data.datasource.AppFavoriteStateDataSource
import com.winters.cleanphotos.data.datasource.AppSeenStateDataSource
import com.winters.cleanphotos.data.datasource.AppFilingLogDataSource
import com.winters.cleanphotos.data.datasource.AppTrashStateDataSource
import com.winters.cleanphotos.data.datasource.MediaPresence
import com.winters.cleanphotos.data.datasource.MediaStorePhotoDataSource
import com.winters.cleanphotos.data.repository.PhotoRepositoryImpl
import com.winters.cleanphotos.domain.model.AppTrashEntry
import com.winters.cleanphotos.domain.model.FilingRecord
import com.winters.cleanphotos.domain.model.PhotoItem
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    private val favorites = AppFavoriteStateDataSource(dataStore)
    private val seen = AppSeenStateDataSource(dataStore)
    private val trash = AppTrashStateDataSource(dataStore)
    private val filing = AppFilingLogDataSource(dataStore)

    private val repo = PhotoRepositoryImpl(
        context = mockk<Context>(relaxed = true),
        mediaStoreDataSource = mediaStore,
        appTrashStateDataSource = trash,
        appFavoriteStateDataSource = favorites,
        appSeenStateDataSource = seen,
        appFilingLogDataSource = filing,
        dataStore = dataStore,
    )

    init {
        every { mediaStore.queryTrashedPhotos() } returns emptyList()
        // 默认证据不足，避免把迁移进来的系统垃圾桶收藏当成失效删掉。
        every { mediaStore.queryPresence(any()) } returns MediaPresence(emptySet(), authoritative = false)
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

    @Test
    fun `主查询短暂失败时重试并加载照片`() = runBlocking {
        var call = 0
        every { mediaStore.queryAllPhotos() } answers {
            call++
            if (call == 1) throw IllegalArgumentException("Volume external_primary not found")
            listOf(createTestPhoto(3))
        }
        repo.refreshPhotos()
        assertEquals(2, call)
        assertEquals(listOf(3L), repo.observeAllPhotos().first().map(PhotoItem::id))
    }

    @Test
    fun `主查询连续失败时抛出且不记成已加载`() = runBlocking {
        val calls = AtomicInteger()
        every { mediaStore.queryAllPhotos() } answers {
            calls.incrementAndGet()
            throw IllegalStateException("MediaStore 查询没有返回游标")
        }
        try {
            repo.refreshPhotos()
            fail("主查询连续失败应该抛出")
        } catch (e: IllegalStateException) {
            assertEquals("MediaStore 查询没有返回游标", e.message)
        }
        val attempts = PhotoRepositoryImpl.QUERY_RETRY_DELAYS_MS.size + 1
        assertEquals(attempts, calls.get())
        assertEquals(emptyList<Long>(), repo.observeAllPhotos().first().map(PhotoItem::id))

        // 失败没有写入 lastRefreshAt，回到前台的节流不能把这次当成「刚加载过」而跳过
        try {
            repo.refreshPhotosIfStale(minIntervalMs = 60_000)
            fail("未加载过时回到前台应该再查，而不是跳过")
        } catch (e: IllegalStateException) {
            assertEquals("MediaStore 查询没有返回游标", e.message)
        }
        assertEquals(attempts * 2, calls.get())
    }

    @Test
    fun `主查询取消时不重试`() = runBlocking {
        var call = 0
        every { mediaStore.queryAllPhotos() } answers {
            call++
            throw CancellationException("cancelled")
        }
        try {
            repo.refreshPhotos()
            fail("取消应该抛出")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
        assertEquals(1, call)
        assertTrue(repo.observeAllPhotos().first().isEmpty())
    }

    @Test
    fun `卸载卷上的记录刷新后还在_重新挂上后回到列表`() = runBlocking {
        favorites.upsertEntries(mapOf(1L to 1L, 2L to 2L, 3L to 3L))
        trash.upsertEntries(listOf(AppTrashEntry(photoId = 4L, movedToTrashAt = 10L)))
        seen.markSeen(setOf(4L))
        dataStore.edit { filing.upsertIn(it, listOf(FilingRecord(4L, "Pictures/trip/", "2024-05-20", 10L))) }
        val online = createTestPhoto(1)
        every { mediaStore.queryAllPhotos() } returns listOf(online)
        every { mediaStore.queryPresence(any()) } returns MediaPresence(setOf(1L, 2L, 4L), authoritative = true)
        repo.refreshPhotos()

        assertEquals(setOf(1L, 2L), favorites.observeEntries().first().map { it.photoId }.toSet())
        assertEquals(listOf(4L), trash.observeEntries().first().map { it.photoId })
        assertEquals(setOf(4L), seen.observeSeenIds().first())
        assertEquals(listOf(4L), filing.observeEntries().first().map { it.photoId })
        assertEquals(listOf(1L), repo.observeFavoritedPhotos().first().map(PhotoItem::id))
        assertTrue(repo.observeAllPhotos().first().none { it.id == 2L })
        assertTrue(repo.observeTrashedPhotos().first().isEmpty())

        every { mediaStore.queryAllPhotos() } returns listOf(online, createTestPhoto(2), createTestPhoto(4))
        repo.refreshPhotos()
        assertEquals(listOf(1L, 2L), repo.observeFavoritedPhotos().first().map(PhotoItem::id))
        assertEquals(listOf(4L), repo.observeTrashedPhotos().first().map(PhotoItem::id))
        assertTrue(repo.observeAllPhotos().first().none { it.id == 2L || it.id == 4L })
    }

    @Test
    fun `真实删除且存在性查询完整时清掉记录`() = runBlocking {
        favorites.upsertEntries(mapOf(1L to 1L, 3L to 3L))
        every { mediaStore.queryAllPhotos() } returns listOf(createTestPhoto(1))
        every { mediaStore.queryPresence(any()) } returns MediaPresence(setOf(1L), authoritative = true)
        repo.refreshPhotos()
        assertEquals(listOf(1L), favorites.observeEntries().first().map { it.photoId })
    }

    @Test
    fun `存在性查询失败时不清理`() = runBlocking {
        favorites.upsertEntries(mapOf(3L to 1L))
        trash.upsertEntries(listOf(AppTrashEntry(photoId = 4L, movedToTrashAt = 10L)))
        every { mediaStore.queryAllPhotos() } returns emptyList()
        every { mediaStore.queryPresence(any()) } returns MediaPresence(emptySet(), authoritative = false)
        repo.refreshPhotos()
        assertEquals(listOf(3L), favorites.observeEntries().first().map { it.photoId })
        assertEquals(listOf(4L), trash.observeEntries().first().map { it.photoId })
    }

    @Test
    fun `部分照片权限时不查询存在性也不清记录`() = runBlocking {
        val denied = mockk<Context>(relaxed = true)
        every { denied.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_DENIED
        val limited = PhotoRepositoryImpl(
            context = denied,
            mediaStoreDataSource = mediaStore,
            appTrashStateDataSource = trash,
            appFavoriteStateDataSource = favorites,
            appSeenStateDataSource = seen,
            appFilingLogDataSource = filing,
            dataStore = dataStore,
        )
        favorites.upsertEntries(mapOf(3L to 1L))
        every { mediaStore.queryAllPhotos() } returns emptyList()
        limited.refreshPhotos()
        assertEquals(listOf(3L), favorites.observeEntries().first().map { it.photoId })
        verify(exactly = 0) { mediaStore.queryPresence(any()) }
    }
}

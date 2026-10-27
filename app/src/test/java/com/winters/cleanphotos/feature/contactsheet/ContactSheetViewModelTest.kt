package com.winters.cleanphotos.feature.contactsheet

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.winters.cleanphotos.TestPhotoData.createTestPhoto
import com.winters.cleanphotos.domain.model.FilingRecord
import com.winters.cleanphotos.domain.model.PhotoAlbum
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.model.SystemMediaActionRequest
import com.winters.cleanphotos.domain.model.SystemMediaActionType
import com.winters.cleanphotos.domain.usecase.BatchFavoritePhotosUseCase
import com.winters.cleanphotos.domain.usecase.BuildAlbumsUseCase
import com.winters.cleanphotos.domain.usecase.FilePhotosToAlbumUseCase
import com.winters.cleanphotos.domain.usecase.ObserveFilingLogUseCase
import com.winters.cleanphotos.domain.usecase.PrepareAlbumMoveUseCase
import com.winters.cleanphotos.domain.usecase.RequestSystemFavoriteUseCase
import com.winters.cleanphotos.domain.usecase.ResolveBatchFavoriteUseCase
import com.winters.cleanphotos.domain.usecase.UndoBatchActionUseCase
import com.winters.cleanphotos.domain.usecase.UndoFilingUseCase
import com.winters.cleanphotos.fake.FakeAlbumRepository
import com.winters.cleanphotos.fake.FakePhotoRepository
import com.winters.cleanphotos.fake.FakeReviewRepository
import com.winters.cleanphotos.fake.FakeSeenStateRepository
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 印样表：圈选、装袋前授权、成功飞入 / 失败留圈、撤回、整理完出小结。 */
class ContactSheetViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val photos = FakePhotoRepository()
    private val albums = FakeAlbumRepository()
    private val review = FakeReviewRepository()
    private val day = "October 24, 2026"
    private val travel = "Pictures/旅行/"

    private fun photo(id: Long, d: String = day): PhotoItem =
        createTestPhoto(id = id, dateGroup = d, isFavorite = true).copy(relativePath = "DCIM/Camera/")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // 旅行袋里已经有一张
        photos.setPhotos(listOf(createTestPhoto(id = 100, dateGroup = day).copy(relativePath = travel)))
        photos.setFavoritedPhotos(listOf(photo(1), photo(2), photo(3), photo(4, "October 22, 2026")))
        albums.onFiled = { moved ->
            val ids = moved.map { it.id }.toSet()
            val current = photos.currentFavorites()
            photos.setFavoritedPhotos(current.filterNot { it.id in ids })
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = ContactSheetViewModel(
        savedStateHandle = SavedStateHandle(mapOf("dayKey" to day)),
        photoRepository = photos,
        buildAlbums = BuildAlbumsUseCase(),
        observeFilingLog = ObserveFilingLogUseCase(albums),
        prepareMove = PrepareAlbumMoveUseCase(albums),
        filePhotos = FilePhotosToAlbumUseCase(albums),
        undoFiling = UndoFilingUseCase(albums),
        requestSystemFavorite = RequestSystemFavoriteUseCase(albums),
        batchFavorite = BatchFavoritePhotosUseCase(ResolveBatchFavoriteUseCase(), review, FakeSeenStateRepository()),
        undoBatch = UndoBatchActionUseCase(review),
        computeDispatcher = dispatcher,
    )

    private fun ContactSheetViewModel.travelAlbum(): PhotoAlbum = uiState.value.albums.first { it.relativePath == travel }

    @Test
    fun `只显示这一天的收藏_按拍摄时间正序`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 3L), vm.uiState.value.photos.map { it.id })
    }

    @Test
    fun `装袋成功_飞入_圈选清空_可撤回`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onToggle(1)
        vm.onToggle(2)
        vm.effects.test {
            vm.onFile(SheetTarget.Existing(vm.travelAlbum()))
            advanceUntilIdle()
            val flight = awaitItem() as ContactSheetEffect.Flight
            assertEquals(listOf(1L, 2L), flight.photos.map { it.id })
            assertEquals(travel, flight.relativePath)
        }
        val s = vm.uiState.value
        assertTrue(s.circled.isEmpty())
        assertEquals(listOf(3L), s.photos.map { it.id })
        assertNotNull(s.undo)
        vm.onUndo()
        advanceUntilIdle()
        assertEquals(1, albums.undone.size)
    }

    @Test
    fun `系统授权被拒_照片叠收起_什么都不动`() = runTest(dispatcher) {
        albums.prepareResult = PhotoOperationResult.NeedSystemApproval(
            SystemMediaActionRequest(emptyList(), SystemMediaActionType.MoveToAlbum, "t", mockk(relaxed = true)),
        )
        val vm = vm()
        advanceUntilIdle()
        vm.onToggle(1)
        vm.effects.test {
            vm.onFile(SheetTarget.Existing(vm.travelAlbum()))
            advanceUntilIdle()
            assertTrue(awaitItem() is ContactSheetEffect.LaunchSystemAction)
            vm.onSystemMediaActionResult(approved = false)
            assertEquals(ContactSheetEffect.Bounce, awaitItem())
        }
        assertTrue(albums.filed.isEmpty())
        assertEquals(setOf(1L), vm.uiState.value.circled)
    }

    @Test
    fun `没放进去的照片保持圈选并提示`() = runTest(dispatcher) {
        albums.failIds = setOf(2L)
        val vm = vm()
        advanceUntilIdle()
        vm.onToggle(1)
        vm.onToggle(2)
        vm.onFile(SheetTarget.Existing(vm.travelAlbum()))
        advanceUntilIdle()
        assertEquals(setOf(2L), vm.uiState.value.circled)
        assertNotNull(vm.uiState.value.message)
    }

    @Test
    fun `新袋子同名时合并进已有文件夹`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onToggle(1)
        vm.onFile(SheetTarget.New("旅行"))
        advanceUntilIdle()
        assertEquals(travel, albums.filed.single().second)
    }

    @Test
    fun `新袋子和 DCIM 下的相册同名_放进那个相册_不算新建`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onCircleAll()
        vm.onFile(SheetTarget.New(" camera "))
        advanceUntilIdle()
        assertEquals("DCIM/Camera/", albums.filed.single().second)
        val summary = requireNotNull(vm.uiState.value.summary)
        assertEquals("DCIM/Camera/", summary.albums.single().relativePath)
        assertTrue(!summary.albums.single().created)
    }

    @Test
    fun `这一天整理完出小结_下一天是更早的一天`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertNull(vm.uiState.value.summary)
        vm.onCircleAll()
        vm.onFile(SheetTarget.New("秋天的海"))
        advanceUntilIdle()
        val summary = requireNotNull(vm.uiState.value.summary)
        assertEquals("Pictures/秋天的海/", summary.albums.single().relativePath)
        assertTrue(summary.albums.single().created)
        assertEquals("October 22, 2026", summary.next?.dayKey)
    }

    @Test
    fun `已经在目标文件夹的照片_不为它弹授权_也照常算装好`() = runTest(dispatcher) {
        albums.prepareResult = PhotoOperationResult.NeedSystemApproval(
            SystemMediaActionRequest(emptyList(), SystemMediaActionType.MoveToAlbum, "t", mockk(relaxed = true)),
        )
        val vm = vm()
        advanceUntilIdle()
        val camera = vm.uiState.value.albums.first { it.relativePath == "DCIM/Camera/" }
        vm.onToggle(1)
        vm.effects.test {
            vm.onFile(SheetTarget.Existing(camera))
            advanceUntilIdle()
            assertTrue(awaitItem() is ContactSheetEffect.Flight)
        }
        assertTrue(albums.prepared.isEmpty())
        assertEquals(listOf(listOf(1L) to "DCIM/Camera/"), albums.filed)
        // 撤回：原本就在这个文件夹，也不用授权
        vm.onUndo()
        advanceUntilIdle()
        assertTrue(albums.prepared.isEmpty())
        assertEquals(1, albums.undone.size)
    }

    @Test
    fun `混着已在目标的照片时_只为真要移动的弹授权`() = runTest(dispatcher) {
        photos.setFavoritedPhotos(listOf(photo(1), photo(2).copy(relativePath = travel)))
        val vm = vm()
        advanceUntilIdle()
        vm.onCircleAll()
        vm.onFile(SheetTarget.Existing(vm.travelAlbum()))
        advanceUntilIdle()
        assertEquals(listOf(listOf(1L)), albums.prepared)
        assertEquals(listOf(1L, 2L), albums.filed.single().first.sorted())
    }

    /** 旅行 1 张、Camera 4 张，再加 A1..A11（29..19 张）：共 13 个相册，托盘露 A1..A8。 */
    private fun manyAlbums() {
        val extra = (1..11).flatMap { i -> (0 until 30 - i).map { k -> createTestPhoto(id = 1000L + i * 100 + k, dateGroup = day).copy(relativePath = "Pictures/A$i/") } }
        photos.setPhotos(listOf(createTestPhoto(id = 100, dateGroup = day).copy(relativePath = travel)) + extra)
    }

    @Test
    fun `托盘露 8 只_其余算进还有 N 个`() = runTest(dispatcher) {
        manyAlbums()
        val vm = vm()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(13, s.albums.size)
        assertEquals(5, s.moreCount)
        assertEquals((1..8).map { "Pictures/A$it/" }, s.tray.take(TrayRules.LIMIT).map { it.relativePath })
    }

    @Test
    fun `本次进入托盘位置固定_装进托盘里的相册不换位`() = runTest(dispatcher) {
        manyAlbums()
        val vm = vm()
        advanceUntilIdle()
        val before = vm.uiState.value.tray.map { it.relativePath }
        vm.onToggle(1)
        vm.onFile(SheetTarget.Existing(vm.uiState.value.albums.first { it.relativePath == "Pictures/A5/" }))
        albums.log.value = listOf(FilingRecord(1, "Pictures/A5/", day, filedAt = 10))
        advanceUntilIdle()
        vm.onFlightDone("Pictures/A5/")
        // 面板列表按最近使用排（A5 到最前），托盘不动
        assertEquals("Pictures/A5/", vm.uiState.value.albums.first().relativePath)
        assertEquals(before, vm.uiState.value.tray.map { it.relativePath })
    }

    @Test
    fun `从面板装进托盘外的相册_飞入播完才插到第一格`() = runTest(dispatcher) {
        manyAlbums()
        val vm = vm()
        advanceUntilIdle()
        val before = vm.uiState.value.tray.map { it.relativePath }
        vm.onToggle(1)
        vm.onFile(SheetTarget.Existing(vm.travelAlbum()))
        albums.log.value = listOf(FilingRecord(1, travel, day, filedAt = 10))
        advanceUntilIdle()
        // 飞入期间托盘顺序不变
        assertEquals(before, vm.uiState.value.tray.map { it.relativePath })
        vm.onFlightDone(travel)
        val after = vm.uiState.value.tray.map { it.relativePath }
        assertEquals(travel, after.first())
        assertEquals(before.take(7), after.drop(1).take(7))
        assertEquals(5, vm.uiState.value.moreCount)
    }

    @Test
    fun `下次进入按最近使用重新排`() = runTest(dispatcher) {
        manyAlbums()
        albums.log.value = listOf(FilingRecord(1, travel, day, filedAt = 10))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(travel, vm.uiState.value.tray.first().relativePath)
    }
}

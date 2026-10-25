package com.winters.cleanphotos.feature.allphotos

import com.winters.cleanphotos.R
import com.winters.cleanphotos.core.text.UiText
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import app.cash.turbine.test
import com.winters.cleanphotos.TestPhotoData
import com.winters.cleanphotos.fake.FakeSeenStateRepository
import com.winters.cleanphotos.domain.model.MediaType
import com.winters.cleanphotos.domain.model.PhotoSortOrder
import com.winters.cleanphotos.domain.model.ReviewScope
import com.winters.cleanphotos.domain.model.SectionHeader
import com.winters.cleanphotos.domain.model.SmartFilterRules
import com.winters.cleanphotos.domain.usecase.BuildPhotoSectionsUseCase
import com.winters.cleanphotos.domain.usecase.ObserveAllSeenStatsUseCase
import com.winters.cleanphotos.domain.usecase.ObserveSeenIdsUseCase
import com.winters.cleanphotos.domain.usecase.ObserveTrashSummaryUseCase
import com.winters.cleanphotos.domain.usecase.FilterPhotosUseCase
import com.winters.cleanphotos.domain.usecase.ObserveAllPhotosUseCase
import com.winters.cleanphotos.domain.usecase.SortPhotosUseCase
import com.winters.cleanphotos.domain.usecase.StartReviewSessionUseCase
import com.winters.cleanphotos.fake.FakePhotoRepository
import com.winters.cleanphotos.fake.FakeReviewRepository
import com.winters.cleanphotos.domain.usecase.BatchFavoritePhotosUseCase
import com.winters.cleanphotos.domain.usecase.BatchMovePhotosToTrashUseCase
import com.winters.cleanphotos.domain.usecase.ResolveBatchFavoriteUseCase
import com.winters.cleanphotos.domain.usecase.UndoBatchActionUseCase
import org.junit.Assert.assertTrue
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * AllPhotosViewModel 单元测试。
 *
 * 由于 ViewModel 继承 AndroidViewModel 且内部使用 PermissionChecker（依赖 ContextCompat），
 * 使用 MockK 模拟 ContextCompat.checkSelfPermission 来隔离 Android 依赖。
 * 同时模拟 Uri.parse 避免 Android framework 调用。
 * 使用 FakePhotoRepository 提供可控的测试数据。
 * AppSeenStateDataSource 使用 relaxed mock 模拟，因为测试不关注已看过状态。
 *
 * 需要将 Dispatchers.Main 替换为 TestDispatcher 来控制 viewModelScope 的协程执行。
 */
class AllPhotosViewModelTest {

    /** 测试用的协程调度器 */
    private val testDispatcher: TestDispatcher = StandardTestDispatcher()

    /** Fake 仓库，提供可控的测试数据 */
    private lateinit var fakePhotoRepository: FakePhotoRepository

    /** 模拟的 AppSeenStateDataSource */
    private lateinit var mockSeenDataSource: FakeSeenStateRepository

    /** 模拟的 Application 上下文 */
    private lateinit var mockApplication: Application

    @Before
    fun setUp() {
        fakePhotoRepository = FakePhotoRepository()
        mockApplication = mockk<Application>(relaxed = true)

        // AppSeenStateDataSource 使用 relaxed mock，默认返回空集合
        mockSeenDataSource = FakeSeenStateRepository()

        // 将 Main 调度器替换为测试调度器，使 viewModelScope 可控
        Dispatchers.setMain(testDispatcher)

        // Mock ContextCompat.checkSelfPermission，避免真实 Android API 调用
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(any<Context>(), any<String>())
        } returns PackageManager.PERMISSION_GRANTED

        // Mock Uri.parse，避免真实 Android API 调用
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers {
            mockk<Uri>(relaxed = true)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(ContextCompat::class)
        unmockkStatic(Uri::class)
    }

    /**
     * 创建 ViewModel 实例。
     * FilterPhotosUseCase 需要传入 mockSeenDataSource。
     */
    private fun createViewModel(): AllPhotosViewModel {
        return AllPhotosViewModel(
            application = mockApplication,
            photoRepository = fakePhotoRepository,
            observeAllPhotosUseCase = ObserveAllPhotosUseCase(fakePhotoRepository),
            sortPhotosUseCase = SortPhotosUseCase(),
            filterPhotosUseCase = FilterPhotosUseCase(mockSeenDataSource),
            buildPhotoSectionsUseCase = BuildPhotoSectionsUseCase(),
            observeSeenIdsUseCase = ObserveSeenIdsUseCase(mockSeenDataSource),
            observeAllSeenStatsUseCase = ObserveAllSeenStatsUseCase(
                fakePhotoRepository, ObserveTrashSummaryUseCase(fakePhotoRepository),
            ),
            batchFavoritePhotosUseCase = BatchFavoritePhotosUseCase(ResolveBatchFavoriteUseCase(), fakeReviewRepository, mockSeenDataSource),
            batchMovePhotosToTrashUseCase = BatchMovePhotosToTrashUseCase(fakeReviewRepository, mockSeenDataSource),
            undoBatchActionUseCase = UndoBatchActionUseCase(fakeReviewRepository),
            computeDispatcher = testDispatcher,
        )
    }

    private val fakeReviewRepository = FakeReviewRepository()

    // ====== 多选 ======

    @Test
    fun `长按进入多选_点按切换_取消到一张不剩退出`() = runTest(testDispatcher) {
        fakePhotoRepository.setPhotos(TestPhotoData.createTestPhotos(5))
        val vm = createViewModel(); advanceUntilIdle()
        vm.onLongPressPhoto(2L)
        assertEquals(setOf(2L), vm.selection.value.selectedIds)
        assertTrue(vm.selection.value.isSelecting)
        vm.onToggleSelection(3L)
        assertEquals(setOf(2L, 3L), vm.selection.value.selectedIds)
        vm.onToggleSelection(2L); vm.onToggleSelection(3L)
        assertFalse(vm.selection.value.isSelecting)
    }

    @Test
    fun `返回或叉号退出多选`() = runTest(testDispatcher) {
        fakePhotoRepository.setPhotos(TestPhotoData.createTestPhotos(5))
        val vm = createViewModel(); advanceUntilIdle()
        vm.onLongPressPhoto(1L); vm.onToggleSelection(4L)
        vm.onExitSelection()
        assertFalse(vm.selection.value.isSelecting)
    }

    @Test
    fun `拖选_长按前已选并上起点到当前格的区间`() = runTest(testDispatcher) {
        fakePhotoRepository.setPhotos(TestPhotoData.createTestPhotos(8))
        val vm = createViewModel(); advanceUntilIdle()
        val order = vm.uiState.value.sections.flatMap { it.photos }.map { it.id }
        vm.onLongPressPhoto(order[6])
        vm.onLongPressPhoto(order[1])
        vm.onDragSelect(order[1], order[3])
        assertEquals(setOf(order[6], order[1], order[2], order[3]), vm.selection.value.selectedIds)
        // 往回拖：区间收窄，长按前已选的仍在
        vm.onDragSelect(order[1], order[1])
        assertEquals(setOf(order[6], order[1]), vm.selection.value.selectedIds)
    }

    @Test
    fun `长按没生效或退出多选后_旧起点的拖选回调被忽略`() = runTest(testDispatcher) {
        fakePhotoRepository.setPhotos(TestPhotoData.createTestPhotos(8))
        val vm = createViewModel(); advanceUntilIdle()
        val order = vm.uiState.value.sections.flatMap { it.photos }.map { it.id }
        assertTrue(vm.onLongPressPhoto(order[0]))
        vm.onDragSelect(order[0], order[2])
        vm.onExitSelection()
        // 退出后同一手势残留的拖动回调：不能用旧的起点集合重新选中
        vm.onDragSelect(order[0], order[4])
        assertFalse(vm.selection.value.isSelecting)
        // 不在列表里的照片长按不生效，返回 false
        assertFalse(vm.onLongPressPhoto(999L))
        vm.onDragSelect(999L, order[3])
        assertFalse(vm.selection.value.isSelecting)
    }

    @Test
    fun `全选这一天_再点取消这一天`() = runTest(testDispatcher) {
        fakePhotoRepository.setPhotos(TestPhotoData.createMultiDateTestPhotos())
        val vm = createViewModel(); advanceUntilIdle()
        val day = vm.uiState.value.sections.first { it.photos.size > 1 }
        val other = vm.uiState.value.sections.first { it.dayKey != day.dayKey }
        vm.onLongPressPhoto(other.photos.first().id)
        vm.onToggleDay(day.dayKey)
        assertEquals(day.selectableIds.toSet() + other.photos.first().id, vm.selection.value.selectedIds)
        vm.onToggleDay(day.dayKey)
        assertEquals(setOf(other.photos.first().id), vm.selection.value.selectedIds)
    }

    @Test
    fun `混合收藏_按钮显示收藏_撤回胶囊写实际张数并退出多选`() = runTest(testDispatcher) {
        val photos = listOf(
            TestPhotoData.createTestPhoto(1, isFavorite = true),
            TestPhotoData.createTestPhoto(2),
            TestPhotoData.createTestPhoto(3),
        )
        fakePhotoRepository.setPhotos(photos)
        val vm = createViewModel(); advanceUntilIdle()
        vm.onLongPressPhoto(1L); vm.onToggleSelection(2L); vm.onToggleSelection(3L)
        assertFalse(vm.selection.value.allSelectedFavorite)
        vm.onBatchFavorite(); advanceUntilIdle()
        val undo = vm.selection.value.undo!!
        assertEquals(UiText.Plural(R.plurals.batch_undo_favorite, 2), undo.title)
        assertFalse(vm.selection.value.isSelecting)
        assertEquals(setOf(1L, 2L, 3L), mockSeenDataSource.seenIds.value)
        vm.onUndoBatch(); advanceUntilIdle()
        assertEquals(listOf("favorite" to setOf(2L, 3L), "unfavorite" to setOf(2L, 3L)), fakeReviewRepository.batchCalls.map { it.first to it.second.toSet() })
        assertNull(vm.selection.value.undo)
    }

    @Test
    fun `全部已收藏_变成取消收藏`() = runTest(testDispatcher) {
        fakePhotoRepository.setPhotos(listOf(TestPhotoData.createTestPhoto(1, isFavorite = true), TestPhotoData.createTestPhoto(2, isFavorite = true)))
        val vm = createViewModel(); advanceUntilIdle()
        vm.onLongPressPhoto(1L); vm.onToggleSelection(2L)
        assertTrue(vm.selection.value.allSelectedFavorite)
        vm.onBatchFavorite(); advanceUntilIdle()
        assertEquals(UiText.Plural(R.plurals.batch_undo_unfavorite, 2), vm.selection.value.undo!!.title)
    }

    @Test
    fun `移入垃圾桶_含收藏全部移走_撤回整批恢复`() = runTest(testDispatcher) {
        fakePhotoRepository.setPhotos(listOf(TestPhotoData.createTestPhoto(1, isFavorite = true), TestPhotoData.createTestPhoto(2)))
        val vm = createViewModel(); advanceUntilIdle()
        vm.onLongPressPhoto(1L); vm.onToggleSelection(2L)
        vm.onBatchMoveToTrash(); advanceUntilIdle()
        assertEquals(UiText.Plural(R.plurals.batch_undo_trash, 2), vm.selection.value.undo!!.title)
        vm.onUndoBatch(); advanceUntilIdle()
        assertEquals(listOf("trash" to setOf(1L, 2L), "restore" to setOf(1L, 2L)), fakeReviewRepository.batchCalls.map { it.first to it.second.toSet() })
    }

    @Test
    fun `照片从列表消失时选中集合跟着收缩_清空即退出`() = runTest(testDispatcher) {
        fakePhotoRepository.setPhotos(TestPhotoData.createTestPhotos(3))
        val vm = createViewModel(); advanceUntilIdle()
        vm.onLongPressPhoto(1L); vm.onToggleSelection(2L)
        fakePhotoRepository.setPhotos(TestPhotoData.createTestPhotos(3).filter { it.id == 3L })
        advanceUntilIdle()
        assertFalse(vm.selection.value.isSelecting)
    }

    @Test
    fun `撤回胶囊过期只清掉同一序号`() = runTest(testDispatcher) {
        fakePhotoRepository.setPhotos(TestPhotoData.createTestPhotos(3))
        val vm = createViewModel(); advanceUntilIdle()
        vm.onLongPressPhoto(1L); vm.onBatchFavorite(); advanceUntilIdle()
        val seq = vm.selection.value.undo!!.seq
        vm.onUndoExpired(seq - 1)
        assertEquals(seq, vm.selection.value.undo?.seq)
        vm.onUndoExpired(seq)
        assertNull(vm.selection.value.undo)
    }

    // ====== 测试用例 ======

    @Test
    fun `初始化后_有权限时_加载照片并显示日期分组`() = runTest(testDispatcher) {
        // 准备：预设 3 个不同日期的照片（共 14 张）
        val photos = TestPhotoData.createMultiDateTestPhotos()
        fakePhotoRepository.setPhotos(photos)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // 验证：UiState 应包含正确的日期分组数据
        assertEquals(photos.size, viewModel.uiState.value.totalCount)
        assertEquals(3, viewModel.uiState.value.sections.size)
        assertEquals(true, viewModel.uiState.value.hasPermission)
        assertNull("无错误信息", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `没有权限时_UiState显示无权限状态`() = runTest(testDispatcher) {
        // 准备：模拟无权限
        every {
            ContextCompat.checkSelfPermission(any<Context>(), any<String>())
        } returns PackageManager.PERMISSION_DENIED

        val viewModel = createViewModel()
        advanceUntilIdle()

        // 验证：UiState 应标记为无权限
        assertEquals("hasPermission 应为 false", false, viewModel.uiState.value.hasPermission)
    }

    @Test
    fun `刷新照片失败时_保留错误信息而不被combine覆盖`() = runTest(testDispatcher) {
        // 准备：设置刷新时抛异常，但预设照片数据
        // 即使 observePhotos 的 combine 发射旧数据，也不应清掉错误提示
        fakePhotoRepository.shouldThrowOnRefresh = true
        fakePhotoRepository.setPhotos(TestPhotoData.createTestPhotos(3))

        val viewModel = createViewModel()
        advanceUntilIdle()

        // 验证：错误信息应保留，允许界面显示失败提示与重试入口
        val state = viewModel.uiState.value
        assertEquals("totalCount 应等于照片数量", 3, state.totalCount)
        assertEquals("hasPermission 应为 true", true, state.hasPermission)
        assertEquals("errorMessage 应保留加载失败提示", UiText.Res(R.string.error_load_photos), state.errorMessage)
    }

    @Test
    fun `onPermissionResult授予权限后_触发刷新`() = runTest(testDispatcher) {
        // 准备：先设置为无权限状态，不预设照片
        every {
            ContextCompat.checkSelfPermission(any<Context>(), any<String>())
        } returns PackageManager.PERMISSION_DENIED

        val viewModel = createViewModel()
        advanceUntilIdle()

        // 验证初始无权限
        assertFalse("初始 hasPermission 应为 false", viewModel.uiState.value.hasPermission)

        // 授予权限并设置照片数据
        every {
            ContextCompat.checkSelfPermission(any<Context>(), any<String>())
        } returns PackageManager.PERMISSION_GRANTED

        val photos = TestPhotoData.createTestPhotos(5)
        fakePhotoRepository.setPhotos(photos)

        viewModel.onPermissionResult(granted = true)
        advanceUntilIdle()

        // 验证：刷新后 hasPermission 为 true
        val state = viewModel.uiState.value
        assertEquals("hasPermission 应为 true", true, state.hasPermission)
    }

    @Test
    fun `智能筛选数量按全部照片计算_筛选结果不含未看过滤`() = runTest(testDispatcher) {
        val min = SmartFilterRules.LARGE_FILE_MIN_BYTES
        val photos = listOf(
            TestPhotoData.createTestPhoto(1).copy(relativePath = "Pictures/Screenshots/", size = 100),
            TestPhotoData.createTestPhoto(2).copy(bucketName = "截图", size = min),
            TestPhotoData.createTestPhoto(3).copy(relativePath = "DCIM/Camera/", size = min, mediaType = MediaType.Video),
            TestPhotoData.createTestPhoto(4).copy(relativePath = "Pictures/QQ/"),
            TestPhotoData.createTestPhoto(5).copy(relativePath = "DCIM/Camera/", bucketName = "Camera", size = min - 1),
        )
        fakePhotoRepository.setPhotos(photos)
        mockSeenDataSource.seenIds.value = setOf(1L)
        val vm = createViewModel()
        advanceUntilIdle()

        val counted = vm.uiState.value
        assertEquals(5, counted.totalCount)
        assertEquals(5, counted.scopeCount)
        assertEquals(5, counted.visibleCount)

        vm.onScopeChanged(LibraryScope.Screenshots)
        advanceUntilIdle()
        val shots = vm.uiState.value
        assertEquals(LibraryScope.Screenshots, shots.scope)
        assertEquals(listOf(2L, 1L), shots.sections.flatMap { it.photos }.map { it.id })
        assertEquals(2, shots.scopeCount)
        assertEquals(2, shots.visibleCount)
        assertEquals(100L + min, shots.visibleBytes)

        vm.onShowUnseenChanged(true)
        advanceUntilIdle()
        val unseenShots = vm.uiState.value
        assertEquals(listOf(2L), unseenShots.sections.flatMap { it.photos }.map { it.id })
        assertEquals(2, unseenShots.scopeCount)
        assertEquals(1, unseenShots.visibleCount)
        assertEquals(min, unseenShots.visibleBytes)

        vm.onScopeChanged(LibraryScope.LargeFiles)
        advanceUntilIdle()
        val large = vm.uiState.value
        assertEquals(listOf(3L, 2L), large.sections.flatMap { it.photos }.map { it.id })
        assertEquals(min + min, large.visibleBytes)

        vm.onScopeChanged(LibraryScope.ChatApps)
        advanceUntilIdle()
        val chat = vm.uiState.value
        assertEquals(listOf(4L), chat.sections.flatMap { it.photos }.map { it.id })
        assertEquals(TestPhotoData.createTestPhoto(4).size ?: 0L, chat.visibleBytes)

        vm.onScopeChanged(LibraryScope.Similar)
        advanceUntilIdle()
        val similar = vm.uiState.value
        assertEquals("相似不吃只看没看过", 5, similar.visibleCount)
        assertEquals(5, similar.scopeCount)

        vm.onScopeChanged(LibraryScope.All)
        vm.onShowUnseenChanged(false)
        advanceUntilIdle()
        assertEquals(LibraryScope.All, vm.uiState.value.scope)
        assertEquals(5, vm.uiState.value.visibleCount)
    }

    @Test
    fun `副文字大小合计把 null 记成 0`() = runTest(testDispatcher) {
        val photos = listOf(
            TestPhotoData.createTestPhoto(1).copy(relativePath = "Pictures/Screenshots/", size = null),
            TestPhotoData.createTestPhoto(2).copy(relativePath = "Pictures/Screenshots/", size = 20L),
        )
        fakePhotoRepository.setPhotos(photos)
        val vm = createViewModel()
        advanceUntilIdle()
        vm.onScopeChanged(LibraryScope.Screenshots)
        advanceUntilIdle()
        assertEquals(20L, vm.uiState.value.visibleBytes)
        assertEquals(2, vm.uiState.value.visibleCount)
    }

    @Test
    fun `那年今天按钮_数量为0且未选中时隐藏_选中时仍显示`() {
        val hidden = AllPhotosUiState(onThisDayCount = 0, scope = LibraryScope.All)
        assertFalse(hidden.showOnThisDay)
        assertTrue(hidden.copy(scope = LibraryScope.OnThisDay).showOnThisDay)
        assertTrue(hidden.copy(onThisDayCount = 2).showOnThisDay)
    }

    @Test
    fun `那年今天数量按全部照片计算_已看过仍计入_筛选只留命中项`() = runTest(testDispatcher) {
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        fun at(date: java.time.LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val photos = listOf(
            TestPhotoData.createTestPhoto(1).copy(dateTaken = at(previousSameDay(today, 1))),
            TestPhotoData.createTestPhoto(2).copy(dateTaken = at(previousSameDay(today, 2)), mediaType = MediaType.Video),
            TestPhotoData.createTestPhoto(3).copy(dateTaken = at(today)),
            TestPhotoData.createTestPhoto(4).copy(dateTaken = at(today.minusDays(3))),
            TestPhotoData.createTestPhoto(5).copy(dateTaken = 0L),
        )
        fakePhotoRepository.setPhotos(photos)
        mockSeenDataSource.seenIds.value = setOf(1L)
        val vm = createViewModel()
        advanceUntilIdle()

        val counted = vm.uiState.value
        assertEquals(2, counted.onThisDayCount)
        assertTrue(counted.showOnThisDay)
        assertEquals(5, counted.totalCount)

        vm.onScopeChanged(LibraryScope.OnThisDay)
        advanceUntilIdle()
        val filtered = vm.uiState.value
        assertEquals(LibraryScope.OnThisDay, filtered.scope)
        assertEquals(listOf(1L, 2L), filtered.sections.flatMap { it.photos }.map { it.id })
        assertEquals(2, filtered.scopeCount)
        assertEquals(2, filtered.visibleCount)
        assertEquals(2, filtered.onThisDayCount)
        assertTrue(filtered.showOnThisDay)

        vm.onShowUnseenChanged(true)
        advanceUntilIdle()
        val unseen = vm.uiState.value
        assertEquals(listOf(2L), unseen.sections.flatMap { it.photos }.map { it.id })
        assertEquals(2, unseen.scopeCount)
        assertEquals(1, unseen.visibleCount)
    }

    @Test
    fun `一天三十张_全选三十张_拖选只含露出的格子_批量移入垃圾桶传三十张`() = runTest(testDispatcher) {
        val photos = (1L..30L).map { TestPhotoData.createTestPhoto(it, dateGroup = "2024-05-20") }
        fakePhotoRepository.setPhotos(photos)
        val vm = createViewModel(); advanceUntilIdle()
        val day = vm.uiState.value.sections.single()
        val shown = day.photos.map { it.id }
        assertEquals((30L downTo 23L).toList(), shown)
        assertEquals((30L downTo 1L).toList(), day.selectableIds)

        vm.onLongPressPhoto(shown.last())
        vm.onDragSelect(shown.last(), shown.first())
        assertEquals(shown.toSet(), vm.selection.value.selectedIds)

        vm.onExitSelection()
        vm.onToggleDay(day.dayKey)
        assertEquals(30, vm.selection.value.count)
        vm.onBatchMoveToTrash(); advanceUntilIdle()
        assertEquals(listOf("trash" to (30L downTo 1L).toList()), fakeReviewRepository.batchCalls)
    }

    @Test
    fun `大文件按大小排序_菜单排序保留_切回后生效`() = runTest(testDispatcher) {
        val min = SmartFilterRules.LARGE_FILE_MIN_BYTES
        val photos = listOf(
            TestPhotoData.createTestPhoto(1).copy(size = min + 10, dateTaken = 3_000, dateGroup = "2024-05-20"),
            TestPhotoData.createTestPhoto(2).copy(size = min + 50, dateTaken = 1_000, dateGroup = "2024-05-18"),
            TestPhotoData.createTestPhoto(3).copy(size = 100, dateTaken = 5_000, dateGroup = "2024-05-21"),
        )
        fakePhotoRepository.setPhotos(photos)
        val vm = createViewModel(); advanceUntilIdle()
        vm.onScopeChanged(LibraryScope.LargeFiles)
        advanceUntilIdle()
        val large = vm.uiState.value
        val section = large.sections.single()
        assertEquals(listOf(2L, 1L), section.photos.map { it.id })
        assertEquals(SectionHeader.None, section.header)
        assertNull(section.yearHeader)
        assertNull(section.date)
        assertEquals(PhotoSortOrder.NewestFirst, large.sortOrder)
        assertEquals(PhotoSortOrder.LargestFirst, large.reviewSortOrder)
        assertFalse(large.scope.showsSortMenu)

        vm.onSortOrderChanged(PhotoSortOrder.OldestFirst)
        advanceUntilIdle()
        val stillLarge = vm.uiState.value
        assertEquals(listOf(2L, 1L), stillLarge.sections.single().photos.map { it.id })
        assertEquals(PhotoSortOrder.OldestFirst, stillLarge.sortOrder)
        assertEquals(PhotoSortOrder.LargestFirst, stillLarge.reviewSortOrder)

        vm.onScopeChanged(LibraryScope.All)
        advanceUntilIdle()
        val all = vm.uiState.value
        assertEquals(listOf(2L, 1L, 3L), all.sections.flatMap { it.photos }.map { it.id })
        assertEquals(PhotoSortOrder.OldestFirst, all.sortOrder)
        assertEquals(PhotoSortOrder.OldestFirst, all.reviewSortOrder)
        assertTrue(all.scope.showsSortMenu)
    }

    @Test
    fun `截图按月分组且超过八张不截断`() = runTest(testDispatcher) {
        val zone = java.time.ZoneId.of("UTC")
        fun shot(id: Long, date: java.time.LocalDate) = TestPhotoData.createTestPhoto(id, date.toString()).copy(
            relativePath = "Pictures/Screenshots/",
            dateTaken = date.atStartOfDay(zone).toInstant().toEpochMilli() + id,
        )
        val photos = (1L..10L).map { shot(it, java.time.LocalDate.of(2026, 10, it.toInt())) } +
            (11L..13L).map { shot(it, java.time.LocalDate.of(2025, 9, (it - 10).toInt())) } +
            TestPhotoData.createTestPhoto(14).copy(relativePath = "DCIM/Camera/", dateTaken = 9_000_000_000_000L)
        fakePhotoRepository.setPhotos(photos)
        val vm = createViewModel(); advanceUntilIdle()
        vm.onScopeChanged(LibraryScope.Screenshots)
        advanceUntilIdle()
        val sections = vm.uiState.value.sections
        assertEquals(listOf("2026-10", "2025-09"), sections.map { it.dayKey })
        assertEquals(SectionHeader.Month, sections[0].header)
        assertEquals((10L downTo 1L).toList(), sections[0].photos.map { it.id })
        assertEquals(10, sections[0].selectableIds.size)
        assertEquals(10, sections[0].totalCount)
        assertEquals(2026, sections[0].yearHeader)
        assertEquals(2025, sections[1].yearHeader)
        assertEquals(listOf(13L, 12L, 11L), sections[1].photos.map { it.id })
    }

    @Test
    fun `那年今天仍按天截断到八张_全选含全部`() = runTest(testDispatcher) {
        val zone = java.time.ZoneId.systemDefault()
        val day = previousSameDay(java.time.LocalDate.now(zone), 1)
        val photos = (1L..10L).map {
            TestPhotoData.createTestPhoto(it, day.toString()).copy(
                dateTaken = day.atStartOfDay(zone).toInstant().toEpochMilli() + it * 1000,
            )
        }
        fakePhotoRepository.setPhotos(photos)
        val vm = createViewModel(); advanceUntilIdle()
        vm.onScopeChanged(LibraryScope.OnThisDay)
        advanceUntilIdle()
        val section = vm.uiState.value.sections.single()
        assertEquals(SectionHeader.Day, section.header)
        assertEquals((10L downTo 3L).toList(), section.photos.map { it.id })
        assertEquals((10L downTo 1L).toList(), section.selectableIds)
        vm.onToggleDay(section.dayKey)
        assertEquals(10, vm.selection.value.count)
    }

    @Test
    fun `网格顺序与清理页顺序一致`() = runTest(testDispatcher) {
        fakePhotoRepository.setPhotos(TestPhotoData.createMultiDateTestPhotos())
        val vm = createViewModel(); advanceUntilIdle()
        val newest = vm.uiState.value
        val shown = newest.sections.flatMap { it.photos }.map { it.id }
        val full = newest.sections.flatMap { it.selectableIds }
        assertEquals((14L downTo 1L).toList(), full)
        assertTrue(shown.size < full.size)
        assertReviewMatchesGrid(vm)

        vm.onSortOrderChanged(PhotoSortOrder.OldestFirst)
        advanceUntilIdle()
        assertEquals((1L..14L).toList(), vm.uiState.value.sections.flatMap { it.selectableIds })
        assertReviewMatchesGrid(vm)

        mockSeenDataSource.seenIds.value = setOf(14L, 10L, 1L)
        vm.onShowUnseenChanged(true)
        advanceUntilIdle()
        assertReviewMatchesGrid(vm)

        val min = SmartFilterRules.LARGE_FILE_MIN_BYTES
        fakePhotoRepository.setPhotos(
            listOf(
                TestPhotoData.createTestPhoto(1).copy(size = min + 100, dateTaken = 1_000, dateGroup = "2024-05-18"),
                TestPhotoData.createTestPhoto(2).copy(size = min + 500, dateTaken = 9_000, dateGroup = "2024-05-20"),
                TestPhotoData.createTestPhoto(3).copy(size = 100, dateTaken = 5_000, dateGroup = "2024-05-19"),
            ),
        )
        vm.onShowUnseenChanged(false)
        vm.onScopeChanged(LibraryScope.LargeFiles)
        advanceUntilIdle()
        val large = vm.uiState.value
        assertEquals(PhotoSortOrder.OldestFirst, large.sortOrder)
        assertEquals(PhotoSortOrder.LargestFirst, large.reviewSortOrder)
        assertEquals(listOf(2L, 1L), large.sections.flatMap { it.selectableIds })
        assertReviewMatchesGrid(vm)

        fakePhotoRepository.setPhotos(
            listOf(
                TestPhotoData.createTestPhoto(1, "2026-10-02").copy(relativePath = "Pictures/Screenshots/", dateTaken = 3_000),
                TestPhotoData.createTestPhoto(2, "2025-09-02").copy(relativePath = "Pictures/Screenshots/", dateTaken = 1_000),
                TestPhotoData.createTestPhoto(3, "2026-10-03").copy(relativePath = "DCIM/Camera/", dateTaken = 9_000),
            ),
        )
        vm.onSortOrderChanged(PhotoSortOrder.NewestFirst)
        vm.onScopeChanged(LibraryScope.Screenshots)
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), vm.uiState.value.sections.flatMap { it.selectableIds })
        assertReviewMatchesGrid(vm)
    }

    private suspend fun assertReviewMatchesGrid(vm: AllPhotosViewModel) {
        val state = vm.uiState.value
        val gridOrder = state.sections.flatMap { it.selectableIds }
        val session = StartReviewSessionUseCase(
            fakePhotoRepository,
            SortPhotosUseCase(),
            FilterPhotosUseCase(mockSeenDataSource),
        ).invoke(
            startPhotoId = gridOrder.first(),
            sortOrder = state.reviewSortOrder,
            filterMode = state.scope.toFilterMode(),
            scope = ReviewScope.AllPhotos,
            unseenOnly = state.unseenOnly,
        )
        assertEquals(gridOrder, session.photos.map { it.id })
    }
}

/** 往前第 n 个与今天月日相同的日期。2 月 29 日跳过非闰年。 */
private fun previousSameDay(today: java.time.LocalDate, nth: Int): java.time.LocalDate {
    var year = today.year
    var found = 0
    while (found < nth) {
        year -= 1
        if (today.monthValue == 2 && today.dayOfMonth == 29 && !java.time.Year.isLeap(year.toLong())) continue
        found++
    }
    return java.time.LocalDate.of(year, today.monthValue, today.dayOfMonth)
}

package com.winters.cleanphotos.feature.allphotos

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import app.cash.turbine.test
import com.winters.cleanphotos.TestPhotoData
import com.winters.cleanphotos.fake.FakeSeenStateRepository
import com.winters.cleanphotos.domain.usecase.BuildPhotoSectionsUseCase
import com.winters.cleanphotos.domain.usecase.ObserveAllSeenStatsUseCase
import com.winters.cleanphotos.domain.usecase.ObserveSeenIdsUseCase
import com.winters.cleanphotos.domain.usecase.ObserveTrashSummaryUseCase
import com.winters.cleanphotos.domain.usecase.FilterPhotosUseCase
import com.winters.cleanphotos.domain.usecase.ObserveAllPhotosUseCase
import com.winters.cleanphotos.domain.usecase.SortPhotosUseCase
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
    fun `全选这一天_再点取消这一天`() = runTest(testDispatcher) {
        fakePhotoRepository.setPhotos(TestPhotoData.createMultiDateTestPhotos())
        val vm = createViewModel(); advanceUntilIdle()
        val day = vm.uiState.value.sections.first { it.photos.size > 1 }
        val other = vm.uiState.value.sections.first { it.dayKey != day.dayKey }
        vm.onLongPressPhoto(other.photos.first().id)
        vm.onToggleDay(day.dayKey)
        assertEquals(day.photos.map { it.id }.toSet() + other.photos.first().id, vm.selection.value.selectedIds)
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
        assertEquals("已收藏 2 张", undo.title)
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
        assertEquals("已取消收藏 2 张", vm.selection.value.undo!!.title)
    }

    @Test
    fun `移入垃圾桶_含收藏全部移走_撤回整批恢复`() = runTest(testDispatcher) {
        fakePhotoRepository.setPhotos(listOf(TestPhotoData.createTestPhoto(1, isFavorite = true), TestPhotoData.createTestPhoto(2)))
        val vm = createViewModel(); advanceUntilIdle()
        vm.onLongPressPhoto(1L); vm.onToggleSelection(2L)
        vm.onBatchMoveToTrash(); advanceUntilIdle()
        assertEquals("已移走 2 张", vm.selection.value.undo!!.title)
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
        assertEquals("errorMessage 应保留加载失败提示", "加载照片失败", state.errorMessage)
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
}

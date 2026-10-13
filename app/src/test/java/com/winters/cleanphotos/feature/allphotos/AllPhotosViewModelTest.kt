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
            computeDispatcher = testDispatcher,
        )
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

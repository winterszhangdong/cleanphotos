package com.winters.cleanphotos.feature.favorites

import com.winters.cleanphotos.R
import com.winters.cleanphotos.core.text.UiText
import android.net.Uri
import app.cash.turbine.test
import com.winters.cleanphotos.TestPhotoData
import com.winters.cleanphotos.fake.FakeSeenStateRepository
import com.winters.cleanphotos.domain.usecase.BuildPhotoSectionsUseCase
import com.winters.cleanphotos.domain.usecase.ObserveUnseenCountUseCase
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * FavoritesViewModel 单元测试。
 *
 * 重点覆盖刷新失败后的错误提示是否会被 combine 产出的正常状态意外清掉。
 */
class FavoritesViewModelTest {

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()

    private lateinit var fakePhotoRepository: FakePhotoRepository
    private lateinit var mockSeenDataSource: FakeSeenStateRepository

    @Before
    fun setUp() {
        fakePhotoRepository = FakePhotoRepository()

        mockSeenDataSource = FakeSeenStateRepository()

        Dispatchers.setMain(testDispatcher)

        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers {
            mockk<Uri>(relaxed = true)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Uri::class)
    }

    private fun createViewModel(): FavoritesViewModel {
        return FavoritesViewModel(
            photoRepository = fakePhotoRepository,
            sortPhotosUseCase = SortPhotosUseCase(),
            buildPhotoSectionsUseCase = BuildPhotoSectionsUseCase(),
            observeUnseenCountUseCase = ObserveUnseenCountUseCase(fakePhotoRepository, mockSeenDataSource),
            computeDispatcher = testDispatcher,
        )
    }

    @Test
    fun `刷新收藏失败时_保留错误信息而不被combine覆盖`() = runTest(testDispatcher) {
        fakePhotoRepository.shouldThrowOnRefresh = true
        fakePhotoRepository.setFavoritedPhotos(TestPhotoData.createTestPhotos(3))

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("totalCount 应等于收藏照片数量", 3, state.totalCount)
        assertEquals("errorMessage 应保留加载收藏照片失败提示", UiText.Res(R.string.error_load_favorites), state.errorMessage)
    }

    @Test
    fun `初始化成功时_不应有错误信息`() = runTest(testDispatcher) {
        fakePhotoRepository.setFavoritedPhotos(TestPhotoData.createTestPhotos(2))

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("totalCount 应等于收藏照片数量", 2, state.totalCount)
        assertNull("正常加载时不应有错误信息", state.errorMessage)
    }
}

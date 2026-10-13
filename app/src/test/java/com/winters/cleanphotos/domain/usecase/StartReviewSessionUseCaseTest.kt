package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.TestPhotoData
import com.winters.cleanphotos.fake.FakeSeenStateRepository
import com.winters.cleanphotos.domain.model.PhotoFilterMode
import com.winters.cleanphotos.domain.model.PhotoSortOrder
import com.winters.cleanphotos.domain.model.ReviewScope
import com.winters.cleanphotos.fake.FakePhotoRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartReviewSessionUseCaseTest {

    /**
     * 创建测试用的 StartReviewSessionUseCase。
     * FilterPhotosUseCase 需要 AppSeenStateDataSource，这里用 relaxed mock 模拟。
     */
    private fun createUseCase(fakeRepo: FakePhotoRepository): StartReviewSessionUseCase {
        val mockSeenDataSource = FakeSeenStateRepository()
        return StartReviewSessionUseCase(
            fakeRepo,
            SortPhotosUseCase(),
            FilterPhotosUseCase(mockSeenDataSource)
        )
    }

    @Test
    fun `根据 photoId 定位起始索引`() = runTest {
        val fakeRepo = FakePhotoRepository()
        val photos = TestPhotoData.createTestPhotos(5)
        fakeRepo.setPhotos(photos)
        val useCase = createUseCase(fakeRepo)

        val session = useCase(
            startPhotoId = 3L,
            sortOrder = PhotoSortOrder.NewestFirst,
            filterMode = PhotoFilterMode.AllPhotos,
            scope = ReviewScope.AllPhotos
        )
        assertEquals(2, session.currentIndex)
        assertEquals(5, session.totalCount)
    }

    @Test
    fun `无效 photoId 抛出异常`() = runTest {
        val fakeRepo = FakePhotoRepository()
        fakeRepo.setPhotos(TestPhotoData.createTestPhotos(3))
        val useCase = createUseCase(fakeRepo)

        val thrown = kotlin.runCatching {
            useCase(
                startPhotoId = 999L,
                sortOrder = PhotoSortOrder.NewestFirst,
                filterMode = PhotoFilterMode.AllPhotos,
                scope = ReviewScope.AllPhotos
            )
        }
        assertTrue("无效 photoId 应抛出异常", thrown.isFailure)
    }

    @Test
    fun `空照片列表抛出异常`() = runTest {
        val fakeRepo = FakePhotoRepository()
        val useCase = createUseCase(fakeRepo)

        val thrown = kotlin.runCatching {
            useCase(
                startPhotoId = 1L,
                sortOrder = PhotoSortOrder.NewestFirst,
                filterMode = PhotoFilterMode.AllPhotos,
                scope = ReviewScope.AllPhotos
            )
        }
        assertTrue("空照片列表应抛出异常", thrown.isFailure)
    }
}

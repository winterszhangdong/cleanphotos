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

    @Test
    fun `进程重建后内存照片表为空_先加载再定位起点`() = runTest {
        val fakeRepo = FakePhotoRepository()
        // 重建时照片表是空的，只有 ensurePhotosLoaded 之后才有数据
        fakeRepo.onEnsureLoaded = { fakeRepo.setPhotos(TestPhotoData.createTestPhotos(5)) }
        val useCase = createUseCase(fakeRepo)

        val session = useCase(3L, PhotoSortOrder.NewestFirst, PhotoFilterMode.AllPhotos, ReviewScope.AllPhotos)
        assertEquals(1, fakeRepo.ensureLoadedCalls)
        assertEquals(3L, session.photos[session.currentIndex].id)
        assertEquals(5, session.totalCount)
    }

    @Test
    fun `只看未看时起点已被标为看过_落到它后面最近的一张未看`() = runTest {
        val fakeRepo = FakePhotoRepository()
        fakeRepo.setPhotos(TestPhotoData.createTestPhotos(5)) // NewestFirst：5,4,3,2,1
        // 进入会话时起点 3 被标为看过，进程重建后「只看未看」把它过滤掉了
        val seen = FakeSeenStateRepository(setOf(3L, 2L))
        val useCase = StartReviewSessionUseCase(fakeRepo, SortPhotosUseCase(), FilterPhotosUseCase(seen))

        val session = useCase(3L, PhotoSortOrder.NewestFirst, PhotoFilterMode.UnseenOnly, ReviewScope.AllPhotos)
        assertEquals(listOf(5L, 4L, 1L), session.photos.map { it.id })
        assertEquals("落到 3 后面最近的未看照片 1", 1L, session.photos[session.currentIndex].id)
    }

    @Test
    fun `起点后面没有未看照片时取前面最近的一张`() = runTest {
        val fakeRepo = FakePhotoRepository()
        fakeRepo.setPhotos(TestPhotoData.createTestPhotos(5))
        val seen = FakeSeenStateRepository(setOf(1L, 2L, 3L))
        val useCase = StartReviewSessionUseCase(fakeRepo, SortPhotosUseCase(), FilterPhotosUseCase(seen))

        val session = useCase(2L, PhotoSortOrder.NewestFirst, PhotoFilterMode.UnseenOnly, ReviewScope.AllPhotos)
        assertEquals(4L, session.photos[session.currentIndex].id)
    }
}

package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.TestPhotoData
import com.winters.cleanphotos.domain.model.PhotoOperationFailureReason
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.fake.FakeReviewRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritePhotoUseCaseTest {
    @Test
    fun `收藏成功`() = runTest {
        val repo = FakeReviewRepository()
        repo.favoriteResult = PhotoOperationResult.Success
        val useCase = FavoritePhotoUseCase(repo)
        assertTrue(useCase(TestPhotoData.createTestPhoto()) is PhotoOperationResult.Success)
    }

    @Test
    fun `收藏失败`() = runTest {
        val repo = FakeReviewRepository()
        repo.favoriteResult = PhotoOperationResult.Failure(PhotoOperationFailureReason.MediaStoreFailed)
        val useCase = FavoritePhotoUseCase(repo)
        assertTrue(useCase(TestPhotoData.createTestPhoto()) is PhotoOperationResult.Failure)
    }
}

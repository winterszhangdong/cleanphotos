package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.TestPhotoData
import com.winters.cleanphotos.domain.model.PhotoOperationFailureReason
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.fake.FakeReviewRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoveFavoritePhotoUseCaseTest {
    @Test
    fun `取消收藏成功`() = runTest {
        val repo = FakeReviewRepository()
        repo.removeFavoriteResult = PhotoOperationResult.Success
        val useCase = RemoveFavoritePhotoUseCase(repo)
        assertTrue(useCase(TestPhotoData.createTestPhoto()) is PhotoOperationResult.Success)
    }

    @Test
    fun `取消收藏失败`() = runTest {
        val repo = FakeReviewRepository()
        repo.removeFavoriteResult = PhotoOperationResult.Failure(PhotoOperationFailureReason.MediaStoreFailed)
        val useCase = RemoveFavoritePhotoUseCase(repo)
        assertTrue(useCase(TestPhotoData.createTestPhoto()) is PhotoOperationResult.Failure)
    }
}

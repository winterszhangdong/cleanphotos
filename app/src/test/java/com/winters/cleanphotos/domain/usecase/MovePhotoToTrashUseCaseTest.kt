package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.TestPhotoData
import com.winters.cleanphotos.domain.model.PhotoOperationFailureReason
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.fake.FakeReviewRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class MovePhotoToTrashUseCaseTest {
    @Test
    fun `移动到垃圾桶成功`() = runTest {
        val repo = FakeReviewRepository()
        repo.moveTrashResult = PhotoOperationResult.Success
        val useCase = MovePhotoToTrashUseCase(repo)
        assertTrue(useCase(TestPhotoData.createTestPhoto()) is PhotoOperationResult.Success)
    }

    @Test
    fun `移动到垃圾桶失败`() = runTest {
        val repo = FakeReviewRepository()
        repo.moveTrashResult = PhotoOperationResult.Failure(PhotoOperationFailureReason.MediaStoreFailed)
        val useCase = MovePhotoToTrashUseCase(repo)
        assertTrue(useCase(TestPhotoData.createTestPhoto()) is PhotoOperationResult.Failure)
    }
}

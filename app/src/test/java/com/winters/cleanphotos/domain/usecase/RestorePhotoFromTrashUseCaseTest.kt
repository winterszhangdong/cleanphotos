package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.TestPhotoData
import com.winters.cleanphotos.domain.model.PhotoOperationFailureReason
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.fake.FakeReviewRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class RestorePhotoFromTrashUseCaseTest {
    @Test
    fun `从垃圾桶恢复成功`() = runTest {
        val repo = FakeReviewRepository()
        repo.restoreResult = PhotoOperationResult.Success
        val useCase = RestorePhotoFromTrashUseCase(repo)
        assertTrue(useCase(TestPhotoData.createTestPhoto()) is PhotoOperationResult.Success)
    }

    @Test
    fun `从垃圾桶恢复失败`() = runTest {
        val repo = FakeReviewRepository()
        repo.restoreResult = PhotoOperationResult.Failure(PhotoOperationFailureReason.MediaStoreFailed)
        val useCase = RestorePhotoFromTrashUseCase(repo)
        assertTrue(useCase(TestPhotoData.createTestPhoto()) is PhotoOperationResult.Failure)
    }
}

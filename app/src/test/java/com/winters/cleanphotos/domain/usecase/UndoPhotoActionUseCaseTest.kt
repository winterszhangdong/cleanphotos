package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.TestPhotoData
import com.winters.cleanphotos.domain.model.PhotoAction
import com.winters.cleanphotos.domain.model.PhotoActionType
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.fake.FakeReviewRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoPhotoActionUseCaseTest {
    @Test
    fun `撤回收藏操作调用 removeFavorite`() = runTest {
        val repo = FakeReviewRepository()
        repo.removeFavoriteResult = PhotoOperationResult.Success
        val useCase = UndoPhotoActionUseCase(
            FavoritePhotoUseCase(repo),
            RemoveFavoritePhotoUseCase(repo),
            RestorePhotoFromTrashUseCase(repo)
        )
        val action = PhotoAction(TestPhotoData.createTestPhoto(), PhotoActionType.Favorite, 0, System.currentTimeMillis())
        assertTrue(useCase(action) is PhotoOperationResult.Success)
    }

    @Test
    fun `撤回取消收藏操作调用 favorite`() = runTest {
        val repo = FakeReviewRepository()
        repo.favoriteResult = PhotoOperationResult.Success
        val useCase = UndoPhotoActionUseCase(
            FavoritePhotoUseCase(repo),
            RemoveFavoritePhotoUseCase(repo),
            RestorePhotoFromTrashUseCase(repo)
        )
        val action = PhotoAction(TestPhotoData.createTestPhoto(), PhotoActionType.Unfavorite, 0, System.currentTimeMillis())
        assertTrue(useCase(action) is PhotoOperationResult.Success)
    }

    @Test
    fun `撤回垃圾桶操作调用 restoreFromTrash`() = runTest {
        val repo = FakeReviewRepository()
        repo.restoreResult = PhotoOperationResult.Success
        val useCase = UndoPhotoActionUseCase(
            FavoritePhotoUseCase(repo),
            RemoveFavoritePhotoUseCase(repo),
            RestorePhotoFromTrashUseCase(repo)
        )
        val action = PhotoAction(TestPhotoData.createTestPhoto(), PhotoActionType.MoveToTrash, 0, System.currentTimeMillis())
        assertTrue(useCase(action) is PhotoOperationResult.Success)
    }
}

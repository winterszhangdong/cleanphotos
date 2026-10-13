package com.winters.cleanphotos.fake

import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.repository.ReviewRepository

/**
 * Fake ReviewRepository 用于测试。
 *
 * 每个操作可配置返回 Success/NeedSystemApproval/Failure。
 */
class FakeReviewRepository : ReviewRepository {

    var favoriteResult: PhotoOperationResult = PhotoOperationResult.Success
    var removeFavoriteResult: PhotoOperationResult = PhotoOperationResult.Success
    var moveTrashResult: PhotoOperationResult = PhotoOperationResult.Success
    var restoreResult: PhotoOperationResult = PhotoOperationResult.Success
    var batchRestoreResult: PhotoOperationResult = PhotoOperationResult.Success
    var deletePermanentlyResult: PhotoOperationResult = PhotoOperationResult.Success
    /** 删除时的副作用，模拟 MediaStore 变化在返回结果前就刷新了列表（Android 8–10 直接删除） */
    var onDeletePermanently: (() -> Unit)? = null

    override suspend fun favoritePhoto(photo: PhotoItem): PhotoOperationResult = favoriteResult
    override suspend fun removeFavorite(photo: PhotoItem): PhotoOperationResult = removeFavoriteResult
    override suspend fun movePhotoToTrash(photo: PhotoItem): PhotoOperationResult = moveTrashResult
    override suspend fun restorePhotoFromTrash(photo: PhotoItem): PhotoOperationResult = restoreResult
    override suspend fun restorePhotosFromTrash(photos: List<PhotoItem>): PhotoOperationResult = batchRestoreResult
    override suspend fun deletePhotosPermanently(photos: List<PhotoItem>): PhotoOperationResult {
        onDeletePermanently?.invoke()
        return deletePermanentlyResult
    }
}

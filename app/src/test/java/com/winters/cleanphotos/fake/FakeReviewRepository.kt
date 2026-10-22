package com.winters.cleanphotos.fake

import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.model.SwipeSideEffects
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

    /** 每次单张操作带过来的滑动附带写入（验证合并成一次写入时没丢）。 */
    val effectsLog = mutableListOf<SwipeSideEffects>()
    override suspend fun favoritePhoto(photo: PhotoItem, effects: SwipeSideEffects): PhotoOperationResult {
        effectsLog += effects; return favoriteResult
    }
    override suspend fun removeFavorite(photo: PhotoItem, effects: SwipeSideEffects): PhotoOperationResult {
        effectsLog += effects; return removeFavoriteResult
    }
    override suspend fun movePhotoToTrash(photo: PhotoItem, effects: SwipeSideEffects): PhotoOperationResult {
        effectsLog += effects; return moveTrashResult
    }
    override suspend fun restorePhotoFromTrash(photo: PhotoItem): PhotoOperationResult = restoreResult
    /** 批量接口调用记录（每次调用一条，验证一次写入、只撤实际改变的）。 */
    val batchCalls = mutableListOf<Pair<String, List<Long>>>()
    override suspend fun favoritePhotos(photos: List<PhotoItem>): PhotoOperationResult {
        batchCalls += "favorite" to photos.map { it.id }; return PhotoOperationResult.Success
    }
    override suspend fun removeFavorites(photos: List<PhotoItem>): PhotoOperationResult {
        batchCalls += "unfavorite" to photos.map { it.id }; return PhotoOperationResult.Success
    }
    var batchMoveResult: PhotoOperationResult = PhotoOperationResult.Success
    override suspend fun movePhotosToTrash(photos: List<PhotoItem>): PhotoOperationResult {
        batchCalls += "trash" to photos.map { it.id }; return batchMoveResult
    }
    override suspend fun restorePhotosFromTrash(photos: List<PhotoItem>): PhotoOperationResult {
        batchCalls += "restore" to photos.map { it.id }; return batchRestoreResult
    }
    /** forgetDeletedTrashEntries 每次调用的 id 集合。 */
    val forgotten = mutableListOf<Set<Long>>()
    override suspend fun forgetDeletedTrashEntries(photoIds: Set<Long>) {
        forgotten += photoIds
    }
    override suspend fun deletePhotosPermanently(photos: List<PhotoItem>): PhotoOperationResult {
        onDeletePermanently?.invoke()
        return deletePermanentlyResult
    }
}

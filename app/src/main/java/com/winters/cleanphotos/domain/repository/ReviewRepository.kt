package com.winters.cleanphotos.domain.repository

import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationResult

interface ReviewRepository {
    suspend fun favoritePhoto(photo: PhotoItem): PhotoOperationResult
    suspend fun removeFavorite(photo: PhotoItem): PhotoOperationResult
    suspend fun movePhotoToTrash(photo: PhotoItem): PhotoOperationResult
    suspend fun restorePhotoFromTrash(photo: PhotoItem): PhotoOperationResult
    suspend fun restorePhotosFromTrash(photos: List<PhotoItem>): PhotoOperationResult
    suspend fun deletePhotosPermanently(photos: List<PhotoItem>): PhotoOperationResult

    /** 批量收藏：收藏 Store 一次写入。不标记看过（由 UseCase 统一批量标记）。 */
    suspend fun favoritePhotos(photos: List<PhotoItem>): PhotoOperationResult
    /** 批量取消收藏：收藏 Store 一次写入。不标记看过。 */
    suspend fun removeFavorites(photos: List<PhotoItem>): PhotoOperationResult
    /** 批量移入 App 内垃圾桶：逐张快照收藏状态后垃圾桶 Store 一次写入、收藏 Store 一次移除。不标记看过。 */
    suspend fun movePhotosToTrash(photos: List<PhotoItem>): PhotoOperationResult
}

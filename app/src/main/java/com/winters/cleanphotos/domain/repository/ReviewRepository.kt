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
}

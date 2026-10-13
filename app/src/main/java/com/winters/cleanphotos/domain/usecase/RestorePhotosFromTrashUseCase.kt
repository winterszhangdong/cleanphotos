package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.repository.ReviewRepository
import javax.inject.Inject

/**
 * 批量从垃圾桶恢复照片。
 */
class RestorePhotosFromTrashUseCase @Inject constructor(
    private val reviewRepository: ReviewRepository
) {
    suspend operator fun invoke(photos: List<PhotoItem>): PhotoOperationResult {
        return reviewRepository.restorePhotosFromTrash(photos)
    }
}

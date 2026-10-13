package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.repository.ReviewRepository
import javax.inject.Inject

/**
 * 批量永久删除照片（仅用于清空垃圾桶）。
 */
class DeletePhotosPermanentlyUseCase @Inject constructor(
    private val reviewRepository: ReviewRepository
) {
    suspend operator fun invoke(photos: List<PhotoItem>): PhotoOperationResult {
        return reviewRepository.deletePhotosPermanently(photos)
    }
}

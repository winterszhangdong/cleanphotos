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

    /** 删除后确认已消失的照片：清掉残留的垃圾桶 / 收藏 / 看过记录。 */
    suspend fun forgetDeleted(photoIds: Set<Long>) = reviewRepository.forgetDeletedTrashEntries(photoIds)
}

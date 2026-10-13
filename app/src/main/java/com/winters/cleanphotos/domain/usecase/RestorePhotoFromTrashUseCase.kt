package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.repository.ReviewRepository
import javax.inject.Inject

class RestorePhotoFromTrashUseCase @Inject constructor(
    private val reviewRepository: ReviewRepository
) {
    suspend operator fun invoke(photo: PhotoItem): PhotoOperationResult {
        return reviewRepository.restorePhotoFromTrash(photo)
    }
}

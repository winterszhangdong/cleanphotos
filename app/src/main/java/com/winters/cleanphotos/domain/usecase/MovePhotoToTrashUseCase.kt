package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.repository.ReviewRepository
import javax.inject.Inject

class MovePhotoToTrashUseCase @Inject constructor(
    private val reviewRepository: ReviewRepository
) {
    suspend operator fun invoke(photo: PhotoItem): PhotoOperationResult {
        return reviewRepository.movePhotoToTrash(photo)
    }
}

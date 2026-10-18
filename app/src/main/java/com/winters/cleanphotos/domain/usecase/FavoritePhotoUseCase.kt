package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.model.SwipeSideEffects
import com.winters.cleanphotos.domain.repository.ReviewRepository
import javax.inject.Inject

class FavoritePhotoUseCase @Inject constructor(
    private val reviewRepository: ReviewRepository
) {
    suspend operator fun invoke(
        photo: PhotoItem,
        effects: SwipeSideEffects = SwipeSideEffects.None,
    ): PhotoOperationResult {
        return reviewRepository.favoritePhoto(photo, effects)
    }
}

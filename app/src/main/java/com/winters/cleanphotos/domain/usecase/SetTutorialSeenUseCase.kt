package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.repository.UserPreferencesRepository
import javax.inject.Inject

class SetTutorialSeenUseCase @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository
) {
    suspend operator fun invoke() {
        userPreferencesRepository.setHasSeenReviewTutorial(true)
    }
}

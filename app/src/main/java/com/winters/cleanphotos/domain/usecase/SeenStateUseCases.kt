package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.repository.SeenStateRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** 观察「看过」的照片 id。 */
class ObserveSeenIdsUseCase @Inject constructor(
    private val seenStateRepository: SeenStateRepository,
) {
    operator fun invoke(): Flow<Set<Long>> = seenStateRepository.observeSeenIds()
}

/** 把一张照片记为看过（清理页停留 / 离开时）。 */
class MarkPhotoSeenUseCase @Inject constructor(
    private val seenStateRepository: SeenStateRepository,
) {
    suspend operator fun invoke(photoId: Long) = seenStateRepository.markSeen(photoId)
}

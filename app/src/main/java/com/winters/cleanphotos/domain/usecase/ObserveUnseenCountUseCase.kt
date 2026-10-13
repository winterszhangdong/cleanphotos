package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.repository.PhotoRepository
import com.winters.cleanphotos.domain.repository.SeenStateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/** 相册里还没看过的张数（空状态按钮「去清理 · 312 张未看」）。 */
class ObserveUnseenCountUseCase @Inject constructor(
    private val photoRepository: PhotoRepository,
    private val seenStateRepository: SeenStateRepository,
) {
    operator fun invoke(): Flow<Int> =
        combine(photoRepository.observeAllPhotos(), seenStateRepository.observeSeenIds()) { photos, seen ->
            photos.count { it.id !in seen }
        }
}

package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.repository.PhotoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** 垃圾桶里每张照片的移入时间（photoId → 毫秒），供单张预览「N天前移入」。 */
class ObserveTrashMovedAtUseCase @Inject constructor(
    private val photoRepository: PhotoRepository,
) {
    operator fun invoke(): Flow<Map<Long, Long>> =
        photoRepository.observeTrashEntries().map { entries -> entries.associate { it.photoId to it.movedToTrashAt } }
}

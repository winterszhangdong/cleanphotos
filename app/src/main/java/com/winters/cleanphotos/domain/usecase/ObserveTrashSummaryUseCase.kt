package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.TrashSummary
import com.winters.cleanphotos.domain.repository.PhotoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** 垃圾桶张数与总大小（「清空后可释放 X GB」）。size 未知的照片按 0 计。 */
class ObserveTrashSummaryUseCase @Inject constructor(
    private val photoRepository: PhotoRepository,
) {
    operator fun invoke(): Flow<TrashSummary> = photoRepository.observeTrashedPhotos().map { photos ->
        TrashSummary(count = photos.size, totalBytes = photos.sumOf { it.size ?: 0L })
    }
}

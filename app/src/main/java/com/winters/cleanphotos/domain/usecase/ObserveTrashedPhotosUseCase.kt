package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.repository.PhotoRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * 观察垃圾桶照片列表。
 *
 * 委托 PhotoRepository.observeTrashedPhotos()，返回实时更新的垃圾桶照片 Flow。
 */
class ObserveTrashedPhotosUseCase @Inject constructor(
    private val photoRepository: PhotoRepository
) {
    operator fun invoke(): Flow<List<PhotoItem>> {
        return photoRepository.observeTrashedPhotos()
    }
}

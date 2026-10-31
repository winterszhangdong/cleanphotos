package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.repository.PhotoRepository
import javax.inject.Inject

/** 本进程的照片表还没加载时先加载。相似对比在冷启动时不能对着空表扫描。 */
class EnsurePhotosLoadedUseCase @Inject constructor(
    private val photoRepository: PhotoRepository,
) {
    suspend operator fun invoke() = photoRepository.ensurePhotosLoaded()
}

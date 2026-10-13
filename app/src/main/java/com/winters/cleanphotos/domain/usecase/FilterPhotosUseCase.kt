package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.PhotoFilterMode
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.repository.SeenStateRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * 按过滤模式筛照片。「看过」状态只经由 [SeenStateRepository] 读取。
 * 已经在流里拿到 seenIds 的调用方用纯函数版本，保证同一帧数据一致。
 */
class FilterPhotosUseCase @Inject constructor(
    private val seenStateRepository: SeenStateRepository,
) {
    suspend operator fun invoke(photos: List<PhotoItem>, filterMode: PhotoFilterMode): List<PhotoItem> =
        invoke(photos, filterMode, seenStateRepository.observeSeenIds().first())

    operator fun invoke(photos: List<PhotoItem>, filterMode: PhotoFilterMode, seenIds: Set<Long>): List<PhotoItem> =
        when (filterMode) {
            PhotoFilterMode.AllPhotos -> photos
            PhotoFilterMode.UnseenOnly -> photos.filter { it.id !in seenIds }
        }
}

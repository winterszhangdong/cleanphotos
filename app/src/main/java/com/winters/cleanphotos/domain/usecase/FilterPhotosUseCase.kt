package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.PhotoFilterMode
import com.winters.cleanphotos.domain.model.PhotoFilterRules
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.repository.SeenStateRepository
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject

/**
 * 先按范围筛，再按需叠「只看没看过」。
 * 「看过」状态只经由 [SeenStateRepository] 读取。
 * 已经在流里拿到 seenIds 的调用方用纯函数版本，保证同一帧数据一致。
 */
class FilterPhotosUseCase @Inject constructor(
    private val seenStateRepository: SeenStateRepository,
) {
    suspend operator fun invoke(
        photos: List<PhotoItem>,
        filterMode: PhotoFilterMode,
        unseenOnly: Boolean = false,
    ): List<PhotoItem> {
        val today = LocalDate.now()
        return invoke(photos, filterMode, seenStateRepository.observeSeenIds().first(), unseenOnly, today)
    }

    operator fun invoke(
        photos: List<PhotoItem>,
        filterMode: PhotoFilterMode,
        seenIds: Set<Long>,
        unseenOnly: Boolean = false,
        today: LocalDate,
    ): List<PhotoItem> {
        val ranged = photos.filter { PhotoFilterRules.matches(it, filterMode, today) }
        return if (unseenOnly) ranged.filter { it.id !in seenIds } else ranged
    }
}

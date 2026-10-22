package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.OnThisDayRules
import com.winters.cleanphotos.domain.model.PhotoFilterMode
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.SmartFilterRules
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
    ): List<PhotoItem> = invoke(photos, filterMode, seenStateRepository.observeSeenIds().first(), unseenOnly)

    operator fun invoke(
        photos: List<PhotoItem>,
        filterMode: PhotoFilterMode,
        seenIds: Set<Long>,
        unseenOnly: Boolean = false,
    ): List<PhotoItem> {
        val ranged = when (filterMode) {
            PhotoFilterMode.AllPhotos -> photos
            PhotoFilterMode.Screenshots,
            PhotoFilterMode.LargeFiles,
            PhotoFilterMode.ChatApps,
            -> photos.filter { SmartFilterRules.matches(it, filterMode) }
            PhotoFilterMode.OnThisDay -> {
                // shortcut: 今天只在照片列表 / 看过状态变化时重算，跨过午夜不会自动刷新；要精确就加一个按日期变化的 Flow
                val today = LocalDate.now()
                photos.filter { OnThisDayRules.isOnThisDay(it, today) }
            }
        }
        return if (unseenOnly) ranged.filter { it.id !in seenIds } else ranged
    }
}

package com.winters.cleanphotos.feature.allphotos

import com.winters.cleanphotos.domain.model.AllSeenStats
import com.winters.cleanphotos.domain.model.PhotoDaySection
import com.winters.cleanphotos.domain.model.PhotoFilterMode
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoSortOrder

data class AllPhotosUiState(
    /** 照片页张数（不含收藏、垃圾桶）。 */
    val totalCount: Int = 0,
    /** 其中还没看过的张数（「· 312 未看」）。 */
    val unseenCount: Int = 0,
    val visibleCount: Int = 0,
    val sortOrder: PhotoSortOrder = PhotoSortOrder.NewestFirst,
    val filterMode: PhotoFilterMode = PhotoFilterMode.AllPhotos,
    val sections: List<PhotoDaySection> = emptyList(),
    val seenIds: Set<Long> = emptySet(),
    /** 「全部看过了」三个数字（决定 11）。 */
    val allSeenStats: AllSeenStats = AllSeenStats(favoriteCount = 0, trashCount = 0, trashBytes = 0L),
    /** 晾绳上的三张照片。 */
    val hangPhotos: List<PhotoItem> = emptyList(),
    val isLoading: Boolean = false,
    /** 冷启动第一次读相册：用加载显影参数，之后用网格首屏参数。 */
    val isInitialLoad: Boolean = true,
    val hasPermission: Boolean = false,
    val errorMessage: String? = null,
)

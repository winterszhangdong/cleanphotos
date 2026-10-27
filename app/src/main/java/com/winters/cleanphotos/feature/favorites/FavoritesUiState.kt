package com.winters.cleanphotos.feature.favorites

import com.winters.cleanphotos.core.text.UiText
import com.winters.cleanphotos.domain.model.FiledDay
import com.winters.cleanphotos.domain.model.PhotoDaySection
import com.winters.cleanphotos.domain.model.PhotoSortOrder

data class FavoritesUiState(
    val totalCount: Int = 0,
    val sortOrder: PhotoSortOrder = PhotoSortOrder.NewestFirst,
    val sections: List<PhotoDaySection> = emptyList(),
    /** 还有几天待整理（顶栏「3 天待整理」）。 */
    val pendingDays: Int = 0,
    /** 「已冲洗」：装过袋的天，最近装过的在前。 */
    val filedDays: List<FiledDay> = emptyList(),
    /** 空状态「去清理 · 312 张未看」。 */
    val unseenCount: Int = 0,
    val isLoading: Boolean = false,
    val errorMessage: UiText? = null,
)

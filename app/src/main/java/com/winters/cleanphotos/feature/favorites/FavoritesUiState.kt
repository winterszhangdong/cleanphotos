package com.winters.cleanphotos.feature.favorites

import com.winters.cleanphotos.core.text.UiText
import com.winters.cleanphotos.domain.model.PhotoDaySection
import com.winters.cleanphotos.domain.model.PhotoSortOrder

data class FavoritesUiState(
    val totalCount: Int = 0,
    val sortOrder: PhotoSortOrder = PhotoSortOrder.NewestFirst,
    val sections: List<PhotoDaySection> = emptyList(),
    /** 空状态「去清理 · 312 张未看」。 */
    val unseenCount: Int = 0,
    val isLoading: Boolean = false,
    val errorMessage: UiText? = null,
)

package com.winters.cleanphotos.feature.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.dockListBottomPadding
import com.winters.cleanphotos.domain.model.PhotoItem

/**
 * 垃圾桶照片网格：三列、间隔 2dp、左右贴边，不按日期分组。[header] 占满一行，跟着网格一起滚动。
 */
@Composable
fun TrashPhotoGrid(
    photos: List<PhotoItem>,
    selectedPhotoIds: Set<Long>,
    isSelectionMode: Boolean,
    onPhotoClick: (PhotoItem) -> Unit,
    onToggleSelection: (PhotoItem) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(bottom = dockListBottomPadding()),
        horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap),
        verticalArrangement = Arrangement.spacedBy(Dimens.GridGap),
        modifier = modifier.fillMaxSize(),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") { header() }
        items(photos, key = { it.id }, contentType = { "photo" }) { photo ->
            TrashPhotoItem(
                photo = photo,
                isSelected = photo.id in selectedPhotoIds,
                isSelectionMode = isSelectionMode,
                onPhotoClick = onPhotoClick,
                onToggleSelection = onToggleSelection,
            )
        }
    }
}

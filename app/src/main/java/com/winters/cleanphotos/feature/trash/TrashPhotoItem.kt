package com.winters.cleanphotos.feature.trash

import com.winters.cleanphotos.core.locale.currentLocale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.winters.cleanphotos.core.design.ApiTier
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.PhotoTile
import com.winters.cleanphotos.core.design.SelectionCheck
import com.winters.cleanphotos.core.design.animateSelectPick
import com.winters.cleanphotos.core.design.selectedShrink
import com.winters.cleanphotos.core.design.saturateBrightness
import com.winters.cleanphotos.core.util.FileSizeFormatter
import com.winters.cleanphotos.domain.model.MediaType
import com.winters.cleanphotos.domain.model.PhotoItem

/** 垃圾桶缩略图降饱和压暗：saturate(0.7) brightness(0.86)。 */
private const val TRASH_SATURATION = 0.7f
private const val TRASH_BRIGHTNESS = 0.86f

/**
 * 垃圾桶三列格子。右下角等宽大小；多选选中时照片缩到 0.86、圆角 6、恢复原色，右上相纸白勾（决定 8，160ms）。
 */
@Composable
fun TrashPhotoItem(
    photo: PhotoItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onPhotoClick: (PhotoItem) -> Unit,
    onToggleSelection: (PhotoItem) -> Unit,
    modifier: Modifier = Modifier,
    /** 清空溶解进度（0..1，已过曲线）；null 表示不在溶解。 */
    dissolveProgress: (() -> Float)? = null,
    tier: ApiTier = ApiTier.Current,
) {
    val pickState = animateSelectPick(isSelected, label = "trash-pick")
    val pick = pickState.value
    val saturation = TRASH_SATURATION + (1f - TRASH_SATURATION) * pick
    val brightness = TRASH_BRIGHTNESS + (1f - TRASH_BRIGHTNESS) * pick
    PhotoTile(
        model = photo.uri,
        contentDescription = photo.displayName,
        isVideo = photo.mediaType == MediaType.Video,
        durationMs = photo.durationMs,
        modifier = modifier
            .then(if (dissolveProgress != null) Modifier.dissolveLayer(dissolveProgress, tier) else Modifier)
            .then(if (isSelectionMode) Modifier.background(DarkroomColors.Room) else Modifier),
        colorFilter = saturateBrightness(saturation, brightness),
        imageModifier = Modifier.selectedShrink { pickState.value },
        onClick = if (dissolveProgress != null) null else ({ if (isSelectionMode) onToggleSelection(photo) else onPhotoClick(photo) }),
    ) {
        val size = photo.size
        // 视频右下角是时长；再叠文件大小会盖住它
        if (size != null && !isSelected && photo.durationMs == null) {
            Text(
                FileSizeFormatter.format(size, currentLocale()),
                style = DarkroomText.TileSize,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = Dimens.TileLabelInset, bottom = Dimens.TileLabelInset),
            )
        }
        SelectionCheck(isSelected)
    }
}

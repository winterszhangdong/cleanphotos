package com.winters.cleanphotos.feature.trash

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.winters.cleanphotos.core.design.ApiTier
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomIcons
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.MotionDurations
import com.winters.cleanphotos.core.design.MotionEasings
import com.winters.cleanphotos.core.design.PhotoTile
import com.winters.cleanphotos.core.design.saturateBrightness
import com.winters.cleanphotos.core.util.FileSizeFormatter
import com.winters.cleanphotos.domain.model.PhotoItem

/** 垃圾桶缩略图降饱和压暗：saturate(0.7) brightness(0.86)。 */
private const val TRASH_SATURATION = 0.7f
private const val TRASH_BRIGHTNESS = 0.86f
private const val SELECTED_SCALE = 0.86f

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
    val spec = tween<Float>(MotionDurations.SELECT_POP, easing = MotionEasings.Standard)
    val pick by animateFloatAsState(if (isSelected) 1f else 0f, spec, label = "trash-pick")
    val saturation = TRASH_SATURATION + (1f - TRASH_SATURATION) * pick
    val brightness = TRASH_BRIGHTNESS + (1f - TRASH_BRIGHTNESS) * pick
    val scale = 1f - (1f - SELECTED_SCALE) * pick
    val corner = Dimens.SelectedCorner * pick
    PhotoTile(
        model = photo.uri,
        contentDescription = photo.displayName,
        modifier = modifier
            .then(if (dissolveProgress != null) Modifier.dissolveLayer(dissolveProgress, tier) else Modifier)
            .then(if (isSelectionMode) Modifier.background(DarkroomColors.Room) else Modifier),
        colorFilter = saturateBrightness(saturation, brightness),
        imageModifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(if (pick > 0f) androidx.compose.foundation.shape.RoundedCornerShape(corner) else RectangleShape),
        onClick = if (dissolveProgress != null) null else ({ if (isSelectionMode) onToggleSelection(photo) else onPhotoClick(photo) }),
    ) {
        val size = photo.size
        if (size != null && !isSelected) {
            Text(
                FileSizeFormatter.format(size),
                style = DarkroomText.TileSize,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = Dimens.TileLabelInset, bottom = Dimens.TileLabelInset),
            )
        }
        AnimatedVisibility(
            visible = isSelected,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = -Dimens.CheckInset, y = Dimens.CheckInset),
            enter = fadeIn(tween(MotionDurations.SELECT_POP, easing = MotionEasings.Standard)) +
                scaleIn(tween(MotionDurations.SELECT_POP, easing = MotionEasings.Standard), initialScale = 0.6f),
            exit = fadeOut(tween(MotionDurations.SELECT_POP, easing = MotionEasings.Standard)) +
                scaleOut(tween(MotionDurations.SELECT_POP, easing = MotionEasings.Standard), targetScale = 0.6f),
        ) {
            // 22dp 相纸白圆 + 2dp 暗室色外环 + 0 2 8 投影
            Box(
                Modifier
                    .size(Dimens.CheckBadge + Dimens.CheckRing * 2)
                    .offset(x = Dimens.CheckRing, y = -Dimens.CheckRing)
                    .dropShadow(CircleShape, Shadow(radius = 8.dp, color = DarkroomColors.CheckShadow, offset = DpOffset(0.dp, 2.dp)))
                    .clip(CircleShape)
                    .background(DarkroomColors.Room)
                    .padding(Dimens.CheckRing)
                    .clip(CircleShape)
                    .background(DarkroomColors.Paper),
                contentAlignment = Alignment.Center,
            ) {
                Icon(DarkroomIcons.CheckBold, contentDescription = "已选", tint = DarkroomColors.Room, modifier = Modifier.size(Dimens.CheckIcon))
            }
        }
    }
}

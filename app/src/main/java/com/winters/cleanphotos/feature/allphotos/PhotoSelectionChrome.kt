package com.winters.cleanphotos.feature.allphotos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomIcons
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.GlassSpecs
import com.winters.cleanphotos.core.design.MotionDurations
import com.winters.cleanphotos.core.design.MotionEasings
import com.winters.cleanphotos.core.design.UndoBar
import com.winters.cleanphotos.core.design.UndoCapsuleContent
import com.winters.cleanphotos.core.design.glassBlur
import com.winters.cleanphotos.core.design.plainClickable
import com.winters.cleanphotos.domain.model.BatchActionType

/** 照片页多选的事件回调（Route 接到 ViewModel）。 */
data class PhotoSelectionActions(
    val onLongPress: (Long) -> Unit,
    val onDrag: (anchorId: Long, currentId: Long) -> Unit,
    val onToggle: (Long) -> Unit,
    val onToggleDay: (String) -> Unit,
    val onExit: () -> Unit,
    val onFavorite: () -> Unit,
    val onMoveToTrash: () -> Unit,
    val onUndo: () -> Unit,
    val onUndoExpired: (Long) -> Unit,
) {
    companion object {
        val None = PhotoSelectionActions({}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {})
    }
}

/**
 * 多选底栏：和底部导航同一枚 44dp 玻璃胶囊、同一位置（距底 24dp），两项各 112×36dp。
 * 「收藏」实心琥珀星 + 相纸白字；全部已收藏时变成空心琥珀星 + 琥珀字「取消收藏」；「移入垃圾桶」中性相纸白，不用红色。
 * 与导航交替出现：150ms 淡入淡出。
 */
@Composable
fun SelectionActionBar(
    visible: Boolean,
    allFavorite: Boolean,
    enabled: Boolean,
    onFavorite: () -> Unit,
    onMoveToTrash: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.navigationBarsPadding().padding(bottom = Dimens.NavBottom),
        enter = fadeIn(tween(MotionDurations.SHORT, easing = MotionEasings.Standard)),
        exit = fadeOut(tween(MotionDurations.SHORT, easing = MotionEasings.Standard)),
    ) {
        val shape = RoundedCornerShape(Dimens.NavCorner)
        Row(
            Modifier
                .dropShadow(shape, Shadow(radius = Dimens.NavShadowBlur, color = DarkroomColors.NavShadow, offset = DpOffset(0.dp, Dimens.NavShadowY)))
                .height(Dimens.NavHeight)
                .clip(shape)
                .glassBlur(GlassSpecs.Nav)
                .border(Dimens.Hairline, DarkroomColors.NavRim, shape)
                .padding(Dimens.NavPadding)
                .alpha(if (enabled) 1f else 0.5f),
            horizontalArrangement = Arrangement.spacedBy(Dimens.NavPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (allFavorite) {
                ActionItem(DarkroomIcons.Star, DarkroomColors.Amber, "取消收藏", DarkroomColors.Amber, enabled, onFavorite)
            } else {
                ActionItem(DarkroomIcons.StarSolid, DarkroomColors.Amber, "收藏", DarkroomColors.Paper, enabled, onFavorite)
            }
            ActionItem(DarkroomIcons.Trash, DarkroomColors.Paper, "移入垃圾桶", DarkroomColors.Paper, enabled, onMoveToTrash)
        }
    }
}

@Composable
private fun ActionItem(icon: ImageVector, iconTint: Color, label: String, labelColor: Color, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .size(Dimens.SelectActionWidth, Dimens.NavItemHeight)
            .clip(RoundedCornerShape(Dimens.NavItemHeight / 2))
            .plainClickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(Dimens.NavItemGap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(Dimens.ToolIcon))
        Text(label, style = DarkroomText.NavLabel.copy(color = labelColor, fontWeight = FontWeight.Medium))
    }
}

/** 批量操作后的撤回胶囊：只有一行「已收藏 N 张」等短文案；小样取实际改变的第一张。位置同清理页（导航上方 16dp）。 */
@Composable
fun BatchUndoBar(
    undo: BatchUndo?,
    visible: Boolean,
    onUndo: () -> Unit,
    onExpired: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    UndoBar(
        content = undo?.let {
            val first = it.action.photos.first()
            UndoCapsuleContent(it.seq, first.id, first.uri, it.action.type != BatchActionType.MoveToTrash, it.title, subtitle = null)
        },
        visible = visible,
        onUndo = onUndo,
        onExpired = onExpired,
        modifier = modifier
            .navigationBarsPadding()
            .padding(start = Dimens.ScreenSide, end = Dimens.ScreenSide, bottom = Dimens.NavBottom + Dimens.NavHeight + Dimens.UndoAboveNav)
            .fillMaxWidth(),
    )
}


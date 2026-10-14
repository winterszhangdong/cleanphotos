package com.winters.cleanphotos.core.design

import com.winters.cleanphotos.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * 多选选中（决定 8）：照片缩到 0.86、圆角 6dp，160ms；右上相纸白勾。垃圾桶与照片页共用。
 */
private const val SELECTED_SCALE = 0.86f

/** 选中进度 0..1（160ms 标准曲线）。 */
@Composable
fun animateSelectPick(selected: Boolean, label: String = "select-pick"): State<Float> =
    animateFloatAsState(
        if (selected) 1f else 0f,
        tween(MotionDurations.SELECT_POP, easing = MotionEasings.Standard),
        label = label,
    )

/** 作用在格子里的照片层：按 [pick] 缩小并加圆角（只在图层阶段读取）。 */
fun Modifier.selectedShrink(pick: () -> Float): Modifier = graphicsLayer {
    val p = pick()
    val scale = 1f - (1f - SELECTED_SCALE) * p
    scaleX = scale
    scaleY = scale
    shape = if (p > 0f) RoundedCornerShape(Dimens.SelectedCorner * p) else RectangleShape
    clip = p > 0f
}

/** 右上角选中勾：22dp 相纸白圆 + 2dp 暗室色外环 + 0 2 8 投影，淡入 + 0.6→1 缩放 160ms。 */
@Composable
fun BoxScope.SelectionCheck(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.TopEnd).offset(x = -Dimens.CheckInset, y = Dimens.CheckInset),
        enter = fadeIn(tween(MotionDurations.SELECT_POP, easing = MotionEasings.Standard)) +
            scaleIn(tween(MotionDurations.SELECT_POP, easing = MotionEasings.Standard), initialScale = 0.6f),
        exit = fadeOut(tween(MotionDurations.SELECT_POP, easing = MotionEasings.Standard)) +
            scaleOut(tween(MotionDurations.SELECT_POP, easing = MotionEasings.Standard), targetScale = 0.6f),
    ) {
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
            Icon(DarkroomIcons.CheckBold, contentDescription = stringResource(R.string.cd_selected), tint = DarkroomColors.Room, modifier = Modifier.size(Dimens.CheckIcon))
        }
    }
}

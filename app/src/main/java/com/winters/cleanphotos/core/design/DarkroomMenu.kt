package com.winters.cleanphotos.core.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

data class MenuOption(val label: String, val trailing: String?, val selected: Boolean, val onClick: () -> Unit)

/** 菜单玻璃：rgba(32,29,26,0.88) + blur(24)。 */
private val MenuGlassSpec = GlassSpec(DarkroomColors.MenuGlass, 24.dp)

/** 菜单背后的 48% 压暗，点空白关闭。 */
@Composable
fun MenuScrim(visible: Boolean, onDismiss: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(MotionDurations.MENU, easing = MotionEasings.Standard)),
        exit = fadeOut(tween(MotionDurations.SHORT, easing = MotionEasings.Standard)),
    ) {
        Box(Modifier.fillMaxSize().background(DarkroomColors.MenuScrim).plainClickable(onClick = onDismiss))
    }
}

/**
 * 排序 / 筛选菜单：192dp 宽，距状态栏下 80dp、右 16dp，从右上角缩放 0.92 → 1 淡入。
 */
@Composable
fun DarkroomMenu(visible: Boolean, header: String, options: List<MenuOption>, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.padding(top = Dimens.MenuTopBelowStatus, end = Dimens.ScreenSide),
            enter = fadeIn(tween(MotionDurations.MENU, easing = MotionEasings.Standard)) +
                scaleIn(tween(MotionDurations.MENU, easing = MotionEasings.Standard), initialScale = 0.92f, transformOrigin = TransformOrigin(1f, 0f)),
            exit = fadeOut(tween(MotionDurations.SHORT, easing = MotionEasings.Standard)) +
                scaleOut(tween(MotionDurations.SHORT, easing = MotionEasings.Standard), targetScale = 0.92f, transformOrigin = TransformOrigin(1f, 0f)),
        ) {
            val shape = RoundedCornerShape(Dimens.MenuCorner)
            Column(
                Modifier
                    .width(Dimens.MenuWidth)
                    .dropShadow(shape, Shadow(radius = 48.dp, color = DarkroomColors.MenuShadow, offset = DpOffset(0.dp, 24.dp)))
                    .clip(shape)
                    .glassBlur(MenuGlassSpec)
                    .border(Dimens.Hairline, DarkroomColors.MenuRim, shape)
                    .plainClickable(onClick = {})
                    .padding(Dimens.MenuPadding),
            ) {
                Text(
                    header,
                    style = DarkroomText.MenuHeader,
                    modifier = Modifier.padding(start = Dimens.MenuHeaderPadding, end = Dimens.MenuHeaderPadding, top = 8.dp, bottom = 4.dp),
                )
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(Dimens.MenuItemHeight)
                            .clip(RoundedCornerShape(Dimens.MenuItemCorner))
                            .background(if (option.selected) DarkroomColors.MenuItemOn else androidx.compose.ui.graphics.Color.Transparent)
                            .plainClickable(onClick = option.onClick)
                            .padding(horizontal = Dimens.MenuHeaderPadding),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            option.label,
                            style = if (option.selected) {
                                DarkroomText.MenuItem.copy(color = DarkroomColors.Paper, fontWeight = FontWeight.Medium)
                            } else {
                                DarkroomText.MenuItem
                            },
                        )
                        Spacer(Modifier.weight(1f))
                        if (option.trailing != null) {
                            Text(
                                option.trailing,
                                style = if (option.selected) DarkroomText.MenuItemRight.copy(color = DarkroomColors.Dim) else DarkroomText.MenuItemRight,
                            )
                        }
                    }
                }
            }
        }
    }
}

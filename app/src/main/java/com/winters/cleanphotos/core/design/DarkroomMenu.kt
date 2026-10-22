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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

data class MenuOption(
    val label: String,
    val trailing: String?,
    val selected: Boolean,
    /** 非 null 时右侧画开关，不再画 [trailing]。放在 [onClick] 前面，调用处的尾随 lambda 仍落到 onClick。 */
    val toggle: Boolean? = null,
    val onClick: () -> Unit,
)

/** 菜单里的一段。段与段之间画分隔线。[header] 为空则这一段没有小标题。 */
data class MenuSection(val header: String? = null, val options: List<MenuOption>)

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
    DarkroomMenu(visible, listOf(MenuSection(header, options)), modifier)
}

/**
 * 排序 / 筛选菜单：192dp 宽，距状态栏下 [top]（默认 80dp）、右 16dp，从右上角缩放 0.92 → 1 淡入。
 * 多段时段与段之间一条细线。
 */
@Composable
fun DarkroomMenu(visible: Boolean, sections: List<MenuSection>, modifier: Modifier = Modifier, top: Dp = Dimens.MenuTopBelowStatus) {
    Box(modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.padding(top = top, end = Dimens.ScreenSide),
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
                sections.forEachIndexed { index, section ->
                    if (index > 0) {
                        Spacer(Modifier.height(Dimens.MenuPadding))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Dimens.MenuHeaderPadding)
                                .height(Dimens.Hairline)
                                .background(DarkroomColors.Rim),
                        )
                        Spacer(Modifier.height(Dimens.MenuPadding))
                    }
                    if (section.header != null) {
                        Text(
                            section.header,
                            style = DarkroomText.MenuHeader,
                            modifier = Modifier.padding(start = Dimens.MenuHeaderPadding, end = Dimens.MenuHeaderPadding, top = 8.dp, bottom = 4.dp),
                        )
                    }
                    section.options.forEach { option ->
                        val highlighted = option.selected && option.toggle == null
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(Dimens.MenuItemHeight)
                                .clip(RoundedCornerShape(Dimens.MenuItemCorner))
                                .background(if (highlighted) DarkroomColors.MenuItemOn else androidx.compose.ui.graphics.Color.Transparent)
                                .plainClickable(onClick = option.onClick)
                                .padding(horizontal = Dimens.MenuHeaderPadding),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                option.label,
                                style = if (highlighted) {
                                    DarkroomText.MenuItem.copy(color = DarkroomColors.Paper, fontWeight = FontWeight.Medium)
                                } else {
                                    DarkroomText.MenuItem
                                },
                            )
                            Spacer(Modifier.weight(1f))
                            val toggle = option.toggle
                            if (toggle != null) {
                                MenuSwitch(toggle)
                            } else if (option.trailing != null) {
                                Text(
                                    option.trailing,
                                    style = if (highlighted) DarkroomText.MenuItemRight.copy(color = DarkroomColors.Dim) else DarkroomText.MenuItemRight,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 菜单开关。开：相纸白轨道 + 暗室色圆钮；关：托盘轨道 + dim 圆钮。不用琥珀、不用红。 */
@Composable
private fun MenuSwitch(on: Boolean) {
    val shape = RoundedCornerShape(Dimens.MenuSwitchTrackHeight / 2)
    Box(
        Modifier
            .size(Dimens.MenuSwitchTrackWidth, Dimens.MenuSwitchTrackHeight)
            .clip(shape)
            .background(if (on) DarkroomColors.Paper else DarkroomColors.Tray)
            .border(Dimens.Hairline, DarkroomColors.Rim, shape)
            .padding(Dimens.MenuSwitchInset),
    ) {
        Box(
            Modifier
                .align(if (on) Alignment.CenterEnd else Alignment.CenterStart)
                .size(Dimens.MenuSwitchThumb)
                .clip(CircleShape)
                .background(if (on) DarkroomColors.Room else DarkroomColors.Dim),
        )
    }
}

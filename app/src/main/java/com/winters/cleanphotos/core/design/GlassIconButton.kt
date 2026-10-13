package com.winters.cleanphotos.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** 40dp 玻璃圆钮 —— #141210 @ 56% + 16dp 背景模糊（API 31+，26–30 压暗代替）+ 14% 细线。 */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = Dimens.GlassButton,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .glassBlur(GlassSpecs.Button)
            .then(if (pressed) Modifier.background(DarkroomColors.Tray2) else Modifier)
            .border(Dimens.Hairline, DarkroomColors.GlassRim, CircleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * 顶栏 36dp 圆形工具按钮（.ib）：托盘色 + 9% 细线；active 时反白；pip 为 6dp 相纸白小点（筛选生效）。
 */
@Composable
fun ToolIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    showPip: Boolean = false,
    enabled: Boolean = true,
    content: @Composable (tint: Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(Dimens.ToolButton)
            .clip(CircleShape)
            .background(if (active) DarkroomColors.Paper else DarkroomColors.Tray)
            .border(Dimens.Hairline, DarkroomColors.Rim, CircleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content(if (active) DarkroomColors.Room else DarkroomColors.Paper)
        if (showPip) {
            // .pip：top/right 7px、6px 相纸白、外扩 2px 托盘色描边（box-shadow spread）
            val ring = if (active) DarkroomColors.Paper else DarkroomColors.Tray
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = -(Dimens.ToolPipInset - Dimens.NavIndicatorHeight), y = Dimens.ToolPipInset - Dimens.NavIndicatorHeight)
                    .size(Dimens.ToolPip + Dimens.NavIndicatorHeight * 2)
                    .clip(CircleShape)
                    .background(ring)
                    .padding(Dimens.NavIndicatorHeight)
                    .clip(CircleShape)
                    .background(if (active) DarkroomColors.Room else DarkroomColors.Paper),
            )
        }
    }
}

package com.winters.cleanphotos.core.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.draw.drawBehind

/**
 * 未看标记（.tile.new::after）：6dp #FFFBF5 光点，外圈 1.5dp rgba(15,14,13,0.5) 暗环，8dp 柔光（spread 1px）。
 * 调用处负责定位在缩略图左上 6dp。
 */
@Composable
fun UnseenDot(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(Dimens.UnseenDot)
            .dropShadow(CircleShape, Shadow(radius = Dimens.UnseenGlow, spread = Dimens.Hairline, color = DarkroomColors.UnseenGlow, offset = DpOffset.Zero))
            .drawBehind {
                // box-shadow 0 0 0 1.5px：圆形暗环，外径 = 光点半径 + 1.5dp
                drawCircle(DarkroomColors.UnseenRing, radius = size.minDimension / 2f + Dimens.UnseenRing.toPx())
                drawCircle(DarkroomColors.UnseenDot)
            },
    )
}

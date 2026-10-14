package com.winters.cleanphotos.core.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 线性图标：24 视口、线宽 1.6、圆头圆角，颜色由调用处 tint。
 * 显示尺寸由调用处 Modifier.size 决定（.i 20 / .i.sm 16 / .ib .i 18 / .ring .i 24）。
 */
object DarkroomIcons {
    val Sort = icon("sort", "M8 5v14M8 5L5 8M8 5l3 3M16 19V5M16 19l-3-3M16 19l3-3")
    val Filter = icon("filter", "M4 7h16M7 12h10M10 17h4")
    val Chevron = icon("chev", "M9.5 6l6 6-6 6")
    val Back = icon("back", "M14.5 6l-6 6 6 6")
    val Star = icon("star", "M12 3.6l2.55 5.3 5.8.75-4.25 4.02 1.07 5.75L12 16.6l-5.17 2.82 1.07-5.75L3.65 9.65l5.8-.75z")
    val StarSolid = icon("star-solid", "M12 3.6l2.55 5.3 5.8.75-4.25 4.02 1.07 5.75L12 16.6l-5.17 2.82 1.07-5.75L3.65 9.65l5.8-.75z", fill = true)
    val Trash = icon("trash", "M4.5 7h15M10 11v6M14 11v6M6.5 7l.8 11.2A2 2 0 009.3 20h5.4a2 2 0 002-1.8L17.5 7M9.5 7V4.5h5V7")
    val Arrow = icon("arrow", "M5 12h14M13 6l6 6-6 6")
    val Undo = icon("undo", "M9 14L4.5 9.5 9 5M4.5 9.5H14a5.5 5.5 0 010 11h-3")
    val More = icon("more", "M6 12h.01M12 12h.01M18 12h.01", strokeWidth = 2.6f)
    val Close = icon("close", "M6.5 6.5l11 11M17.5 6.5l-11 11")
    val Check = icon("check", "M5.5 12.5l4 4 9-9")
    /** .tile .ck .i：勾选徽标里线宽 2.4。 */
    val CheckBold = icon("check-bold", "M5.5 12.5l4 4 9-9", strokeWidth = 2.4f)
    val Restore = icon("restore", "M12 15V4M7.5 8.5L12 4l4.5 4.5M5 13v5a2 2 0 002 2h10a2 2 0 002-2v-5")
    val Up = icon("up", "M12 19V5M6 11l6-6 6 6")
    val Down = icon("down", "M12 5v14M6 13l6 6 6-6")
    val LeftRight = icon("lr", "M4 12h16M8 8l-4 4 4 4M16 8l4 4-4 4")

    private fun icon(name: String, path: String, strokeWidth: Float = 1.6f, fill: Boolean = false): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                fill = if (fill) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
}

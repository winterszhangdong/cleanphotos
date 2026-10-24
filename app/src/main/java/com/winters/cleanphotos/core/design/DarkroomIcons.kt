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
    /**
     * 实心三角。24 视口里宽 16、高 19.2，重心比中心右移约 1。
     * 配 [Dimens.PlaybackCenterIcon] 36dp 时，画面上大约宽 24dp、高 29dp。
     */
    val Play = icon("play", "M7.7 2.4l16 9.6-16 9.6z", strokeWidth = 0f, fill = true)
    /** 同心圆 + 外圈虚线。ImageVector 没有虚线，外圈用八段弧近似。 */
    val Motion = motionIcon()
    val SoundOn = icon("sound-on", "M4.5 9.5v5h3.2l3.8 3V6.5l-3.8 3zM14.6 9.2a3.2 3.2 0 010 5.6M17 7a6 6 0 010 10")
    val SoundOff = icon("sound-off", "M4.5 9.5v5h3.2l3.8 3V6.5l-3.8 3zM16 9.2l4.2 5.6M20.2 9.2L16 14.8")

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
                stroke = if (strokeWidth > 0f) SolidColor(Color.Black) else null,
                strokeLineWidth = if (strokeWidth > 0f) strokeWidth else 0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    private fun motionIcon(): ImageVector {
        val stroke = SolidColor(Color.Black)
        val builder = ImageVector.Builder(
            name = "motion",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        fun add(path: String) {
            builder.addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                stroke = stroke,
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        add("M12 8.6A3.4 3.4 0 1 0 12 15.4")
        add("M12 15.4A3.4 3.4 0 1 0 12 8.6")
        add("M12.00 3.80A8.2 8.2 0 0 1 15.07 4.40")
        add("M17.80 6.20A8.2 8.2 0 0 1 19.55 8.80")
        add("M20.20 12.00A8.2 8.2 0 0 1 19.60 15.07")
        add("M17.80 17.80A8.2 8.2 0 0 1 15.20 19.55")
        add("M12.00 20.20A8.2 8.2 0 0 1 8.93 19.60")
        add("M6.20 17.80A8.2 8.2 0 0 1 4.45 15.20")
        add("M3.80 12.00A8.2 8.2 0 0 1 4.40 8.93")
        add("M6.20 6.20A8.2 8.2 0 0 1 8.80 4.45")
        return builder.build()
    }
}

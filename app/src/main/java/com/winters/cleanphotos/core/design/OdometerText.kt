package com.winters.cleanphotos.core.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset

/**
 * 里程表数字：每一位独立滚动，增加向上、减少向下；落定弹簧 0.75 / 400，逐位 translationY + 透明度。
 */
object Odometer {
    enum class Direction { Up, Down, None }

    fun direction(old: Long, new: Long): Direction = when {
        new > old -> Direction.Up
        new < old -> Direction.Down
        else -> Direction.None
    }

    /** 右对齐后逐位比较，返回每个字符位是否需要滚动。 */
    fun changedPositions(old: String, new: String): List<Boolean> {
        val pad = maxOf(old.length, new.length)
        val o = old.padStart(pad)
        val n = new.padStart(pad)
        return n.indices.map { o[it] != n[it] }.takeLast(new.length)
    }
}

/**
 * @param value 数值，用于决定滚动方向
 * @param format 数值到展示文本（例如千分位 "1,284"）
 */
@Composable
fun OdometerText(
    value: Long,
    style: TextStyle,
    modifier: Modifier = Modifier,
    format: (Long) -> String = { it.toString() },
) {
    val previous = remember { longArrayOf(value) }
    val direction = Odometer.direction(previous[0], value)
    val text = format(value)
    Row(modifier = modifier.clipToBounds()) {
        text.forEachIndexed { indexFromStart, ch ->
            val keyFromEnd = text.length - indexFromStart
            androidx.compose.runtime.key(keyFromEnd) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = { odometerTransition(direction) },
                    label = "odometer-digit",
                ) { c -> Text(text = c.toString(), style = style) }
            }
        }
    }
    SideEffect { previous[0] = value }
}

private fun odometerTransition(direction: Odometer.Direction): ContentTransform {
    val spring = MotionSprings.Settle.spec(IntOffset(1, 1))
    val fade = tween<Float>(MotionDurations.SHORT, easing = MotionEasings.Standard)
    return when (direction) {
        Odometer.Direction.Up ->
            (slideInVertically(spring) { it } + fadeIn(fade)) togetherWith (slideOutVertically(spring) { -it } + fadeOut(fade))
        Odometer.Direction.Down ->
            (slideInVertically(spring) { -it } + fadeIn(fade)) togetherWith (slideOutVertically(spring) { it } + fadeOut(fade))
        Odometer.Direction.None -> fadeIn(fade) togetherWith fadeOut(fade)
    }
}

package com.winters.cleanphotos.feature.review

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomIcons
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.LocalAnimationsEnabled
import com.winters.cleanphotos.core.design.MotionDurations
import com.winters.cleanphotos.core.design.MotionEasings
import com.winters.cleanphotos.core.design.plainClickable

/**
 * CSS 关键帧求值：stops 为 (百分比 0..1, 值)，每段内按 animation-timing-function（这里是 --ease-out）。
 */
internal fun keyframes(t: Float, stops: List<Pair<Float, Float>>, easing: androidx.compose.animation.core.Easing = MotionEasings.DraftEaseOut): Float {
    if (t <= stops.first().first) return stops.first().second
    for (i in 1 until stops.size) {
        val (t1, v1) = stops[i]
        val (t0, v0) = stops[i - 1]
        if (t <= t1) {
            if (t1 <= t0) return v1
            return v0 + (v1 - v0) * easing.transform((t - t0) / (t1 - t0))
        }
    }
    return stops.last().second
}

/** 三段各 2.4s（上滑 → 下滑 → 左右）。 */
internal object TutorialTimeline {
    val dotOpacity = listOf(0f to 0f, .04f to 1f, .22f to 1f, .28f to 0f, .333f to 0f, .334f to 0f, .374f to 1f, .554f to 1f, .61f to 0f, .666f to 0f, .667f to 0f, .707f to 1f, .88f to 1f, .94f to 0f, 1f to 0f)
    val dotX = listOf(0f to 0f, .666f to 0f, .667f to 56f, .707f to 56f, .88f to -56f, .94f to -64f, 1f to -64f)
    val dotY = listOf(0f to 0f, .04f to 0f, .22f to -96f, .28f to -104f, .333f to -104f, .334f to 0f, .374f to 0f, .554f to 96f, .61f to 104f, .666f to 104f, .667f to 0f, 1f to 0f)
    val dotScale = listOf(0f to 1.2f, .04f to .9f, .333f to .9f, .334f to 1.2f, .374f to .9f, .666f to .9f, .667f to 1.2f, .707f to .9f, 1f to .9f)
    val trailUpOpacity = listOf(0f to 1f, .22f to 1f, .32f to 0f, 1f to 0f)
    val trailUpScale = listOf(0f to 0f, .04f to 0f, .22f to 1f, 1f to 1f)
    val trailDownOpacity = listOf(0f to 0f, .374f to 0f, .375f to 1f, .554f to 1f, .65f to 0f, 1f to 0f)
    val trailDownScale = listOf(0f to 0f, .375f to 0f, .554f to 1f, 1f to 1f)
    val trailSideOpacity = listOf(0f to 0f, .707f to 0f, .708f to 1f, .88f to 1f, .98f to 0f, 1f to 0f)
    val trailSideScale = listOf(0f to 0f, .708f to 0f, .88f to 1f, 1f to 1f)
    /** 终点亮起 0..1。 */
    val star = listOf(0f to 0f, .18f to 0f, .22f to 1f, .28f to 1f, .36f to 0f, 1f to 0f)
    val bin = listOf(0f to 0f, .51f to 0f, .554f to 1f, .61f to 1f, .69f to 0f, 1f to 0f)

    /** tut-row：线性，0–31% 亮、34% 起 0.38；第 2、3 行分别提前 4.8s / 2.4s（即相位 +1/3、+2/3）。 */
    fun rowOpacity(t: Float, row: Int): Float {
        val phase = (t - row / 3f + 1f) % 1f
        return keyframes(phase, listOf(0f to 1f, .31f to 1f, .34f to .38f, 1f to .38f), LinearEasing)
    }
}

/**
 * 首次教学：照片压暗到 32%（在舞台里做），中间光点依次演示上滑（琥珀）、下滑、左右（相纸白），
 * 每段 2.4s；三行文字依次点亮；「知道了」或完成任意一次滑动后永久关闭。不拦截滑动手势（只有按钮可点）。
 */
@Composable
internal fun ReviewTutorialOverlay(topInset: Dp, bottomInset: Dp, onKnow: () -> Unit) {
    val animations = LocalAnimationsEnabled.current
    val time: State<Float> = if (animations) {
        rememberInfiniteTransition(label = "tutorial").animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(MotionDurations.TUTORIAL_SEGMENT * 3, easing = LinearEasing), RepeatMode.Restart),
            label = "tutorial-t",
        )
    } else remember { mutableFloatStateOf(0.2f) }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = topInset + STAGE_TOP)
                .size(200.dp, 264.dp),
        ) {
            TutorialStage(time)
        }
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = topInset + ROWS_TOP, start = 32.dp, end = 32.dp)
                .fillMaxWidth(),
        ) {
            TutRow(0, time, DarkroomIcons.Up, DarkroomColors.Amber, "向上滑动", "收藏照片", last = false)
            TutRow(1, time, DarkroomIcons.Down, DarkroomColors.Paper, "向下滑动", "移到垃圾桶", last = false)
            TutRow(2, time, DarkroomIcons.LeftRight, DarkroomColors.Paper, "左右滑动", "切换照片", last = true)
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomInset + 40.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(DarkroomColors.Paper)
                .plainClickable(onClick = onKnow)
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center,
        ) { Text("知道了", style = DarkroomText.TutOk) }
    }
}

@Composable
private fun TutRow(index: Int, time: State<Float>, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, title: String, right: String, last: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .graphicsLayer { alpha = TutorialTimeline.rowOpacity(time.value, index) }
            .drawBehind {
                if (!last) drawRect(DarkroomColors.Rim, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        }
        Text(title, style = DarkroomText.TutRowTitle)
        Spacer(Modifier.weight(1f))
        Text(right, style = DarkroomText.TutRowRight)
    }
}

@Composable
private fun TutorialStage(time: State<Float>) {
    Box(Modifier.fillMaxSize()) {
        // 虚线轴：竖轴 top/bottom 44，横轴 left/right 24
        Canvas(Modifier.fillMaxSize()) {
            val c = DarkroomColors.Paper.copy(alpha = 0.14f)
            val dash = 3.dp.toPx()
            val step = 7.dp.toPx()
            val cx = size.width / 2f
            var y = 44.dp.toPx()
            while (y < size.height - 44.dp.toPx()) { drawRect(c, Offset(cx, y), Size(1.dp.toPx(), dash)); y += step }
            val cy = size.height / 2f
            var x = 24.dp.toPx()
            while (x < size.width - 24.dp.toPx()) { drawRect(c, Offset(x, cy), Size(dash, 1.dp.toPx())); x += step }
        }
        // 光迹
        Trail(time, Modifier.align(Alignment.TopCenter).offset(y = 36.dp).size(3.dp, 96.dp), TutorialTimeline.trailUpOpacity, TutorialTimeline.trailUpScale, vertical = true, origin = TransformOrigin(0.5f, 1f),
            brush = Brush.verticalGradient(listOf(DarkroomColors.Amber.copy(alpha = 0.9f), DarkroomColors.Amber.copy(alpha = 0f))))
        Trail(time, Modifier.align(Alignment.TopCenter).offset(y = 132.dp).size(3.dp, 96.dp), TutorialTimeline.trailDownOpacity, TutorialTimeline.trailDownScale, vertical = true, origin = TransformOrigin(0.5f, 0f),
            brush = Brush.verticalGradient(listOf(DarkroomColors.Paper.copy(alpha = 0f), DarkroomColors.Paper.copy(alpha = 0.6f))))
        Trail(time, Modifier.align(Alignment.CenterStart).offset(x = 44.dp).size(112.dp, 3.dp), TutorialTimeline.trailSideOpacity, TutorialTimeline.trailSideScale, vertical = false, origin = TransformOrigin(1f, 0.5f),
            brush = Brush.horizontalGradient(listOf(DarkroomColors.Paper.copy(alpha = 0.6f), DarkroomColors.Paper.copy(alpha = 0f))))
        // 终点
        TutEnd(time, TutorialTimeline.star, amber = true, Modifier.align(Alignment.TopCenter).offset(y = 4.dp))
        TutEnd(time, TutorialTimeline.bin, amber = false, Modifier.align(Alignment.BottomCenter).offset(y = (-4).dp))
        // 光点 28dp + 6dp 光晕 + 24dp 柔光
        Box(
            Modifier
                .align(Alignment.Center)
                .size(28.dp)
                .graphicsLayer {
                    val t = time.value
                    alpha = keyframes(t, TutorialTimeline.dotOpacity)
                    translationX = keyframes(t, TutorialTimeline.dotX).dp.toPx()
                    translationY = keyframes(t, TutorialTimeline.dotY).dp.toPx()
                    val s = keyframes(t, TutorialTimeline.dotScale)
                    scaleX = s; scaleY = s
                }
                .drawBehind {
                    drawCircle(Brush.radialGradient(listOf(DOT_GLOW, Color.Transparent), radius = size.minDimension / 2f + 24.dp.toPx()), radius = size.minDimension / 2f + 24.dp.toPx())
                    drawCircle(DOT_RING, radius = size.minDimension / 2f + 6.dp.toPx())
                    drawCircle(DOT_FILL)
                },
        )
    }
}

@Composable
private fun Trail(
    time: State<Float>, modifier: Modifier, opacity: List<Pair<Float, Float>>, scale: List<Pair<Float, Float>>,
    vertical: Boolean, origin: TransformOrigin, brush: Brush,
) {
    Box(
        modifier
            .graphicsLayer {
                val t = time.value
                alpha = keyframes(t, opacity)
                val s = keyframes(t, scale)
                if (vertical) scaleY = s else scaleX = s
                transformOrigin = origin
            }
            .clip(RoundedCornerShape(2.dp))
            .background(brush),
    )
}

@Composable
private fun TutEnd(time: State<Float>, stops: List<Pair<Float, Float>>, amber: Boolean, modifier: Modifier) {
    Box(
        modifier
            .size(32.dp)
            .drawBehind {
                val on = keyframes(time.value, stops)
                val r = size.minDimension / 2f
                if (amber && on > 0f) drawCircle(DarkroomColors.Amber.copy(alpha = 0.2f * on), radius = r + 4.dp.toPx())
                val fill = if (amber) DarkroomColors.Amber else DarkroomColors.Paper
                if (on > 0f) drawCircle(fill.copy(alpha = on), radius = r)
                if (on < 1f) drawCircle(DarkroomColors.Paper.copy(alpha = 0.2f * (1f - on)), radius = r - 0.5.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            },
        contentAlignment = Alignment.Center,
    ) {
        val icon = if (amber) DarkroomIcons.StarSolid else DarkroomIcons.Trash
        Box(Modifier.graphicsLayer { }) {
            Icon(
                icon, null,
                tint = lerp(DarkroomColors.Paper.copy(alpha = 0.5f), DarkroomColors.Room, keyframes(time.value, stops)),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private val STAGE_TOP = 100.dp
private val ROWS_TOP = 400.dp
private val DOT_FILL = Color(0xFFFFF8EE).copy(alpha = 0.92f)
private val DOT_RING = Color(0xFFFFF6E8).copy(alpha = 0.12f)
private val DOT_GLOW = Color(0xFFFFF6E8).copy(alpha = 0.5f)

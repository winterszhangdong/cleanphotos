package com.winters.cleanphotos.core.design

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.State
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * 空状态容器（.empty）：在页头（状态栏 + 84dp）与底部导航（24 + 44 + 24）之间居中，内容水平居中。
 */
@Composable
fun EmptyStateArea(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(top = Dimens.EmptyTop - 32.dp, bottom = Dimens.EmptyBottom),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** .empty h4 + p（p 最宽 232，上 8 下 24）。 */
@Composable
fun EmptyTitleAndBody(title: String, body: String) {
    Text(title, style = DarkroomText.EmptyH4, textAlign = TextAlign.Center)
    Text(
        body,
        style = DarkroomText.EmptyP,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp, bottom = 24.dp).width(Dimens.EmptyTextWidth),
    )
}

/** CSS 的 rotate(θ) translateX(dx)：先旋转坐标系再平移，等价于平移向量随之旋转。 */
private fun Modifier.rotatedShift(degrees: Float, dx: Dp, alpha: Float): Modifier = graphicsLayer {
    val r = Math.toRadians(degrees.toDouble())
    val px = dx.toPx()
    translationX = (px * cos(r)).toFloat()
    translationY = (px * sin(r)).toFloat()
    rotationZ = degrees
    this.alpha = alpha
}

/**
 * 空状态的「一叠相纸」：两张托盘色底卡左右斜放，最上面一张演示手势后回到原位，周期 3.6s。
 * 空状态循环（周期 T = 3600ms，1px = 1.575dp）：
 * - 顶卡 18%（648ms）起落定弹簧推到 ∓28px（44dp）+ ∓3°，停到 55%（1980ms），弹跳弹簧回落；
 * - 星标 22%（792ms）起弹跳到 1.25 + 琥珀光晕，55% → 70% 线性回 1；
 * - 箭头 12% → 22% 减速淡入（下移 8px → 0），22% → 32% 线性淡出并上移 10px。
 * sink = true 为垃圾桶空状态：同一组版式，相纸往下沉、图标换垃圾桶、箭头朝下，无琥珀光晕。
 * 系统关闭动画时停在静止插画（ui-design.md「移除动画」）。
 */
@Composable
fun FavoritePileArt(modifier: Modifier = Modifier) = EmptyPileArt(sink = false, modifier = modifier)

@Composable
fun EmptyPileArt(sink: Boolean, modifier: Modifier = Modifier) {
    val enabled = LocalAnimationsEnabled.current
    val push = remember { Animatable(0f) }
    val star = remember { Animatable(0f) }
    val arrow = remember { Animatable(0f) }
    LaunchedEffect(enabled) {
        push.snapTo(0f); star.snapTo(0f); arrow.snapTo(0f)
        if (!enabled) return@LaunchedEffect
        while (true) {
            coroutineScope {
                launch { delay(EmptyCycle.PUSH_AT); push.animateTo(1f, MotionSprings.Settle.spec()) }
                launch { delay(EmptyCycle.DROP_AT); push.animateTo(0f, MotionSprings.Bouncy.spec()) }
                launch { delay(EmptyCycle.STAR_AT); star.animateTo(1f, MotionSprings.Bouncy.spec()) }
                launch { delay(EmptyCycle.DROP_AT); star.animateTo(0f, tween(EmptyCycle.STAR_BACK, easing = LinearEasing)) }
                launch {
                    delay(EmptyCycle.ARROW_IN_AT)
                    arrow.animateTo(1f, tween(EmptyCycle.ARROW_STEP, easing = MotionEasings.Decelerate))
                    arrow.animateTo(2f, tween(EmptyCycle.ARROW_STEP, easing = LinearEasing))
                }
                launch { delay(MotionDurations.EMPTY_CYCLE.toLong()) }
            }
            arrow.snapTo(0f)
        }
    }
    val dir = if (sink) 1f else -1f
    val cardShape = RoundedCornerShape(Dimens.PileCardCorner)
    val cardModifier = Modifier
        .offset(x = 30.dp, y = 24.dp)
        .size(Dimens.PileCardWidth, Dimens.PileCardHeight)
    Box(modifier.size(Dimens.PileWidth, Dimens.PileHeight)) {
        Box(cardModifier.rotatedShift(-10f, (-16).dp, 0.6f).clip(cardShape).background(DarkroomColors.Tray).border(Dimens.Hairline, DarkroomColors.Rim, cardShape))
        Box(cardModifier.rotatedShift(8f, 16.dp, 0.8f).clip(cardShape).background(DarkroomColors.Tray).border(Dimens.Hairline, DarkroomColors.Rim, cardShape))
        Box(
            cardModifier
                .graphicsLayer {
                    translationY = dir * EmptyCycle.PUSH_DISTANCE.toPx() * push.value
                    rotationZ = dir * EmptyCycle.PUSH_ROTATION * push.value
                }
                .dropShadow(cardShape, Shadow(radius = 40.dp, color = if (sink) DarkroomColors.PrintShadow else DarkroomColors.PileGlow))
                .clip(cardShape)
                .background(DarkroomColors.PileCard)
                .border(Dimens.Hairline, if (sink) DarkroomColors.Rim else DarkroomColors.PileRim, cardShape),
            contentAlignment = Alignment.Center,
        ) {
            val iconMod = Modifier.size(28.dp).graphicsLayer {
                val s = 1f + (EmptyCycle.STAR_SCALE - 1f) * star.value
                scaleX = s; scaleY = s
            }
            if (sink) {
                Icon(DarkroomIcons.Trash, null, tint = DarkroomColors.Dim, modifier = iconMod)
            } else {
                if (ApiTier.Current.hasBlur) {
                    Icon(
                        DarkroomIcons.StarSolid, null, tint = DarkroomColors.PileStarGlow,
                        modifier = iconMod.graphicsLayer { alpha = star.value.coerceIn(0f, 1f) }.blur(8.dp, BlurredEdgeTreatment.Unbounded),
                    )
                }
                Icon(DarkroomIcons.StarSolid, null, tint = DarkroomColors.Amber, modifier = iconMod)
            }
        }
        // .c16-arrow：顶卡上方的方向箭头（下沉时在下方）
        Icon(
            if (sink) DarkroomIcons.Down else DarkroomIcons.Up, null,
            tint = if (sink) DarkroomColors.Dim else DarkroomColors.Amber,
            modifier = Modifier
                .align(if (sink) Alignment.BottomCenter else Alignment.TopCenter)
                .offset(y = EmptyCycle.ARROW_GAP * dir)
                .size(EmptyCycle.ARROW_SIZE)
                .graphicsLayer {
                    val v = arrow.value
                    alpha = if (v <= 1f) EmptyCycle.ARROW_ALPHA * v else EmptyCycle.ARROW_ALPHA * (2f - v)
                    val y = if (v <= 1f) EmptyCycle.ARROW_RISE_IN.toPx() * (1f - v) else -EmptyCycle.ARROW_RISE_OUT.toPx() * (v - 1f)
                    translationY = -dir * y
                },
        )
    }
}

private object EmptyCycle {
    const val PUSH_AT = 648L
    const val DROP_AT = 1980L
    const val STAR_AT = 792L
    const val STAR_BACK = 540
    const val ARROW_IN_AT = 432L
    const val ARROW_STEP = 360
    const val STAR_SCALE = 1.25f
    const val PUSH_ROTATION = 3f
    const val ARROW_ALPHA = 0.9f
    val PUSH_DISTANCE = 44.dp
    val ARROW_RISE_IN = 12.6.dp
    val ARROW_RISE_OUT = 15.75.dp
    val ARROW_SIZE = 19.dp
    /** 箭头距顶卡 30px（47dp），顶卡距盒顶 24dp。 */
    val ARROW_GAP = 23.dp
}

/**
 * 晾绳：248×152，一根下垂的细绳（Q 曲线），三张相纸夹在绳上轻摆 ±2.5°（4s，各自错开）。
 */
@Composable
fun ClotheslineArt(photos: List<Painter>, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "clothesline")
    // h1 -1.2s、h2 -2.4s、h3 -0.4s；sway 0%/100% = -2.5°，50% = 2.5°
    val delays = listOf(1200, 2400, 400)
    val positions = listOf(DpOffset(20.dp, 26.dp), DpOffset(94.dp, 33.dp), DpOffset(168.dp, 26.dp))
    Box(modifier.size(Dimens.DoneArtWidth, Dimens.DoneArtHeight)) {
        Canvas(Modifier.fillMaxWidth().size(Dimens.DoneArtWidth, 40.dp)) {
            val sx = size.width / 248f
            val sy = size.height / 40f
            val path = Path().apply {
                moveTo(0f, 18f * sy)
                quadraticTo(124f * sx, 40f * sy, 248f * sx, 18f * sy)
            }
            drawPath(path, DarkroomColors.HangLine, style = Stroke(width = 1.dp.toPx()))
        }
        photos.take(3).forEachIndexed { i, painter ->
            val angle = t.animateFloat(
                initialValue = -2.5f, targetValue = 2.5f,
                animationSpec = infiniteRepeatable(
                    animation = tween(MotionDurations.CLOTHESLINE_SWING / 2, easing = EaseInOut),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(delays[i], StartOffsetType.FastForward),
                ),
                label = "sway-$i",
            )
            HangingPrint(painter, angle, Modifier.offset(positions[i].x, positions[i].y))
        }
    }
}

@Composable
private fun HangingPrint(painter: Painter, angle: State<Float>, modifier: Modifier) {
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier.graphicsLayer {
            rotationZ = angle.value
            // transform-origin: 50% -6px
            transformOrigin = TransformOrigin(0.5f, -6.dp.toPx() / size.height)
        },
    ) {
        Box(
            Modifier
                .width(60.dp)
                .dropShadow(shape, Shadow(radius = 24.dp, color = DarkroomColors.PrintShadow, offset = DpOffset(0.dp, 12.dp)))
                .clip(shape)
                .background(DarkroomColors.PrintPaper)
                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 12.dp),
        ) {
            Image(painter, null, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp, 64.dp))
        }
        // ::before 夹子：8×14，顶 -8，#3A3632 + 12% 内描边
        val clip = RoundedCornerShape(2.dp)
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-8).dp)
                .size(8.dp, 14.dp)
                .clip(clip)
                .background(DarkroomColors.HangClip)
                .border(Dimens.Hairline, DarkroomColors.MenuRim, clip),
        )
    }
}

/**
 * 三张相纸在显影液里反复显影（4.8s：0% 暗 → 45% 显出 → 85% 保持 → 100% 回暗；p1 / p3 各延后 0.3s / 0.6s）。
 */
@Composable
fun DevelopingPrintsArt(left: Painter, center: Painter, right: Painter, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "prints")
    val animate = LocalAnimationsEnabled.current
    @Composable
    fun progress(delay: Int): State<Float> {
        // 关闭动画时 InfiniteTransition 直接跳到终值 0（全暗），这里停在显影完成的那一帧
        if (!animate) return remember { mutableFloatStateOf(1f) }
        val cycle = 4800
        return t.animateFloat(
            initialValue = 0f, targetValue = 0f,
            animationSpec = infiniteRepeatable(
                keyframes {
                    durationMillis = cycle
                    0f at 0 using MotionEasings.DraftEaseOut
                    1f at (cycle * 0.45f).toInt() using MotionEasings.DraftEaseOut
                    1f at (cycle * 0.85f).toInt() using MotionEasings.DraftEaseOut
                    0f at cycle
                },
                initialStartOffset = StartOffset(delay),
            ),
            label = "print-$delay",
        )
    }
    val pl = progress(300)
    val pc = progress(0)
    val pr = progress(600)
    Box(modifier.fillMaxWidth().size(width = Dp.Unspecified, height = 232.dp)) {
        Print(left, pl, Modifier.align(Alignment.TopCenter).graphicsLayer { translationX = -72.dp.toPx(); rotationZ = -9f })
        Print(right, pr, Modifier.align(Alignment.TopCenter).graphicsLayer { translationX = 72.dp.toPx(); rotationZ = 8f })
        Print(center, pc, Modifier.align(Alignment.TopCenter).graphicsLayer { translationY = -8.dp.toPx() })
    }
}

@Composable
private fun Print(painter: Painter, progress: State<Float>, modifier: Modifier) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .padding(top = 24.dp)
            .size(120.dp, 152.dp)
            .dropShadow(shape, Shadow(radius = 32.dp, color = DarkroomColors.PrintShadow, offset = DpOffset(0.dp, 16.dp)))
            .clip(shape)
            .background(DarkroomColors.PrintPaper)
            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 20.dp),
    ) {
        Image(
            painter, null,
            contentScale = ContentScale.Crop,
            // filter: brightness(0.05→1) contrast(0.6→1) saturate(0→1)；只在图层阶段读进度，不触发重组。
            // 每帧新数组：ColorFilter 按矩阵内容判等（同 Develop.kt）
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val p = progress.value
                colorFilter = ColorFilter.colorMatrix(
                    ColorMatrix(DevelopMatrix.compose(brightness = 0.05f + 0.95f * p, contrast = 0.6f + 0.4f * p, saturation = p)),
                )
            },
        )
    }
}

package com.winters.cleanphotos.core.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * 玻璃背景模糊（Haze 1.7.0）。用户决定：API 31+ 真模糊，26–30 按分级表用同色压暗代替；开关只看 [ApiTier.hasBlur]。
 *
 * 用法：页面根部用 [GlassBackdropHost] 包住，被模糊的内容（列表 / 照片）加 [glassBackdropSource]，
 * 玻璃组件（导航胶囊、玻璃圆钮）内部用 [glassBlur]。
 *
 * 版本说明：Haze 2.x / 1.7.1+ 要求 compileSdk 37，本项目是 36，所以锁 1.7.0。
 * 1.7.0 不支持对背景做饱和度增强，导航的 saturate(1.3) 暂未实现。
 */
val LocalGlassHazeState = staticCompositionLocalOf<HazeState> {
    error("玻璃组件必须放在 GlassBackdropHost 里")
}

@Composable
fun GlassBackdropHost(tier: ApiTier = ApiTier.Current, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlassHazeState provides rememberHazeState(blurEnabled = tier.hasBlur), content = content)
}

@Composable
fun Modifier.glassBackdropSource(): Modifier = hazeSource(LocalGlassHazeState.current)

/** 规格里的玻璃：tint = 玻璃底色，blur 半径。 */
data class GlassSpec(val tint: Color, val blur: Dp)

object GlassSpecs {
    /** .glass-btn：rgba(20,18,16,0.56) + blur(16px)。 */
    val Button = GlassSpec(DarkroomColors.Glass, Dimens.GlassBlur)
    /** .nav：rgba(30,28,25,0.72) + blur(20px)（saturate(1.3) 见上方版本说明）。 */
    val Nav = GlassSpec(DarkroomColors.NavGlass, Dimens.NavBlur)
}

fun glassStyle(spec: GlassSpec): HazeStyle = HazeStyle(
    backgroundColor = DarkroomColors.Room,
    tints = listOf(HazeTint(spec.tint)),
    blurRadius = spec.blur,
    noiseFactor = 0f,
    // 26–30：不模糊，只用同一玻璃色压暗
    fallbackTint = HazeTint(spec.tint),
)

@Composable
fun Modifier.glassBlur(spec: GlassSpec, tier: ApiTier = ApiTier.Current): Modifier =
    hazeEffect(state = LocalGlassHazeState.current, style = glassStyle(spec)) {
        blurEnabled = tier.hasBlur
    }

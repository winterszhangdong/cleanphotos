package com.winters.cleanphotos.core.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** 固定 dp 尺寸容器（底栏 72×36 标签、84dp 页头、50dp 天标题）里文字的系统字体缩放上限。 */
object FontScaleCaps {
    const val NAV_LABEL = 1.15f
    const val HEADER = 1.3f
    const val DAY_HEADER = 1.3f
}

/** 系统字体缩放不超过 [max] 时原样使用（1.0 时排版完全不变）；超过时按 [max] 排版，避免撑破固定尺寸的格子。 */
internal fun cappedDensity(current: Density, max: Float): Density =
    if (current.fontScale <= max) current else Density(current.density, max)

/** 内容里的 sp 文字最多按 [max] 倍缩放。 */
@Composable
fun CappedFontScale(max: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val capped = cappedDensity(density, max)
    if (capped === density) content() else CompositionLocalProvider(LocalDensity provides capped, content = content)
}

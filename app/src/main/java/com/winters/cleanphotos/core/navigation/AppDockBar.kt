package com.winters.cleanphotos.core.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.graphicsLayer
import com.winters.cleanphotos.core.design.AppBottomBar
import androidx.compose.runtime.CompositionLocalProvider
import com.winters.cleanphotos.core.design.BottomTab
import com.winters.cleanphotos.core.design.LocalGlassHazeState
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.MotionEasings
import com.winters.cleanphotos.core.design.plainClickable

/**
 * 公共层里唯一的一份底部导航。照片 / 收藏 / 垃圾桶共用同一个实例，选中项变化时下划线拉伸着滑过去。
 * 清理页（[DockMode.Landing]）不可点，只按 [LandingNavState.alpha] 作为离场落点浮现。
 */
@Composable
internal fun AppDockBar(
    dock: AppDockState,
    config: DockConfig?,
    selected: BottomTab,
    trashCount: Int,
    onSelect: (BottomTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 新目的地还没登记配置的那一两帧沿用上一份，导航始终留在组合里：下划线 / 计数的动画状态跨页连续，不会闪掉重建
    val lastConfig = remember { arrayOfNulls<DockConfig>(1) }
    if (config != null) lastConfig[0] = config
    val effective = config ?: lastConfig[0]
    val mode = effective?.mode ?: DockMode.Hidden
    val hidden = mode == DockMode.Hidden
    val landing = mode == DockMode.Landing
    val scrim = effective?.scrim
    val lastScrim = remember { arrayOfNulls<DockScrim>(1) }
    if (scrim != null) lastScrim[0] = scrim
    val scrimAlpha by animateFloatAsState(
        targetValue = if (scrim != null) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (scrim != null) scrim.enterMs else lastScrim[0]?.exitMs ?: 0,
            easing = MotionEasings.Standard,
        ),
        label = "dock-scrim",
    )
    Box(
        modifier
            .navigationBarsPadding()
            .padding(bottom = Dimens.NavBottom)
            .graphicsLayer {
                alpha = when {
                    hidden -> 0f
                    landing -> dock.landing.alpha.value
                    else -> 1f
                }
            },
    ) {
        // 导航始终在同一个组合位置（下划线 / 计数状态不丢）；只切换模糊取样的页面
        CompositionLocalProvider(LocalGlassHazeState provides (effective?.hazeState ?: LocalGlassHazeState.current)) {
            AppBottomBar(
                selected = selected,
                trashCount = trashCount,
                onSelect = onSelect,
                enabled = mode == DockMode.Visible,
                bounceTab = if (landing) dock.landing.tab else null,
                bounceSeq = if (landing) dock.landing.seq else 0,
            )
        }
        val s = lastScrim[0]
        // 淡出期间也要在：这里不读 scrimAlpha（读了会让整条导航随淡出每帧重组），只在图层里读
        if (s != null) {
            // 页面遮罩画在页面层，导航在其上：在胶囊范围内补一层同色遮罩，点它等同点页面遮罩
            Box(
                Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(Dimens.NavCorner))
                    .graphicsLayer { alpha = scrimAlpha }
                    .background(s.color)
                    .then(if (scrim != null) Modifier.plainClickable(onClick = scrim.onDismiss) else Modifier),
            )
        }
    }
}

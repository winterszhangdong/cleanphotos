package com.winters.cleanphotos.core.design

import androidx.annotation.StringRes
import com.winters.cleanphotos.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** 列表底部留白：导航距底 24 + 高 44 + 安全区，再加 16 空隙。 */
@Composable
fun dockListBottomPadding(): Dp {
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return nav + Dimens.NavBottom + Dimens.NavHeight + 16.dp
}

enum class BottomTab(@StringRes val label: Int) {
    AllPhotos(R.string.tab_photos),
    Favorites(R.string.tab_favorites),
    Trash(R.string.tab_trash),
}

/**
 * 底部导航缩成 44dp 高、只占中间的玻璃胶囊。
 * 选中项相纸白字 500 + 16×2dp 下划线（决定 6）；切换时下划线前沿走标准曲线先到、后沿用落定弹簧跟上（拉伸），
 * 文字颜色 280ms 渐变；垃圾桶计数用里程表滚动。外边距（居中、距底 24dp + 安全区）由调用处加。
 */
@Composable
fun AppBottomBar(
    selected: BottomTab,
    onSelect: (BottomTab) -> Unit,
    modifier: Modifier = Modifier,
    trashCount: Int? = null,
    /** false：不挂任何点击，胶囊不拦截下面的手势。 */
    enabled: Boolean = true,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val itemWidthPx = with(density) { Dimens.NavItemWidth.toPx() }
    val indicatorPx = with(density) { Dimens.NavIndicatorWidth.toPx() }
    fun leftFor(tab: BottomTab) = tab.ordinal * itemWidthPx + (itemWidthPx - indicatorPx) / 2f
    val left = remember { Animatable(leftFor(selected)) }
    val right = remember { Animatable(leftFor(selected) + indicatorPx) }
    LaunchedEffect(selected, itemWidthPx) {
        val targetLeft = leftFor(selected)
        val targetRight = targetLeft + indicatorPx
        val movingRight = targetLeft > left.value
        val lead = tween<Float>(MotionDurations.MEDIUM, easing = MotionEasings.Standard)
        val trail = MotionSprings.Settle.spec<Float>()
        coroutineScope {
            launch { left.animateTo(targetLeft, if (movingRight) trail else lead) }
            launch { right.animateTo(targetRight, if (movingRight) lead else trail) }
        }
    }
    val shape = RoundedCornerShape(Dimens.NavCorner)
    Box(
        modifier = modifier
            .dropShadow(shape, Shadow(radius = Dimens.NavShadowBlur, color = DarkroomColors.NavShadow, offset = DpOffset(0.dp, Dimens.NavShadowY)))
            .height(Dimens.NavHeight)
            .clip(shape)
            .glassBlur(GlassSpecs.Nav)
            .border(Dimens.Hairline, DarkroomColors.NavRim, shape)
            .padding(Dimens.NavPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BottomTab.entries.forEach { tab ->
                NavItem(
                    tab = tab,
                    selected = tab == selected,
                    count = if (tab == BottomTab.Trash) trashCount else null,
                    onClick = if (enabled) ({ onSelect(tab) }) else null,
                )
            }
        }
        // 下划线单独一层：动画每帧只重录这一小层。画在玻璃那一层里会让 Haze 模糊跟着每帧重录（P8 卡顿排查）
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer()
                .drawBehind {
                    // 只在绘制阶段读取下划线两条边，切换动画不触发重组
                    val h = Dimens.NavIndicatorHeight.toPx()
                    val y = Dimens.NavItemHeight.toPx() - Dimens.NavIndicatorBottom.toPx() - h
                    drawRoundRect(
                        color = DarkroomColors.Paper,
                        topLeft = Offset(left.value, y),
                        size = Size(right.value - left.value, h),
                        cornerRadius = CornerRadius(h / 2f),
                    )
                },
        )
    }
}

@Composable
private fun NavItem(tab: BottomTab, selected: Boolean, count: Int?, onClick: (() -> Unit)?) {
    val interaction = remember { MutableInteractionSource() }
    val color by animateColorAsState(
        targetValue = if (selected) DarkroomColors.Paper else DarkroomColors.Dim,
        animationSpec = tween(MotionDurations.MEDIUM, easing = MotionEasings.Standard),
        label = "nav-label",
    )
    Row(
        modifier = Modifier
            .size(Dimens.NavItemWidth, Dimens.NavItemHeight)
            .padding(bottom = Dimens.NavIndicatorHeight)
            .then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(Dimens.NavItemGap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      // 72×36 固定格子：大字号下标签和角标会被裁掉 / 挤出，缩放封顶（1.0 时不变）
      CappedFontScale(FontScaleCaps.NAV_LABEL) {
        // 颜色在绘制阶段读取：280ms 渐变期间不重组、不重新排版
        BasicText(
            text = stringResource(tab.label),
            // 与 material3 Text 一样叠在 LocalTextStyle 上，排版不变
            style = LocalTextStyle.current.merge(DarkroomText.NavLabel.copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)),
            color = { color },
        )
        if (count != null && count > 0) {
            Box(
                modifier = Modifier
                    .height(Dimens.NavBadgeHeight)
                    .defaultMinSize(minWidth = Dimens.NavBadgeMinWidth)
                    .clip(RoundedCornerShape(Dimens.NavBadgeHeight / 2))
                    .background(DarkroomColors.BadgeFill)
                    .padding(horizontal = Dimens.NavBadgePaddingH),
                contentAlignment = Alignment.Center,
            ) {
                OdometerText(value = count.toLong(), style = DarkroomText.NavBadge)
            }
        }
      }
    }
}

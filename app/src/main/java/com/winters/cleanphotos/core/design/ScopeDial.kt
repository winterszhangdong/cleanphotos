package com.winters.cleanphotos.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 范围刻度的位置和透明度。进度以页为单位，可以为小数，也可以滑出两端。
 * 颜色是相纸白乘 [alpha]，不另换色。
 */
object ScopeDialMath {
    const val MIN_ALPHA = 0.22f
    const val FALLOFF = 0.55f

    /** 进入页亮度 0.7：在页上叠一层暗室色，透明度是这个数。 */
    const val PAGE_DIM = 0.3f

    fun alpha(distance: Float): Float = maxOf(MIN_ALPHA, 1f - FALLOFF * distance)

    /** 第 [index] 个标签的文字中心，相对第一段文字的左边缘。 */
    fun center(index: Int, widths: FloatArray, gap: Float): Float {
        var x = 0f
        for (i in 0 until index) x += widths[i] + gap
        return x + widths[index] / 2f
    }

    /**
     * 进度 [progress] 处的文字中心。相邻标签之间线性，两端按相邻中心的间距外推。
     * 只有一个标签时，外推步长是文字宽加间距。
     */
    fun centerAt(progress: Float, widths: FloatArray, gap: Float): Float {
        if (widths.isEmpty()) return 0f
        val last = widths.lastIndex
        if (last == 0) return widths[0] / 2f + progress * (widths[0] + gap)
        val i0 = floor(progress.toDouble()).toInt()
        val t = progress - i0
        return at(i0, widths, gap, last) + (at(i0 + 1, widths, gap, last) - at(i0, widths, gap, last)) * t
    }

    private fun at(index: Int, widths: FloatArray, gap: Float, last: Int): Float = when {
        index < 0 -> {
            val step = center(1, widths, gap) - center(0, widths, gap)
            center(0, widths, gap) + index * step
        }
        index > last -> {
            val step = center(last, widths, gap) - center(last - 1, widths, gap)
            center(last, widths, gap) + (index - last) * step
        }
        else -> center(index, widths, gap)
    }

    /** 让 [progress] 的文字中心落在视口中心。 */
    fun translationX(viewportCenter: Float, progress: Float, widths: FloatArray, gap: Float): Float =
        viewportCenter - centerAt(progress, widths, gap)

    /** 热区：文字左右各延伸半个间距，并且至少 [minTouch] 宽。 */
    fun hitWidth(textWidth: Float, gap: Float, minTouch: Float): Float = maxOf(textWidth + gap, minTouch)
}

/** 布局阶段写下文字宽度，绘制阶段读。不放进组合状态。 */
private class DialMeasures {
    var widths: FloatArray = FloatArray(0)
    var gapPx: Float = 0f
}

/**
 * 照片页范围刻度。选中项由 [progress] 居中（页单位，可为小数）。
 * [progress] 只在 graphicsLayer 里读。
 * [selectedIndex] 只给读屏报当前停稳的项。
 */
@Composable
fun ScopeDial(
    labels: List<String>,
    progress: () -> Float,
    onSelect: (Int) -> Unit,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { Dimens.ScopeDialGap.toPx() }
    val minTouchPx = with(density) { Dimens.MinTouch.toPx() }
    val fadePx = with(density) { Dimens.ScopeDialEdgeFade.toPx() }
    val measures = remember { DialMeasures() }
    Box(
        modifier
            .fillMaxWidth()
            .height(Dimens.ScopeRowHeight)
            .clipToBounds()
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to DarkroomColors.Room,
                        1f to Color.Transparent,
                        startX = 0f,
                        endX = fadePx,
                    ),
                    size = Size(fadePx, size.height),
                )
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        1f to DarkroomColors.Room,
                        startX = size.width - fadePx,
                        endX = size.width,
                    ),
                    topLeft = Offset(size.width - fadePx, 0f),
                    size = Size(fadePx, size.height),
                )
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = ScopeDialMath.translationX(
                        size.width / 2f,
                        progress(),
                        measures.widths,
                        measures.gapPx,
                    )
                },
        ) {
            Layout(
                content = {
                    labels.forEachIndexed { index, label ->
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .semantics { selected = index == selectedIndex }
                                .plainClickable(role = Role.Tab) { onSelect(index) },
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Text(
                                label,
                                style = DarkroomText.ScopeDial,
                                color = DarkroomColors.Paper,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier
                                    .padding(top = Dimens.ScopeDialTextTop)
                                    .graphicsLayer { alpha = ScopeDialMath.alpha(abs(index - progress())) },
                            )
                        }
                    }
                },
            ) { measurables, constraints ->
                val widths = FloatArray(measurables.size) { i ->
                    measurables[i].maxIntrinsicWidth(constraints.maxHeight).toFloat()
                }
                measures.widths = widths
                measures.gapPx = gapPx
                val placeables = measurables.mapIndexed { i, measurable ->
                    val hit = ScopeDialMath.hitWidth(widths[i], gapPx, minTouchPx).roundToInt()
                    measurable.measure(Constraints.fixed(hit, constraints.maxHeight))
                }
                val contentWidth = if (widths.isEmpty()) {
                    0
                } else {
                    (ScopeDialMath.center(widths.lastIndex, widths, gapPx) + widths.last() / 2f).roundToInt()
                }
                layout(contentWidth, constraints.maxHeight) {
                    placeables.forEachIndexed { i, placeable ->
                        val center = ScopeDialMath.center(i, widths, gapPx)
                        placeable.place((center - placeable.width / 2f).roundToInt(), 0)
                    }
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = Dimens.ScopeDialIndicatorTop)
                .size(Dimens.NavIndicatorWidth, Dimens.NavIndicatorHeight)
                .background(DarkroomColors.Paper, RoundedCornerShape(50)),
        )
    }
}

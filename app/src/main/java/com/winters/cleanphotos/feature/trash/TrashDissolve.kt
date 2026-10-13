package com.winters.cleanphotos.feature.trash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import com.winters.cleanphotos.core.design.ApiTier
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.MotionDurations
import com.winters.cleanphotos.core.design.MotionEasings
import com.winters.cleanphotos.core.design.dockListBottomPadding
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** 溶解终态：缩到 0.86、模糊 5dp（31+）、亮度 0.3、透明。 */
private const val DISSOLVE_SCALE = 0.86f
private const val DISSOLVE_BRIGHTNESS = 0.3f
private val DissolveBlur = Dimens.DissolveBlur

/**
 * 清空成功后，清空前的格子按阅读顺序依次溶解（每格 380ms、步长 60ms、加速离场曲线），
 * 「可释放」数字 900ms 滚动归零（三次减速）。全部溶解完回调 [onFinished]，空状态随后升起。
 * 只有一个时钟 Animatable，每格在 graphicsLayer 里按自己的起点读进度，溶解期间不重组格子。
 */
@Composable
internal fun TrashDissolveGrid(
    dissolve: TrashDissolve,
    header: @Composable (bytes: Long) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    tier: ApiTier = ApiTier.Current,
) {
    val gridState = rememberLazyGridState()
    val clock = remember(dissolve) { Animatable(0f) }
    val bytes = remember(dissolve) { Animatable(dissolve.totalBytes.toFloat()) }
    val finished by rememberUpdatedState(onFinished)
    LaunchedEffect(dissolve) {
        // 只排可见的格子：屏幕外的格子不参与步长，否则长列表要等很久
        val visible = gridState.layoutInfo.visibleItemsInfo.count { it.key is Long }
        val total = MotionDurations.DISSOLVE_ITEM + MotionDurations.DISSOLVE_STAGGER * (visible - 1).coerceAtLeast(0)
        coroutineScope {
            launch {
                bytes.animateTo(0f, tween(MotionDurations.DISSOLVE_COUNT_TO_ZERO, easing = MotionEasings.CountDecelerate))
            }
            clock.animateTo(total.toFloat(), tween(total, easing = LinearEasing))
        }
        finished()
    }
    val firstVisible = remember(dissolve) { gridState.firstVisibleItemIndex }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        contentPadding = PaddingValues(bottom = dockListBottomPadding()),
        horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap),
        verticalArrangement = Arrangement.spacedBy(Dimens.GridGap),
        userScrollEnabled = false,
        modifier = modifier.fillMaxSize(),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
            header(bytes.value.toLong())
        }
        itemsIndexed(dissolve.photos, key = { _, p -> p.id }, contentType = { _, _ -> "photo" }) { index, photo ->
            val order = (index + 1 - firstVisible).coerceAtLeast(0) // +1：第 0 项是 header
            val start = (order * MotionDurations.DISSOLVE_STAGGER).toFloat()
            TrashPhotoItem(
                photo = photo,
                isSelected = false,
                isSelectionMode = false,
                onPhotoClick = {},
                onToggleSelection = {},
                dissolveProgress = {
                    val p = ((clock.value - start) / MotionDurations.DISSOLVE_ITEM).coerceIn(0f, 1f)
                    MotionEasings.Accelerate.transform(p)
                },
                tier = tier,
            )
        }
    }
}

/** 溶解在单格上的表现（graphicsLayer 内读进度）。 */
internal fun Modifier.dissolveLayer(progress: () -> Float, tier: ApiTier): Modifier = graphicsLayer {
    val p = progress()
    val s = 1f + (DISSOLVE_SCALE - 1f) * p
    scaleX = s
    scaleY = s
    alpha = 1f - p
    renderEffect = if (tier.hasBlur && p > 0f) {
        val r = DissolveBlur.toPx() * p
        BlurEffect(r, r, TileMode.Decal)
    } else null
}.drawWithContent {
    drawContent()
    // brightness(b) = 叠一层透明度 (1 − b) 的黑：c·(1 − a) = c·b
    val p = progress()
    if (p > 0f) drawRect(Color.Black, alpha = (1f - DISSOLVE_BRIGHTNESS) * p)
}


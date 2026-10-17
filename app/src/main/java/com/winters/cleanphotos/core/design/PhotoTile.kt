package com.winters.cleanphotos.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * 方形缩略图（.tile）：#1c1a18 占位、居中裁切；[unseen] 时左上 6dp 画未看光点。
 * 首次从磁盘解码才按 [developProfile] 显影（决定 7），命中内存缓存直接显示。显影不拦截点击。
 */
@Composable
fun PhotoTile(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    unseen: Boolean = false,
    developProfile: DevelopProfile? = DevelopProfiles.GridFirstScreen,
    developDelayMs: Int = 0,
    colorFilter: ColorFilter? = null,
    imageModifier: Modifier = Modifier,
    /** 共享元素等需要作用在「已定好正方形尺寸」之后的修饰。放在 [modifier] 里会先于 aspectRatio，
     *  收回时共享元素按大图边界给约束、内部再被 aspectRatio 压成正方形，首帧跳成顶部一块方图。 */
    sizedModifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val develop = developProfile?.let { rememberDevelopState(it, developed = true) }
    // 只在真正显影时挂 graphicsLayer：静止态不留离屏合成层，滚动更顺
    var developing by remember { mutableStateOf(false) }
    Box(
        modifier
            .aspectRatio(1f)
            .then(sizedModifier)
            .background(DarkroomColors.TileFill)
            .then(if (onClick != null) Modifier.plainClickable(onClick = onClick) else Modifier),
    ) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            colorFilter = colorFilter,
            onSuccess = { success ->
                if (develop != null && success.result.dataSource.requiresDevelop()) {
                    // UNDISPATCHED：同一帧先挂层并把进度压到 0，避免「先亮一帧再变暗」。
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        developing = true
                        develop.develop(developDelayMs)
                        developing = false
                    }
                }
            },
            modifier = imageModifier
                .fillMaxSize()
                .then(if (developing && develop != null) Modifier.develop(develop) else Modifier),
        )
        if (unseen) {
            UnseenDot(Modifier.align(Alignment.TopStart).offset(Dimens.UnseenInset, Dimens.UnseenInset))
        }
        overlay()
    }
}

/** 等分网格（.grid4 / .grid3）：间隔 2dp，左右贴边，不足一行用空位补齐。 */
@Composable
fun <T> TileGrid(
    items: List<T>,
    columns: Int,
    modifier: Modifier = Modifier,
    tile: @Composable (index: Int, item: T, Modifier) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
        items.chunked(columns).forEachIndexed { row, rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap)) {
                rowItems.forEachIndexed { i, item -> tile(row * columns + i, item, Modifier.weight(1f)) }
                repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f).aspectRatio(1f)) }
            }
        }
    }
}

/** CSS filter: saturate(s) brightness(b) 对应的颜色矩阵（垃圾桶网格 0.7 / 0.86）。 */
fun saturateBrightness(saturation: Float, brightness: Float): ColorFilter {
    val m = ColorMatrix().apply { setToSaturation(saturation) }
    val v = m.values
    for (i in 0 until 3) for (j in 0 until 4) v[i * 5 + j] *= brightness
    return ColorFilter.colorMatrix(ColorMatrix(v))
}

/** 网格缩略图的内存缓存键；清理页大图以它作为占位（同一张照片从网格进入时不出现空帧）。 */
fun photoThumbKey(photoId: Long): String = "thumb-$photoId"

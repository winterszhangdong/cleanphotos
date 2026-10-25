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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import coil.compose.AsyncImage
import com.winters.cleanphotos.R
import com.winters.cleanphotos.core.util.DurationFormatter
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * 方形缩略图（.tile）：#1c1a18 占位、居中裁切。
 * 视频在右下角用 [DarkroomText.TileSize] 标时长，不画播放图标。
 * 有大小或时长角标时，在照片之上、文字之下铺一条底部淡渐变（[tileBadgeScrim]），没有角标的格子不画。
 * 首次从磁盘解码才按 [developProfile] 显影（决定 7），命中内存缓存直接显示。显影不拦截点击。
 * [developCached] 为 true 时命中缓存也显影，并从第一帧压暗（相似列表首次出组：出现的是列表，不是这次解码）。
 */
@Composable
fun PhotoTile(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    isVideo: Boolean = false,
    durationMs: Long? = null,
    /** 左下角文件大小。大文件平铺用，和右下角时长互不重叠。 */
    sizeLabel: String? = null,
    /** overlay 里另外画了角标（垃圾桶右下文件大小）时也铺同一条衬底。 */
    overlayBadge: Boolean = false,
    developProfile: DevelopProfile? = DevelopProfiles.GridFirstScreen,
    developDelayMs: Int = 0,
    developCached: Boolean = false,
    colorFilter: ColorFilter? = null,
    imageModifier: Modifier = Modifier,
    /** 共享元素等需要作用在「已定好正方形尺寸」之后的修饰。放在 [modifier] 里会先于 aspectRatio，
     *  收回时共享元素按大图边界给约束、内部再被 aspectRatio 压成正方形，首帧跳成顶部一块方图。 */
    sizedModifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val develop = developProfile?.let { rememberDevelopState(it, developed = !developCached) }
    // 只在真正显影时挂 graphicsLayer：静止态不留离屏合成层，滚动更顺
    var developing by remember { mutableStateOf(developCached) }
    val durationLabel = durationMs?.let { DurationFormatter.format(it) }
    val hasBadge = sizeLabel != null || durationLabel != null || overlayBadge
    val spoken = when {
        !isVideo -> contentDescription
        durationLabel != null && !contentDescription.isNullOrBlank() ->
            stringResource(R.string.cd_video_named, contentDescription, durationLabel)
        durationLabel != null -> stringResource(R.string.cd_video, durationLabel)
        !contentDescription.isNullOrBlank() -> stringResource(R.string.cd_video, contentDescription)
        else -> stringResource(R.string.cd_video_only)
    }
    val spokenWithSize = when {
        sizeLabel == null -> spoken
        spoken != null -> stringResource(R.string.cd_photo_size, spoken, sizeLabel)
        else -> sizeLabel
    }
    Box(
        modifier
            .aspectRatio(1f)
            .then(sizedModifier)
            .background(DarkroomColors.TileFill)
            .then(if (onClick != null) Modifier.plainClickable(onClick = onClick) else Modifier),
    ) {
        AsyncImage(
            model = model,
            contentDescription = spokenWithSize,
            contentScale = ContentScale.Crop,
            colorFilter = colorFilter,
            onSuccess = { success ->
                if (develop != null && (developCached || success.result.dataSource.requiresDevelop())) {
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
                .then(if (hasBadge) Modifier.tileBadgeScrim() else Modifier)
                .then(if (developing && develop != null) Modifier.develop(develop) else Modifier),
        )
        if (sizeLabel != null) {
            Text(
                sizeLabel,
                style = DarkroomText.TileSize,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .clearAndSetSemantics { }
                    .padding(start = Dimens.TileLabelInset, bottom = Dimens.TileLabelInset),
            )
        }
        if (durationLabel != null) {
            Text(
                durationLabel,
                style = DarkroomText.TileSize,
                // 时长已写进格子的 contentDescription，避免读屏再念一遍角标
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .clearAndSetSemantics { }
                    .padding(end = Dimens.TileLabelInset, bottom = Dimens.TileLabelInset),
            )
        }
        overlay()
    }
}

/**
 * 角标那一行的底部衬：透明 → [DarkroomColors.TileBadgeScrim]，高 [Dimens.TileBadgeScrimHeight]。
 * 画在照片上面。调用方把文字排在这层之后，字才压在衬底上。尺寸变化才重建笔刷，不触发重组。
 */
fun Modifier.tileBadgeScrim(): Modifier = drawWithCache {
    val band = Dimens.TileBadgeScrimHeight.toPx().coerceAtMost(size.height)
    val top = size.height - band
    val brush = if (band > 0f) {
        Brush.verticalGradient(
            colors = listOf(Color.Transparent, DarkroomColors.TileBadgeScrim),
            startY = top,
            endY = size.height,
        )
    } else {
        null
    }
    onDrawWithContent {
        drawContent()
        val fade = brush ?: return@onDrawWithContent
        drawRect(fade, topLeft = Offset(0f, top), size = Size(size.width, band))
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

package com.winters.cleanphotos.ui.photopager

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.winters.cleanphotos.domain.model.PhotoItem

/** 统一深色背景 */
private val PhotoPagerBackgroundColor = Color.Black

/**
 * 通用横向 Pager 组件。
 *
 * 平面轮播风格：
 * - 全局背景只在根 Box 绘制一次
 * - 每个 page wrapper 负责横向位移、pageScale、pageAlpha、parallax
 * - PhotoPagerPhoto 只负责图片内容渲染、zoom 平移和错误态
 * - 拖动中当前页和进入页同时可见
 *
 * 本组件是纯渲染组件，不包含手势检测。
 *
 * @param photos 完整照片列表
 * @param currentIndex 当前显示的照片索引
 * @param offsetX 当前水平偏移量（像素）
 * @param offsetY 当前垂直偏移量（像素），只有当前页使用
 * @param scale 当前缩放比例（垂直滑动缩小），只有当前页使用
 * @param zoomScale zoom 缩放倍率，只有当前页使用
 * @param zoomOffsetX zoom 平移 X，只有当前页使用
 * @param zoomOffsetY zoom 平移 Y，只有当前页使用
 * @param onImageLoadFailed 图片加载失败回调
 */
@Composable
fun PhotoPager(
    photos: List<PhotoItem>,
    currentIndex: Int,
    offsetX: Int,
    offsetY: Int,
    scale: Float,
    zoomScale: Float = 1f,
    zoomOffsetX: Float = 0f,
    zoomOffsetY: Float = 0f,
    onImageLoadFailed: () -> Unit = {},
    backgroundPhotoOverride: PhotoItem? = null,
    contentScale: ContentScale = ContentScale.Fit,
    colorFilter: androidx.compose.ui.graphics.ColorFilter? = null,
    backgroundColor: Color = PhotoPagerBackgroundColor,
    videoHostId: Long? = null,
    videoOverlay: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenSizePx = remember(
        configuration.screenWidthDp,
        configuration.screenHeightDp,
        density
    ) {
        with(density) {
            IntSize(
                width = configuration.screenWidthDp.dp.roundToPx(),
                height = configuration.screenHeightDp.dp.roundToPx()
            )
        }
    }

    // 合成当前页的最终缩放：外部垂直缩小 * zoom 缩放
    val currentPageScale = scale * zoomScale

    val pages = remember(photos, currentIndex, offsetX, offsetY, currentPageScale, zoomOffsetX, zoomOffsetY, screenSizePx, backgroundPhotoOverride) {
        buildPhotoPagerPages(
            photos = photos,
            currentIndex = currentIndex,
            offsetX = offsetX,
            offsetY = offsetY,
            scale = currentPageScale,
            zoomOffsetX = zoomOffsetX,
            zoomOffsetY = zoomOffsetY,
            screenSizePx = screenSizePx,
            backgroundPhotoOverride = backgroundPhotoOverride
        )
    }

    // 根 Box：全局背景只绘制一次
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        pages.forEach { page ->
            key(page.photo.id) {
                val failureCallback: () -> Unit = if (page.reportLoadFailure) {
                    onImageLoadFailed
                } else {
                    { }
                }
                // page wrapper：负责横向位移、pageScale、pageAlpha、parallax、zIndex
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(page.zIndex)
                        .graphicsLayer {
                            translationX = page.horizontalOffsetPx.toFloat() + page.pageParallaxOffsetPx.toFloat()
                            scaleX = page.pageScale
                            scaleY = page.pageScale
                            alpha = page.pageAlpha
                        }
                        .clipToBounds()
                ) {
                    // 图片内容：只负责渲染、zoom 平移、错误态
                    PhotoPagerPhoto(
                        photo = page.photo,
                        verticalOffsetPx = page.verticalOffsetPx,
                        scale = page.scale,
                        zoomOffsetX = page.zoomOffsetX,
                        zoomOffsetY = page.zoomOffsetY,
                        contentScale = contentScale,
                        colorFilter = colorFilter,
                        onImageLoadFailed = failureCallback,
                        videoOverlay = if (page.photo.id == videoHostId && page.role != PhotoPagerRole.Background) videoOverlay else null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

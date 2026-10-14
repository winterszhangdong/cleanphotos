package com.winters.cleanphotos.ui.photopager

import com.winters.cleanphotos.R
import androidx.compose.ui.res.stringResource
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomText
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.winters.cleanphotos.domain.model.PhotoItem

/**
 * 纯图片内容组件。
 *
 * 只负责：
 * - 图片加载和显示（AsyncImage + ContentScale.Fit）
 * - zoom 平移和缩放（zoomOffsetX/Y + scale）
 * - 垂直手势偏移（verticalOffsetPx）
 * - 加载失败 UI
 *
 * 不负责：
 * - 页面横向位移（由 PhotoPager 的 page wrapper 处理）
 * - 页面级 pageScale/pageAlpha（由 PhotoPager 的 page wrapper 处理）
 * - 单页全屏背景遮罩（全局背景在 PhotoPager 根节点）
 *
 * @param photo 照片数据
 * @param verticalOffsetPx 垂直偏移量（上下滑手势）
 * @param scale 缩放比例（垂直缩小 * zoom 缩放合成值）
 * @param zoomOffsetX zoom 平移 X
 * @param zoomOffsetY zoom 平移 Y
 * @param onImageLoadFailed 图片加载失败回调
 */
@Composable
fun PhotoPagerPhoto(
    photo: PhotoItem?,
    verticalOffsetPx: Int = 0,
    scale: Float = 1f,
    zoomOffsetX: Float = 0f,
    zoomOffsetY: Float = 0f,
    contentScale: ContentScale = ContentScale.Fit,
    colorFilter: androidx.compose.ui.graphics.ColorFilter? = null,
    onImageLoadFailed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var hasImageLoadError by remember(photo?.id) { mutableStateOf(false) }
    val imageRequestKey = remember(photo?.id, photo?.uri) {
        buildPhotoPagerImageRequestKey(photo)
    }
    val imageRequest = remember(context, imageRequestKey) {
        imageRequestKey?.let { key ->
            ImageRequest.Builder(context)
                .data(key.photoUri)
                // 交互期间会高频重组，显式固定缓存键，避免同图请求在不同页角色间漂移
                .memoryCacheKey(key.cacheKey)
                .diskCacheKey(key.photoUri.toString())
                // 清理页有频繁的缩放与横滑变换，禁用 crossfade 避免交互期间闪烁
                .crossfade(false)
                .build()
        }
    }

    // 切换照片时重置错误状态
    val photoId = photo?.id
    LaunchedEffect(photoId) {
        hasImageLoadError = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .graphicsLayer {
                // 垂直手势偏移 + zoom 平移（在缩放坐标系内生效）
                translationY = verticalOffsetPx.toFloat()
                // 缩放（外部垂直缩小 * zoom 缩放的合成值）
                scaleX = scale
                scaleY = scale
            }
            .graphicsLayer {
                // zoom 平移（在缩放后的坐标系内生效）
                translationX = zoomOffsetX
                translationY = zoomOffsetY
            },
        contentAlignment = Alignment.Center
    ) {
        if (photo != null && imageRequest != null) {
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                colorFilter = colorFilter,
                onSuccess = {
                    hasImageLoadError = false
                },
                onError = {
                    if (!hasImageLoadError) {
                        hasImageLoadError = true
                        onImageLoadFailed()
                    }
                }
            )

            if (hasImageLoadError) {
                ImageLoadFailedContent()
            }
        }
    }
}

/**
 * Coil 请求稳定键。
 *
 * `PhotoPager` 会在同一张照片的不同页面角色之间复用同一个 Compose 节点，
 * 因此这里用 `photoId + uri` 固定请求身份，避免手势重组期间频繁重建请求。
 */
internal data class PhotoPagerImageRequestKey(
    val photoId: Long,
    val photoUri: Uri
) {
    val cacheKey: String = "photo-pager-$photoId-$photoUri"
}

internal fun buildPhotoPagerImageRequestKey(
    photo: PhotoItem?
): PhotoPagerImageRequestKey? {
    if (photo == null) return null
    return PhotoPagerImageRequestKey(
        photoId = photo.id,
        photoUri = photo.uri
    )
}

@Composable
private fun ImageLoadFailedContent() {
    Text(text = stringResource(R.string.photo_load_failed), style = DarkroomText.Body.copy(color = DarkroomColors.Dim))
}

package com.winters.cleanphotos.feature.review

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import android.graphics.drawable.BitmapDrawable
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import com.winters.cleanphotos.core.design.ApiTier
import com.winters.cleanphotos.domain.model.PhotoItem
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 环境光取样边长。全屏 36dp 模糊折算到这张小图上只是几个像素，做一次就够。 */
internal const val AMBIENT_SOURCE_PX = 96
internal const val AMBIENT_SCALE = 1.2f
internal const val AMBIENT_BRIGHTNESS = 0.4f
internal const val AMBIENT_SATURATION = 1.3f
internal val AMBIENT_BLUR = 36.dp

/**
 * 把屏幕上的模糊半径折回小图。
 * 小图按 Crop 铺满屏幕时的缩放是 `screenMax / source`，源图像素半径 = 屏幕模糊像素 / 这个缩放。
 */
internal fun ambientSourceRadius(screenBlurPx: Float, sourcePx: Int, screenMaxPx: Int): Int {
    if (screenBlurPx <= 0f || screenMaxPx <= 0 || sourcePx <= 0) return 1
    return (screenBlurPx * sourcePx / screenMaxPx).roundToInt().coerceAtLeast(1)
}

/** 宽高未知，或按 Fit 有一边没铺满：需要环境光。 */
internal fun photoNeedsAmbient(photo: PhotoItem, screen: IntSize): Boolean {
    if (screen.width <= 1 || screen.height <= 1) return false
    if (photo.width == null || photo.height == null) return true
    val fit = ReviewGestureMath.fitSize(photo.width, photo.height, screen.width.toFloat(), screen.height.toFloat())
    return fit.width < screen.width - 1f || fit.height < screen.height - 1f
}

/**
 * 三次盒式模糊，边缘夹紧。在源图像素上做，不碰每帧的全屏 RenderEffect。
 */
internal fun boxBlur3(pixels: IntArray, w: Int, h: Int, radius: Int) {
    val r = radius.coerceAtLeast(1)
    val tmp = IntArray(pixels.size)
    repeat(3) {
        boxBlurHorizontal(pixels, tmp, w, h, r)
        boxBlurVertical(tmp, pixels, w, h, r)
    }
}

private fun boxBlurHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            var a = 0
            var red = 0
            var g = 0
            var b = 0
            var n = 0
            val x0 = (x - r).coerceAtLeast(0)
            val x1 = (x + r).coerceAtMost(w - 1)
            for (k in x0..x1) {
                val p = src[row + k]
                a += p ushr 24
                red += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
                n++
            }
            dst[row + x] = ((a / n) shl 24) or ((red / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
    }
}

private fun boxBlurVertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
    for (x in 0 until w) {
        for (y in 0 until h) {
            var a = 0
            var red = 0
            var g = 0
            var b = 0
            var n = 0
            val y0 = (y - r).coerceAtLeast(0)
            val y1 = (y + r).coerceAtMost(h - 1)
            for (k in y0..y1) {
                val p = src[k * w + x]
                a += p ushr 24
                red += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
                n++
            }
            dst[y * w + x] = ((a / n) shl 24) or ((red / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
    }
}

internal fun blurBitmap(src: Bitmap, radius: Int): Bitmap {
    val readable = if (src.config == Bitmap.Config.HARDWARE) {
        src.copy(Bitmap.Config.ARGB_8888, false) ?: error("环境光硬件位图无法拷成软件位图")
    } else {
        src
    }
    val square = centerCropSquare(readable, AMBIENT_SOURCE_PX)
    val w = square.width
    val h = square.height
    val pixels = IntArray(w * h)
    square.getPixels(pixels, 0, w, 0, 0, w, h)
    boxBlur3(pixels, w, h, radius)
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
        it.setPixels(pixels, 0, w, 0, 0, w, h)
    }
}

/** 居中裁成正方形软件位图。硬件位图画进来即可，后面才能读像素。 */
private fun centerCropSquare(src: Bitmap, size: Int): Bitmap {
    require(src.width > 0 && src.height > 0) { "环境光取样位图是空的" }
    val scale = max(size.toFloat() / src.width, size.toFloat() / src.height)
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.translate((size - src.width * scale) / 2f, (size - src.height * scale) / 2f)
    canvas.scale(scale, scale)
    canvas.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
    return out
}

private object ReviewAmbientCache {
    private val cache = android.util.LruCache<String, ImageBitmap>(32)
    private fun key(id: Long, w: Int, h: Int) = "$id@$w×$h"
    fun get(id: Long, w: Int, h: Int): ImageBitmap? = cache.get(key(id, w, h))
    fun put(id: Long, w: Int, h: Int, image: ImageBitmap) {
        cache.put(key(id, w, h), image)
    }
}

private fun ambientRequest(context: Context, photo: PhotoItem) = ImageRequest.Builder(context)
    .data(photo.uri)
    .size(AMBIENT_SOURCE_PX)
    .precision(Precision.INEXACT)
    .bitmapConfig(Bitmap.Config.RGB_565)
    .allowHardware(false)
    .memoryCacheKey("review-amb-${photo.id}")
    .crossfade(false)
    .build()

/** 解码 96px 并模糊一次。已经缓存过的直接返回。失败（文件不在）返回 null，页面露出暗房底色。 */
internal suspend fun loadReviewAmbient(
    context: Context,
    photo: PhotoItem,
    screen: IntSize,
    screenBlurPx: Float,
): ImageBitmap? {
    if (!ApiTier.Current.hasBlur || !photoNeedsAmbient(photo, screen)) return null
    ReviewAmbientCache.get(photo.id, screen.width, screen.height)?.let { return it }
    val radius = ambientSourceRadius(screenBlurPx, AMBIENT_SOURCE_PX, maxOf(screen.width, screen.height))
    val result = coil.Coil.imageLoader(context).execute(ambientRequest(context, photo))
    if (result !is SuccessResult) return null
    val drawable = result.drawable
    check(drawable is BitmapDrawable) { "环境光解码结果不是位图：${drawable.javaClass.name}" }
    val blurred = withContext(Dispatchers.Default) { blurBitmap(drawable.bitmap, radius).asImageBitmap() }
    ReviewAmbientCache.put(photo.id, screen.width, screen.height, blurred)
    return blurred
}

/**
 * 当前页和邻页都取同一张已模糊的小图。邻页在屏外时就开始做，滑进来时环境光已经在。
 */
@Composable
internal fun rememberReviewAmbient(photo: PhotoItem, screen: IntSize, enabled: Boolean): ImageBitmap? {
    val context = LocalContext.current
    val blurPx = with(LocalDensity.current) { AMBIENT_BLUR.toPx() }
    var bitmap by remember(photo.id, screen.width, screen.height) {
        mutableStateOf(ReviewAmbientCache.get(photo.id, screen.width, screen.height))
    }
    LaunchedEffect(photo.id, photo.uri, screen.width, screen.height, enabled) {
        if (!enabled) return@LaunchedEffect
        bitmap = loadReviewAmbient(context, photo, screen, blurPx)
    }
    return if (enabled) bitmap else null
}

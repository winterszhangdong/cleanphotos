package com.winters.cleanphotos.data.datasource

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.Sharpness
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.CancellationException

/**
 * 约 512px 长边的灰度图上算拉普拉斯方差。
 * Android 10+ 用 [android.content.ContentResolver.loadThumbnail]，8–9 用 inSampleSize。
 * 结果只缓存在进程内存。单张失败返回 null。
 */
@Singleton
class PhotoSharpnessDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // 同一 id 的体积或宽高变了就要重算，不能拿改图前的清晰度当推荐。
    private val cache = ConcurrentHashMap<Long, Stamp>()

    fun sharpness(photo: PhotoItem): Double? {
        val size = photo.size ?: Long.MIN_VALUE
        val width = photo.width ?: Int.MIN_VALUE
        val height = photo.height ?: Int.MIN_VALUE
        cache[photo.id]?.let { hit ->
            if (hit.size == size && hit.width == width && hit.height == height) return hit.value
        }
        val score = try {
            val decoded = load(photo.uri) ?: return null
            try {
                score(decoded)
            } finally {
                decoded.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "清晰度失败 id=${photo.id}", e)
            null
        } ?: return null
        cache[photo.id] = Stamp(size, width, height, score)
        return score
    }

    private fun load(uri: Uri): Bitmap? = loadPhotoBitmap(context, uri, EDGE)

    private fun score(bitmap: Bitmap): Double? {
        val scaled = scaleToEdge(bitmap)
        try {
            if (scaled.width < 3 || scaled.height < 3) return null
            val pixels = IntArray(scaled.width * scaled.height)
            scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            val luma = IntArray(pixels.size) { i ->
                val c = pixels[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                (r * 77 + g * 150 + b * 29) shr 8
            }
            return Sharpness.laplacianVariance(luma, scaled.width, scaled.height)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun scaleToEdge(bitmap: Bitmap): Bitmap {
        val longEdge = max(bitmap.width, bitmap.height)
        if (longEdge <= EDGE || longEdge <= 0) return bitmap
        val scale = EDGE.toFloat() / longEdge
        val w = max(1, (bitmap.width * scale).toInt())
        val h = max(1, (bitmap.height * scale).toInt())
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    private class Stamp(val size: Long, val width: Int, val height: Int, val value: Double)

    private companion object {
        const val TAG = "PhotoSharpness"
        const val EDGE = 512
    }
}

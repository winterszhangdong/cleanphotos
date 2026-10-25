package com.winters.cleanphotos.data.datasource

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.winters.cleanphotos.domain.model.DHash
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.SimilarRules
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * 64 位 dHash。Android 10+ 用 [android.content.ContentResolver.loadThumbnail] 拿小图，
 * 8–9 用 inSampleSize 解一张小图，再缩成 9×8 灰度。
 */
@Singleton
class PhotoHashDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // shortcut: hash 只存进程内存，进程重启要重算；相册大到首次比对明显慢时改成持久化缓存。
    // 键带上体积和宽高：同一 MediaStore id 被外部改过（裁剪、旋转）后不能继续用旧哈希分组。
    private val cache = ConcurrentHashMap<Long, Stamp>()

    /** 取图或解码失败返回 null，调用方跳过这张。 */
    fun dHash(photo: PhotoItem): Long? {
        val size = photo.size ?: Long.MIN_VALUE
        val width = photo.width ?: Int.MIN_VALUE
        val height = photo.height ?: Int.MIN_VALUE
        cache[photo.id]?.let { hit ->
            if (hit.size == size && hit.width == width && hit.height == height) return hit.value
        }
        val hash = try {
            val decoded = loadSmall(photo.uri) ?: return null
            try {
                hashBitmap(decoded)
            } finally {
                decoded.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "dHash 失败 id=${photo.id}", e)
            null
        } ?: return null
        cache[photo.id] = Stamp(size, width, height, hash)
        return hash
    }

    private fun loadSmall(uri: Uri): Bitmap? = loadPhotoBitmap(context, uri, DECODE_EDGE)

    private fun hashBitmap(bitmap: Bitmap): Long {
        val scaled = if (bitmap.width == SimilarRules.DHASH_WIDTH && bitmap.height == SimilarRules.DHASH_HEIGHT) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, SimilarRules.DHASH_WIDTH, SimilarRules.DHASH_HEIGHT, true)
        }
        try {
            val pixels = IntArray(SimilarRules.DHASH_LENGTH)
            scaled.getPixels(pixels, 0, SimilarRules.DHASH_WIDTH, 0, 0, SimilarRules.DHASH_WIDTH, SimilarRules.DHASH_HEIGHT)
            val luma = IntArray(pixels.size) { i ->
                val c = pixels[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // 整数近似 0.299 / 0.587 / 0.114
                (r * 77 + g * 150 + b * 29) shr 8
            }
            return DHash.fromLuminance(luma)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private class Stamp(val size: Long, val width: Int, val height: Int, val value: Long)

    private companion object {
        const val TAG = "PhotoHash"
        /** 比 9×8 略大，缩小后比较稳，又不必解全图。 */
        const val DECODE_EDGE = 96
    }
}

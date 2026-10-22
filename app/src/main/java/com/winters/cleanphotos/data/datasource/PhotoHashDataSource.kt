package com.winters.cleanphotos.data.datasource

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Size
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
    // shortcut: hash 只存进程内存，进程重启要重算；相册大到首次比对明显慢时改成持久化缓存
    private val cache = ConcurrentHashMap<Long, Long>()

    /** 取图或解码失败返回 null，调用方跳过这张。 */
    fun dHash(photo: PhotoItem): Long? {
        cache[photo.id]?.let { return it }
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
        cache[photo.id] = hash
        return hash
    }

    private fun loadSmall(uri: Uri): Bitmap? {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(DECODE_EDGE, DECODE_EDGE), null)
        } else {
            decodeSampled(uri)
        } ?: return null
        if (raw.config != Bitmap.Config.HARDWARE) return raw
        val copy = raw.copy(Bitmap.Config.ARGB_8888, false)
        raw.recycle()
        return copy
    }

    private fun decodeSampled(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

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

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= DECODE_EDGE && h / 2 >= DECODE_EDGE) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    private companion object {
        const val TAG = "PhotoHash"
        /** 比 9×8 略大，缩小后比较稳，又不必解全图。 */
        const val DECODE_EDGE = 96
    }
}

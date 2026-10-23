package com.winters.cleanphotos.data.datasource

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Size

/**
 * 小图解码。Android 10+ 用 [android.content.ContentResolver.loadThumbnail]，
 * 8–9 用 inSampleSize。HARDWARE 位图复制成 ARGB_8888，方便后面读像素。
 * 取图或解码失败返回 null。
 */
internal fun loadPhotoBitmap(context: Context, uri: Uri, edge: Int): Bitmap? {
    val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.contentResolver.loadThumbnail(uri, Size(edge, edge), null)
    } else {
        decodeSampled(context, uri, edge)
    } ?: return null
    if (raw.config != Bitmap.Config.HARDWARE) return raw
    val copy = raw.copy(Bitmap.Config.ARGB_8888, false)
    raw.recycle()
    return copy
}

private fun decodeSampled(context: Context, uri: Uri, edge: Int): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, edge)
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
}

private fun sampleSize(width: Int, height: Int, edge: Int): Int {
    var sample = 1
    var w = width
    var h = height
    while (w / 2 >= edge && h / 2 >= edge) {
        w /= 2
        h /= 2
        sample *= 2
    }
    return sample
}

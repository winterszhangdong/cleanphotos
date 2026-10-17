package com.winters.cleanphotos.core.image

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import coil.size.pxOrElse
import kotlinx.coroutines.suspendCancellableCoroutine
import okio.buffer
import okio.source
import kotlin.coroutines.resume

/** 网格缩略图的请求模型：只要格子大小的图，不必解码原图。 */
data class GridThumb(val uri: Uri)

/**
 * [GridThumb] 的取图器。
 * - Android 10+：ContentResolver.loadThumbnail，走系统缓存的缩略图，比读整张原图再缩小快得多。
 * - Android 8–9 或取缩略图失败：退回原来的做法，打开原图交给 Coil 按格子大小采样解码。
 * 结果都标为 DISK，首屏显影的判断不变。
 */
class GridThumbFetcher(
    private val data: GridThumb,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val context = options.context
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val w = options.size.width.pxOrElse { DEFAULT_PX }
            val h = options.size.height.pxOrElse { DEFAULT_PX }
            val bitmap = runCatching { loadThumbnail(context, w, h) }.getOrNull()
            if (bitmap != null) {
                // isSampled = false：loadThumbnail 按比例缩进 w×h，非方形照片的短边会小于格子（如 270×202）。
                // 标成「已采样」时 Coil 回到网格判定缓存图比请求尺寸小、内存缓存失效而重新取图（灰色显影重播）。
                // 网格每次请求同一格子尺寸，这张就是网格要的最终图，按原图对待即可。
                return DrawableResult(BitmapDrawable(context.resources, bitmap), isSampled = false, dataSource = DataSource.DISK)
            }
        }
        val resolver = context.contentResolver
        val stream = checkNotNull(resolver.openInputStream(data.uri)) { "Unable to open ${data.uri}" }
        return SourceResult(
            source = ImageSource(stream.source().buffer(), context),
            mimeType = resolver.getType(data.uri),
            dataSource = DataSource.DISK,
        )
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun loadThumbnail(context: Context, w: Int, h: Int) = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        cont.resume(context.contentResolver.loadThumbnail(data.uri, android.util.Size(w, h), signal))
    }

    class Factory : Fetcher.Factory<GridThumb> {
        override fun create(data: GridThumb, options: Options, imageLoader: ImageLoader): Fetcher = GridThumbFetcher(data, options)
    }

    private companion object {
        const val DEFAULT_PX = 256
    }
}

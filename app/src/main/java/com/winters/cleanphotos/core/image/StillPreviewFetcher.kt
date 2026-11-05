package com.winters.cleanphotos.core.image

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.OperationCanceledException
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import coil.size.pxOrElse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okio.buffer
import okio.source
import kotlin.coroutines.resume

/**
 * RAW / PNG 在一倍时要的图：只要系统缩略图，不解码原图。
 * 放大用的更清楚图不走这里，仍由 Coil 按原图采样。
 */
data class StillPreview(val uri: Uri)

/**
 * [StillPreview] 的取图器。取消规则与 [GridThumbFetcher] 相同：请求取消不能再去读整张原图。
 * - Android 10+：`ContentResolver.loadThumbnail`。系统会优先用内嵌预览，并缓存结果。
 * - Android 8–9，或取缩略图失败：打开原文件交给 Coil 采样。这是最后退路，调用方要关掉磁盘缓存，避免把 RAW 拷进缓存。
 */
class StillPreviewFetcher(
    private val data: StillPreview,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val context = options.context
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val w = options.size.width.pxOrElse { DEFAULT_PX }
            val h = options.size.height.pxOrElse { DEFAULT_PX }
            val bitmap = try {
                loadThumbnail(context, w, h)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OperationCanceledException) {
                currentCoroutineContext().ensureActive()
                null
            } catch (e: Exception) {
                null
            }
            if (bitmap != null) {
                // isSampled = false：和网格一样，这张就是这次要的最终图，不能让 Coil 因短边更小而当成缓存失效。
                return DrawableResult(
                    BitmapDrawable(context.resources, bitmap),
                    isSampled = false,
                    dataSource = DataSource.DISK,
                )
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

    class Factory : Fetcher.Factory<StillPreview> {
        override fun create(data: StillPreview, options: Options, imageLoader: ImageLoader): Fetcher =
            StillPreviewFetcher(data, options)
    }

    private companion object {
        const val DEFAULT_PX = 256
    }
}

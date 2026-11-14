package com.winters.cleanphotos.core.image

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.exifinterface.media.ExifInterface
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
import okio.Buffer
import kotlin.coroutines.resume

/**
 * RAW 在一倍时要的图：文件里相机塞好的预览，不去马赛克。
 * 没有预览时退回系统缩略图。仍没有就失败，调用方再给当前这一张解原图。
 * 这里不打开原文件交给 Coil，否则左右预取会把整张 RAW 解出来。
 */
data class RawEmbeddedPreview(val uri: Uri)

class RawEmbeddedFetcher(
    private val data: RawEmbeddedPreview,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val context = options.context
        currentCoroutineContext().ensureActive()
        val bytes = try {
            embeddedPreview(context, data.uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        currentCoroutineContext().ensureActive()
        if (bytes != null && bytes.isNotEmpty()) {
            return SourceResult(
                source = ImageSource(Buffer().write(bytes), context),
                mimeType = "image/jpeg",
                dataSource = DataSource.DISK,
            )
        }
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
                return DrawableResult(
                    BitmapDrawable(context.resources, bitmap),
                    isSampled = false,
                    dataSource = DataSource.DISK,
                )
            }
        }
        error("No glance image for ${data.uri}")
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun loadThumbnail(context: Context, w: Int, h: Int) = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        cont.resume(context.contentResolver.loadThumbnail(data.uri, android.util.Size(w, h), signal))
    }

    class Factory : Fetcher.Factory<RawEmbeddedPreview> {
        override fun create(data: RawEmbeddedPreview, options: Options, imageLoader: ImageLoader): Fetcher =
            RawEmbeddedFetcher(data, options)
    }

    private companion object {
        const val DEFAULT_PX = 256

        fun embeddedPreview(context: Context, uri: Uri): ByteArray? {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            pfd.use {
                val exif = ExifInterface(it.fileDescriptor)
                if (!exif.hasThumbnail()) return null
                return exif.thumbnailBytes
            }
        }
    }
}

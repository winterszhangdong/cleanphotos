package com.winters.cleanphotos.data.datasource

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.winters.cleanphotos.core.design.ApiTier
import com.winters.cleanphotos.domain.model.CompressVideoPlan
import com.winters.cleanphotos.domain.model.MediaType
import com.winters.cleanphotos.domain.model.PhotoItem
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

internal sealed interface VideoProbe {
    data class Ok(
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val playbackFps: Double?,
        val fileSizeBytes: Long,
        val durationMs: Long,
        val hdr: Boolean,
        val samsungSef: Boolean,
    ) : VideoProbe

    data object Undecodable : VideoProbe
}

/**
 * 开压时读轨道，成品先落缓存，再以 IS_PENDING 写入原来的文件夹。
 * 不往网格的照片查询里加格式字段。
 */
@Singleton
class VideoCompressStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val held = ConcurrentHashMap<String, Held>()

    fun availableBytes(): Long = android.os.StatFs(context.cacheDir.absolutePath).availableBytes

    internal fun probe(photo: PhotoItem): VideoProbe {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, photo.uri, null)
            var video: MediaFormat? = null
            var videoMime: String? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") || video != null) continue
                if (mime in BLOCKED_MIME) return VideoProbe.Undecodable
                video = format
                videoMime = mime
            }
            val format = video ?: return VideoProbe.Undecodable
            val width = format.integer(MediaFormat.KEY_WIDTH) ?: return VideoProbe.Undecodable
            val height = format.integer(MediaFormat.KEY_HEIGHT) ?: return VideoProbe.Undecodable
            if (width < 2 || height < 2) return VideoProbe.Undecodable
            val duration = photo.durationMs?.takeIf { it > 0L }
                ?: format.long(MediaFormat.KEY_DURATION)?.div(1000L)?.takeIf { it > 0L }
                ?: 0L
            val size = photo.size?.takeIf { it > 0L } ?: statSize(photo.uri) ?: 0L
            return VideoProbe.Ok(
                width = width,
                height = height,
                rotationDegrees = rotationDegrees(format, photo.uri),
                playbackFps = frameRate(format),
                fileSizeBytes = size,
                durationMs = duration,
                hdr = isHdr(format, videoMime),
                samsungSef = hasSamsungSef(photo.uri),
            )
        } finally {
            extractor.release()
        }
    }

    fun hold(file: File, source: PhotoItem, plan: CompressVideoPlan.Plan, durationMs: Long) {
        held[file.absolutePath] = Held(file, source, plan, durationMs)
    }

    fun publish(token: String): PhotoItem {
        val heldItem = held[token] ?: error("压缩成品不在缓存里")
        val created = insert(heldItem)
        held.remove(token)
        heldItem.file.delete()
        return created
    }

    fun discard(token: String) {
        val heldItem = held.remove(token)
        val file = heldItem?.file ?: File(token)
        val cache = context.cacheDir.canonicalFile
        val target = file.canonicalFile
        if (target.path.startsWith(cache.path)) target.delete()
    }

    /** 成品已经入库，但收藏或移入垃圾桶没写成。删掉刚插入的这一条，原片不动。 */
    fun removePublished(photo: PhotoItem) {
        context.contentResolver.delete(photo.uri, null, null)
    }

    /**
     * 轨道里的宽高是旋转之前的编码尺寸。KEY_ROTATION 经常是 0，旋转写在容器矩阵里，
     * 要用 MediaMetadataRetriever 再读一次，否则竖屏 4K 会按横屏去缩。
     */
    private fun rotationDegrees(format: MediaFormat, uri: Uri): Int {
        val fromTrack = format.integer(MediaFormat.KEY_ROTATION) ?: 0
        if (fromTrack != 0) return fromTrack
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
                ?: 0
        } finally {
            retriever.release()
        }
    }

    private fun insert(heldItem: Held): PhotoItem {
        val source = heldItem.source
        val plan = heldItem.plan
        val meta = readMeta(source.uri)
        val displayName = CompressVideoPlan.outputDisplayName(source.displayName)
        val pending = ApiTier.Current.supportsMediaStorePending
        val resolver = context.contentResolver
        var uri: Uri? = null
        var legacyFile: File? = null
        var committed = false
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DURATION, heldItem.durationMs)
                put(MediaStore.Video.Media.WIDTH, plan.outputWidth)
                put(MediaStore.Video.Media.HEIGHT, plan.outputHeight)
                put(MediaStore.Video.Media.SIZE, heldItem.file.length())
                source.dateTaken?.let { put(DATE_TAKEN, it) }
                if (meta.latitude != null && meta.longitude != null) {
                    put(MediaStore.Video.Media.LATITUDE, meta.latitude)
                    put(MediaStore.Video.Media.LONGITUDE, meta.longitude)
                }
                if (pending) {
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        normalizeRelativePath(source.relativePath)
                            ?: meta.relativePath
                            ?: "${Environment.DIRECTORY_MOVIES}/",
                    )
                }
            }
            val collection = if (pending) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            if (!pending) {
                val dir = meta.dataPath?.let { File(it).parentFile }
                    ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                dir.mkdirs()
                legacyFile = uniqueFile(dir, displayName)
                heldItem.file.copyTo(legacyFile, overwrite = false)
                values.put(MediaStore.MediaColumns.DATA, legacyFile.absolutePath)
                values.put(MediaStore.Video.Media.DISPLAY_NAME, legacyFile.name)
            }
            uri = resolver.insert(collection, values) ?: error("图库没有收下压缩视频")
            if (pending) {
                resolver.openOutputStream(uri)?.use { out ->
                    heldItem.file.inputStream().use { it.copyTo(out) }
                } ?: error("写不出压缩视频")
                val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                if (resolver.update(uri, done, null, null) != 1) error("没能完成压缩视频")
            }
            committed = true
            val id = ContentUris.parseId(uri)
            val storedName = queryName(uri) ?: legacyFile?.name ?: displayName
            return PhotoItem(
                id = id,
                uri = uri,
                displayName = storedName,
                dateTaken = source.dateTaken,
                dateGroup = source.dateGroup,
                size = heldItem.file.length(),
                width = plan.outputWidth,
                height = plan.outputHeight,
                isFavorite = false,
                mediaType = MediaType.Video,
                durationMs = heldItem.durationMs,
                relativePath = if (pending) {
                    normalizeRelativePath(source.relativePath) ?: meta.relativePath
                } else {
                    source.relativePath
                },
                bucketName = source.bucketName,
            )
        } finally {
            if (!committed) {
                uri?.let { resolver.delete(it, null, null) }
                legacyFile?.delete()
            }
        }
    }

    private fun queryName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getString(0)
        }

    private data class SourceMeta(
        val latitude: Double?,
        val longitude: Double?,
        val dataPath: String?,
        val relativePath: String?,
    )

    private fun readMeta(uri: Uri): SourceMeta {
        val pending = ApiTier.Current.supportsMediaStorePending
        val columns = buildList {
            add(MediaStore.Video.Media.LATITUDE)
            add(MediaStore.Video.Media.LONGITUDE)
            if (pending) add(MediaStore.Video.Media.RELATIVE_PATH) else add(MediaStore.MediaColumns.DATA)
        }.toTypedArray()
        val cursor = context.contentResolver.query(uri, columns, null, null, null)
            ?: return SourceMeta(null, null, null, null)
        cursor.use {
            if (!it.moveToFirst()) return SourceMeta(null, null, null, null)
            fun number(name: String): Double? {
                val index = it.getColumnIndex(name)
                if (index < 0 || it.isNull(index)) return null
                return it.getDouble(index)
            }
            val data = if (!pending) it.string(MediaStore.MediaColumns.DATA) else null
            val relative = if (pending) normalizeRelativePath(it.string(MediaStore.Video.Media.RELATIVE_PATH)) else null
            return SourceMeta(number(MediaStore.Video.Media.LATITUDE), number(MediaStore.Video.Media.LONGITUDE), data, relative)
        }
    }

    private fun android.database.Cursor.string(name: String): String? {
        val index = getColumnIndex(name)
        if (index < 0 || isNull(index)) return null
        return getString(index)
    }

    private fun statSize(uri: Uri): Long? =
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            pfd.statSize.takeIf { it > 0L }
        }

    private fun hasSamsungSef(uri: Uri): Boolean {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return false
        pfd.use {
            val length = it.statSize
            if (length <= 0L) return false
            // 不要关掉这条 channel：关掉会连带关掉 pfd，第二次就读不到文件尾。
            val channel = FileInputStream(it.fileDescriptor).channel
            if (scan(channel, 0L, min(length, HEAD_SCAN))) return true
            if (length > HEAD_SCAN) {
                return scan(channel, max(0L, length - TAIL_SCAN), min(TAIL_SCAN, length))
            }
        }
        return false
    }

    private fun scan(channel: java.nio.channels.FileChannel, start: Long, length: Long): Boolean {
        channel.position(start)
        val buffer = ByteArray(64 * 1024)
        var remaining = length
        var matched = 0
        while (remaining > 0L) {
            val want = min(buffer.size.toLong(), remaining).toInt()
            val read = channel.read(ByteBuffer.wrap(buffer, 0, want))
            if (read <= 0) return false
            remaining -= read
            for (index in 0 until read) {
                matched = if (buffer[index] == SEF_MARK[matched]) matched + 1 else if (buffer[index] == SEF_MARK[0]) 1 else 0
                if (matched == SEF_MARK.size) return true
            }
        }
        return false
    }

    private fun uniqueFile(dir: File, name: String): File {
        val direct = File(dir, name)
        if (!direct.exists()) return direct
        val stem = name.substringBeforeLast('.')
        var index = 1
        while (true) {
            val candidate = File(dir, "${stem}_$index.mp4")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private data class Held(
        val file: File,
        val source: PhotoItem,
        val plan: CompressVideoPlan.Plan,
        val durationMs: Long,
    )

    private companion object {
        const val DATE_TAKEN = MediaStore.Images.Media.DATE_TAKEN
        const val HEAD_SCAN = 1L * 1024L * 1024L
        const val TAIL_SCAN = 2L * 1024L * 1024L
        val SEF_MARK = "SlowMotion_Data".toByteArray(Charsets.US_ASCII)
        val BLOCKED_MIME = setOf("video/mv-hevc")
    }
}

private fun MediaFormat.integer(key: String): Int? {
    if (!containsKey(key)) return null
    return getInteger(key)
}

private fun MediaFormat.long(key: String): Long? {
    if (!containsKey(key)) return null
    return getLong(key)
}

private fun frameRate(format: MediaFormat): Double? {
    if (!format.containsKey(MediaFormat.KEY_FRAME_RATE)) return null
    val asFloat = runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrNull()
    if (asFloat != null && asFloat > 0f) return asFloat.toDouble()
    val asInt = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrNull()
    return asInt?.takeIf { it > 0 }?.toDouble()
}

private fun isHdr(format: MediaFormat, mime: String?): Boolean {
    if (mime.equals("video/dolby-vision", ignoreCase = true)) return true
    val transfer = format.integer(MediaFormat.KEY_COLOR_TRANSFER) ?: return false
    return transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG
}

internal fun VideoProbe.Ok.toRequest(sdkInt: Int) = CompressVideoPlan.Request(
    width = width,
    height = height,
    rotationDegrees = rotationDegrees,
    playbackFps = playbackFps,
    fileSizeBytes = fileSizeBytes,
    durationMs = durationMs,
    hdr = hdr,
    sdkInt = sdkInt,
)

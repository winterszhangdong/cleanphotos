package com.winters.cleanphotos.core.media

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.winters.cleanphotos.domain.model.CompressFailure
import com.winters.cleanphotos.domain.model.CompressVideoPlan
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

sealed interface CompressEncodeResult {
    data class Ready(val file: File, val durationMs: Long) : CompressEncodeResult
    data class Stopped(val failure: CompressFailure) : CompressEncodeResult
}

/**
 * Media3 Transformer 1.11，默认 MP4 muxer。
 * 先缩放并尽量原样保留声音；OpenGL 色彩转换失败且 API ≥ 31 时改用 MediaCodec 色调映射；
 * 音轨进不了 MP4 时改 AAC-LC；编码器拒绝原时间戳时改固定帧率。成功即停。
 */
@OptIn(UnstableApi::class)
@Singleton
class Media3VideoCompressor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun compress(
        uri: Uri,
        plan: CompressVideoPlan.Plan,
        samsungSefSlowMotion: Boolean,
        sdkInt: Int,
        onProgress: (Int) -> Unit,
    ): CompressEncodeResult {
        val dir = File(context.cacheDir, "compress").apply { mkdirs() }
        val output = File(dir, "${System.nanoTime()}.mp4")
        return try {
            withContext(Dispatchers.Main) {
                runAttempts(uri, plan, samsungSefSlowMotion, sdkInt, output, onProgress)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { output.delete() }
            throw e
        }
    }

    private suspend fun runAttempts(
        uri: Uri,
        plan: CompressVideoPlan.Plan,
        samsungSefSlowMotion: Boolean,
        sdkInt: Int,
        output: File,
        onProgress: (Int) -> Unit,
    ): CompressEncodeResult {
        var attempt = Attempt(
            hdrMode = if (plan.toneMapHdrToSdr) {
                Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
            } else {
                Composition.HDR_MODE_KEEP_HDR
            },
            aac = false,
            fixedFps = false,
            sef = samsungSefSlowMotion,
        )
        val tried = HashSet<Attempt>()
        while (tried.add(attempt)) {
            output.delete()
            try {
                export(uri, plan, attempt, output, onProgress)
                val duration = withContext(Dispatchers.IO) { readDuration(output) }
                if (duration == null) {
                    output.delete()
                    return CompressEncodeResult.Stopped(CompressFailure.Failed)
                }
                return CompressEncodeResult.Ready(output, duration)
            } catch (e: CancellationException) {
                output.delete()
                throw e
            } catch (e: Exception) {
                output.delete()
                val next = nextAttempt(attempt, e, sdkInt) ?: return CompressEncodeResult.Stopped(terminal(e))
                attempt = next
            }
        }
        output.delete()
        return CompressEncodeResult.Stopped(CompressFailure.Failed)
    }

    private suspend fun export(
        uri: Uri,
        plan: CompressVideoPlan.Plan,
        attempt: Attempt,
        output: File,
        onProgress: (Int) -> Unit,
    ) {
        val presentation = Presentation.createForWidthAndHeight(
            plan.outputWidth,
            plan.outputHeight,
            Presentation.LAYOUT_SCALE_TO_FIT,
        )
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri))
            .setEffects(Effects(emptyList<androidx.media3.common.audio.AudioProcessor>(), listOf<Effect>(presentation)))
            .setFlattenForSlowMotion(attempt.sef)
            .apply {
                if (attempt.fixedFps) {
                    setFrameRate(plan.outputFps.roundToInt().coerceIn(1, CompressVideoPlan.MAX_OUTPUT_FPS.toInt()))
                }
            }
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(listOf(edited)).build())
            .setHdrMode(attempt.hdrMode)
            .build()
        val encoder = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(plan.videoBitrate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).build(),
            )
            .apply {
                if (attempt.aac) {
                    setRequestedAudioEncoderSettings(
                        AudioEncoderSettings.Builder()
                            .setBitrate(CompressVideoPlan.AUDIO_BITRATE.toInt())
                            .setProfile(MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                            .build(),
                    )
                }
            }
            .build()
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .apply { if (attempt.aac) setAudioMimeType(MimeTypes.AUDIO_AAC) }
            .setEncoderFactory(encoder)
            .build()
        coroutineScope {
            val progress = launch {
                val holder = ProgressHolder()
                var last = -1
                while (isActive) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE && holder.progress != last) {
                        last = holder.progress
                        onProgress(holder.progress.coerceIn(0, 100))
                    }
                    delay(200)
                }
            }
            try {
                suspendCancellableCoroutine { cont ->
                    val listener = object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: androidx.media3.transformer.ExportResult) {
                            transformer.removeListener(this)
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: androidx.media3.transformer.ExportResult,
                            exportException: ExportException,
                        ) {
                            transformer.removeListener(this)
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    }
                    transformer.addListener(listener)
                    cont.invokeOnCancellation { transformer.cancel() }
                    try {
                        transformer.start(composition, output.absolutePath)
                    } catch (e: Exception) {
                        transformer.removeListener(listener)
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            } finally {
                progress.cancel()
            }
        }
    }

    private fun nextAttempt(current: Attempt, error: Throwable, sdkInt: Int): Attempt? {
        val code = findExport(error)?.errorCode ?: 0
        val text = blob(error)
        if (current.hdrMode == Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL &&
            sdkInt >= 31 &&
            isGl(code, text)
        ) {
            return current.copy(hdrMode = Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC)
        }
        if (!current.aac && isAudioMux(code, text)) return current.copy(aac = true)
        if (!current.fixedFps && isTimestamp(text)) return current.copy(fixedFps = true)
        return null
    }

    private fun isGl(code: Int, text: String): Boolean {
        if (code == ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED) return true
        return text.contains("GlException") || text.contains("OpenGL") || text.contains("EGL") ||
            text.contains("ToneMap") || text.contains("tone map", ignoreCase = true)
    }

    private fun isAudioMux(code: Int, text: String): Boolean {
        if (code == ExportException.ERROR_CODE_MUXING_FAILED ||
            code == ExportException.ERROR_CODE_AUDIO_PROCESSING_FAILED
        ) {
            return true
        }
        return text.contains("audio", ignoreCase = true) &&
            (text.contains("mux", ignoreCase = true) || text.contains("mime", ignoreCase = true) ||
                text.contains("unsupported", ignoreCase = true))
    }

    private fun isTimestamp(text: String): Boolean =
        text.contains("timestamp", ignoreCase = true) ||
            text.contains("timeUs") ||
            text.contains("monotonic", ignoreCase = true) ||
            text.contains("presentationTime")

    private fun terminal(error: Throwable): CompressFailure {
        val code = findExport(error)?.errorCode ?: 0
        val text = blob(error)
        if (text.contains("ENOSPC") || text.contains("No space left", ignoreCase = true)) {
            return CompressFailure.NotEnoughSpace
        }
        if (code == ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
            code == ExportException.ERROR_CODE_DECODER_INIT_FAILED ||
            code == ExportException.ERROR_CODE_DECODING_FAILED
        ) {
            return CompressFailure.Undecodable
        }
        return CompressFailure.Failed
    }

    private fun findExport(error: Throwable): ExportException? {
        var current: Throwable? = error
        var guard = 0
        while (current != null && guard < 8) {
            if (current is ExportException) return current
            current = current.cause
            guard++
        }
        return null
    }

    private fun blob(error: Throwable): String = buildString {
        var current: Throwable? = error
        var guard = 0
        while (current != null && guard < 8) {
            append(current.javaClass.name).append(' ').append(current.message).append('\n')
            current = current.cause
            guard++
        }
    }

    private fun readDuration(file: File): Long? {
        if (!file.exists() || file.length() <= 0L) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            retriever.release()
        }
    }

    private data class Attempt(
        val hdrMode: Int,
        val aac: Boolean,
        val fixedFps: Boolean,
        val sef: Boolean,
    )
}

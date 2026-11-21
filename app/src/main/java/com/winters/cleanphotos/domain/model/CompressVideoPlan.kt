package com.winters.cleanphotos.domain.model

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * 单条视频压到 1080p 的计划。不依赖 Android。
 *
 * 宽高是编码尺寸，旋转 90/270 时先对调，再等比缩小：短边不超过 1080、长边不超过 1920，不放大。
 * 结果向下取偶数，至少 2。
 */
object CompressVideoPlan {
    const val MAX_SHORT_EDGE = 1080
    const val MAX_LONG_EDGE = 1920
    const val BASE_BITRATE = 8_000_000L
    const val AUDIO_BITRATE = 160_000L
    const val MIN_VIDEO_BITRATE = 500_000L
    const val MAX_OUTPUT_FPS = 60.0
    const val UNKNOWN_FPS = 30.0
    const val HEADROOM_BYTES = 32L * 1024L * 1024L

    data class Request(
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        /** 播放帧率。null 或非正数按 30。不是拍摄帧率。 */
        val playbackFps: Double?,
        val fileSizeBytes: Long,
        val durationMs: Long,
        val hdr: Boolean,
        val sdkInt: Int,
    )

    data class Plan(
        val outputWidth: Int,
        val outputHeight: Int,
        val outputFps: Double,
        val videoBitrate: Long,
        /** API 29+ 的 HDR 收成 SDR，不保留 HDR / 杜比视界。 */
        val toneMapHdrToSdr: Boolean,
    )

    sealed interface Decision {
        data class Proceed(val plan: Plan) : Decision
        data object AlreadySmall : Decision
        data object HdrOnOldAndroid : Decision
    }

    fun decide(request: Request): Decision {
        if (request.hdr && request.sdkInt <= 28) return Decision.HdrOnOldAndroid
        val (orientedWidth, orientedHeight) = oriented(request.width, request.height, request.rotationDegrees)
        val (outputWidth, outputHeight) = fit(orientedWidth, orientedHeight)
        val fps = outputFps(request.playbackFps)
        val bitrate = videoBitrate(outputWidth, outputHeight, fps, request.fileSizeBytes, request.durationMs)
        val shortEdge = min(orientedWidth, orientedHeight)
        val longEdge = max(orientedWidth, orientedHeight)
        val withinFrame = shortEdge <= MAX_SHORT_EDGE && longEdge <= MAX_LONG_EDGE
        if (withinFrame && request.durationMs > 0L) {
            val sourceBitrate = request.fileSizeBytes * 8.0 / (request.durationMs / 1000.0)
            if (sourceBitrate <= bitrate + AUDIO_BITRATE) return Decision.AlreadySmall
        }
        return Decision.Proceed(
            Plan(
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                outputFps = fps,
                videoBitrate = bitrate,
                toneMapHdrToSdr = request.hdr,
            ),
        )
    }

    /** 成品时长和原播放时长差超过 max(500ms, 原时长的 3%) 则作废。 */
    fun durationAcceptable(originalMs: Long, outputMs: Long): Boolean {
        val slack = max(500L, originalMs * 3L / 100L)
        val delta = if (outputMs >= originalMs) outputMs - originalMs else originalMs - outputMs
        return delta <= slack
    }

    /** 估算成品字节：视频码率/8 × 时长 + 160kbps 音频。 */
    fun estimatedBytes(videoBitrate: Long, durationMs: Long): Long {
        val seconds = durationMs.coerceAtLeast(0L) / 1000.0
        return ((videoBitrate + AUDIO_BITRATE) / 8.0 * seconds).toLong()
    }

    fun requiredFreeBytes(videoBitrate: Long, durationMs: Long): Long =
        estimatedBytes(videoBitrate, durationMs) + HEADROOM_BYTES

    /** 扩展名前加 `_1080p`，容器永远是 `.mp4`。 */
    fun outputDisplayName(displayName: String?): String {
        val raw = displayName?.trim().orEmpty()
        val stem = raw.substringBeforeLast('.', raw).ifEmpty { "video" }
        return "${stem}_1080p.mp4"
    }

    private fun oriented(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> {
        val turns = ((rotationDegrees % 360) + 360) % 360
        return if (turns == 90 || turns == 270) height to width else width to height
    }

    private fun fit(width: Int, height: Int): Pair<Int, Int> {
        require(width >= 2 && height >= 2) { "画面边长至少为 2，收到 ${width}×${height}" }
        val scale = min(
            1.0,
            min(MAX_SHORT_EDGE.toDouble() / min(width, height), MAX_LONG_EDGE.toDouble() / max(width, height)),
        )
        return floorEven(width * scale) to floorEven(height * scale)
    }

    private fun floorEven(value: Double): Int {
        var rounded = floor(value).toInt()
        if (rounded % 2 != 0) rounded -= 1
        return rounded.coerceAtLeast(2)
    }

    private fun outputFps(playbackFps: Double?): Double {
        val fps = playbackFps ?: return UNKNOWN_FPS
        if (fps <= 0.0) return UNKNOWN_FPS
        return min(fps, MAX_OUTPUT_FPS)
    }

    private fun videoBitrate(width: Int, height: Int, fps: Double, fileSizeBytes: Long, durationMs: Long): Long {
        val pixels = width.toDouble() * height.toDouble()
        var bitrate = BASE_BITRATE * (pixels / (MAX_LONG_EDGE.toDouble() * MAX_SHORT_EDGE.toDouble())) *
            sqrt(fps / UNKNOWN_FPS)
        if (durationMs > 0L) {
            val sourceBitrate = fileSizeBytes * 8.0 / (durationMs / 1000.0)
            if (bitrate + AUDIO_BITRATE > sourceBitrate * 0.85) {
                bitrate = max(MIN_VIDEO_BITRATE.toDouble(), sourceBitrate * 0.7 - AUDIO_BITRATE)
            }
        }
        return bitrate.roundToLong()
    }
}

package com.winters.cleanphotos.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressVideoPlanTest {
    @Test
    fun `4K 横竖和旋转后的 1080p 按框缩小且不放大`() {
        assertSize(req(3840, 2160), 1920, 1080)
        assertSize(req(2160, 3840), 1080, 1920)
        assertSize(req(1920, 1080, rotation = 90), 1080, 1920)
        assertSize(req(1920, 1080, rotation = 270), 1080, 1920)
        assertSize(req(3840, 2160, rotation = 180), 1920, 1080)
        assertSize(req(1280, 720), 1280, 720)
        assertSize(req(640, 480), 640, 480)
    }

    @Test
    fun `框内奇数向下取偶，超出框的不因为很小就跳过`() {
        assertSize(req(1001, 800), 1000, 800)
        val over = decide(req(1921, 1080, fileSizeBytes = 100, durationMs = 10_000))
        val plan = over as CompressVideoPlan.Decision.Proceed
        assertEquals(1920, plan.plan.outputWidth)
        assertEquals(1078, plan.plan.outputHeight)
        val odd = decide(req(1920, 1081))
        val oddPlan = odd as CompressVideoPlan.Decision.Proceed
        assertEquals(1918, oddPlan.plan.outputWidth)
        assertEquals(1080, oddPlan.plan.outputHeight)
    }

    @Test
    fun `帧率封顶 60，未知按 30`() {
        assertEquals(60.0, fps(120.0), 0.0)
        assertEquals(30.0, fps(null), 0.0)
        assertEquals(30.0, fps(0.0), 0.0)
        assertEquals(24.0, fps(24.0), 0.0)
    }

    @Test
    fun `1080p30 基准 8Mbps，60 帧按根号放大，原片太小则下调`() {
        val full = decide(req(1920, 1080, fileSizeBytes = 80_000_000, durationMs = 10_000, fps = 30.0))
        assertEquals(8_000_000L, (full as CompressVideoPlan.Decision.Proceed).plan.videoBitrate)
        val sixty = decide(req(1920, 1080, fileSizeBytes = 80_000_000, durationMs = 10_000, fps = 60.0))
        assertEquals(11_313_708L, (sixty as CompressVideoPlan.Decision.Proceed).plan.videoBitrate)
        val small = decide(req(1920, 1080, fileSizeBytes = 1_250_000, durationMs = 10_000, fps = 30.0))
        assertEquals(540_000L, (small as CompressVideoPlan.Decision.Proceed).plan.videoBitrate)
    }

    @Test
    fun `框内且码率已经不超过目标则跳过，分辨率仍超出则不跳过`() {
        assertTrue(decide(req(1280, 720, fileSizeBytes = 500_000, durationMs = 10_000)) is CompressVideoPlan.Decision.AlreadySmall)
        assertTrue(decide(req(3840, 2160, fileSizeBytes = 500_000, durationMs = 10_000)) is CompressVideoPlan.Decision.Proceed)
    }

    @Test
    fun `HDR 在 API 28 拒绝、29 允许并标成 SDR`() {
        assertTrue(decide(req(3840, 2160, hdr = true, sdkInt = 26)) is CompressVideoPlan.Decision.HdrOnOldAndroid)
        assertTrue(decide(req(3840, 2160, hdr = true, sdkInt = 28)) is CompressVideoPlan.Decision.HdrOnOldAndroid)
        val ok = decide(req(3840, 2160, hdr = true, sdkInt = 29)) as CompressVideoPlan.Decision.Proceed
        assertTrue(ok.plan.toneMapHdrToSdr)
        val sdr = decide(req(3840, 2160, hdr = false, sdkInt = 29)) as CompressVideoPlan.Decision.Proceed
        assertFalse(sdr.plan.toneMapHdrToSdr)
    }

    @Test
    fun `时长差超过 500 毫秒或原时长的百分之 3 则作废`() {
        assertTrue(CompressVideoPlan.durationAcceptable(10_000, 10_500))
        assertFalse(CompressVideoPlan.durationAcceptable(10_000, 10_501))
        assertTrue(CompressVideoPlan.durationAcceptable(100_000, 103_000))
        assertFalse(CompressVideoPlan.durationAcceptable(100_000, 103_001))
    }

    @Test
    fun `显示名在扩展名前加 _1080p 且容器是 mp4`() {
        assertEquals("clip_1080p.mp4", CompressVideoPlan.outputDisplayName("clip.mov"))
        assertEquals("noext_1080p.mp4", CompressVideoPlan.outputDisplayName("noext"))
        assertEquals("video_1080p.mp4", CompressVideoPlan.outputDisplayName(null))
    }

    private fun fps(playback: Double?): Double {
        val decision = decide(req(1920, 1080, fileSizeBytes = 80_000_000, durationMs = 10_000, fps = playback))
        return (decision as CompressVideoPlan.Decision.Proceed).plan.outputFps
    }

    private fun assertSize(request: CompressVideoPlan.Request, width: Int, height: Int) {
        val plan = decide(request) as CompressVideoPlan.Decision.Proceed
        assertEquals(width, plan.plan.outputWidth)
        assertEquals(height, plan.plan.outputHeight)
    }

    private fun decide(request: CompressVideoPlan.Request) = CompressVideoPlan.decide(request)

    private fun req(
        width: Int,
        height: Int,
        rotation: Int = 0,
        fps: Double? = 30.0,
        fileSizeBytes: Long = 80_000_000,
        durationMs: Long = 10_000,
        hdr: Boolean = false,
        sdkInt: Int = 34,
    ) = CompressVideoPlan.Request(width, height, rotation, fps, fileSizeBytes, durationMs, hdr, sdkInt)
}

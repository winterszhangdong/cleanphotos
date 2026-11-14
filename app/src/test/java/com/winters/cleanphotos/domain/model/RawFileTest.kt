package com.winters.cleanphotos.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawFileTest {

    @Test
    fun `扩展名判断 RAW，大小写不分，JPEG 和没有扩展名不是`() {
        assertTrue(isRawFileName("IMG_1.dng"))
        assertTrue(isRawFileName("IMG_1.DNG"))
        assertTrue(isRawFileName("shot.CR3"))
        assertTrue(isRawFileName("a.nef"))
        assertTrue(isRawFileName("a.arw"))
        assertTrue(isRawFileName("a.gpr"))
        assertFalse(isRawFileName("IMG_1.jpg"))
        assertFalse(isRawFileName("IMG_1.HEIC"))
        assertFalse(isRawFileName("clip.mp4"))
        assertFalse(isRawFileName("archive.dng.jpg"))
        assertFalse(isRawFileName("photo"))
        assertFalse(isRawFileName("photo."))
        assertFalse(isRawFileName(null))
        assertFalse(isRawFileName(""))
    }

    @Test
    fun `左下角一行：RAW、动态、大小按有的连起来`() {
        assertEquals("RAW", photoMarkLine("RAW", null, null))
        assertEquals("动态", photoMarkLine(null, "动态", null))
        assertEquals("RAW · 动态", photoMarkLine("RAW", "动态", null))
        assertEquals("RAW · 28 MB", photoMarkLine("RAW", null, "28 MB"))
        assertEquals("RAW · 动态 · 28 MB", photoMarkLine("RAW", "动态", "28 MB"))
        assertEquals("28 MB", photoMarkLine(null, null, "28 MB"))
        assertNull(photoMarkLine(null, null, null))
        assertNull(photoMarkLine("", "", ""))
    }

    @Test
    fun `RAW 和大 PNG 是重图，屏幕大小的 PNG 以及 JPEG 不是`() {
        assertTrue(isHeavyStill("a.dng"))
        assertTrue(isHeavyStill("a.DNG"))
        assertTrue(isHeavyStill("a.CR2"))
        assertTrue(isHeavyStill("a.dng", 1440, 3168))
        // 不传边长、边长缺失或不是正数时，PNG 走稳妥的重图路径
        assertTrue(isHeavyStill("shot.png"))
        assertTrue(isHeavyStill("shot.PNG"))
        assertTrue(isHeavyStill("shot.png", null, 100))
        assertTrue(isHeavyStill("shot.png", 100, null))
        assertTrue(isHeavyStill("shot.png", 0, 100))
        assertTrue(isHeavyStill("shot.png", PNG_SCREEN_MAX_EDGE + 1, 10))
        assertTrue(isHeavyStill("shot.png", 10, PNG_SCREEN_MAX_EDGE + 1))
        assertFalse(isHeavyStill("shot.png", 1440, 3168))
        assertFalse(isHeavyStill("shot.PNG", PNG_SCREEN_MAX_EDGE, PNG_SCREEN_MAX_EDGE))
        assertFalse(isHeavyStill("a.jpg"))
        assertFalse(isHeavyStill("a.JPEG"))
        assertFalse(isHeavyStill("a.jpg", 8000, 6000))
        assertFalse(isHeavyStill("a.heic"))
        assertFalse(isHeavyStill("a.HEIF"))
        assertFalse(isHeavyStill("archive.dng.jpg"))
        assertFalse(isHeavyStill("photo.png.jpg"))
        assertFalse(isHeavyStill("clip.mp4"))
        assertFalse(isHeavyStill("photo"))
        assertFalse(isHeavyStill("photo."))
        assertFalse(isHeavyStill(null))
        assertFalse(isHeavyStill(""))
    }

    @Test
    fun `一倍图来源：RAW 用内嵌预览，大 PNG 用系统缩略图，其余解原图`() {
        assertEquals(GlanceKind.RawEmbedded, glanceKind("a.dng", 4000, 3000))
        assertEquals(GlanceKind.SystemThumb, glanceKind("shot.png", null, null))
        assertEquals(GlanceKind.SystemThumb, glanceKind("shot.PNG", 6000, 4000))
        assertEquals(GlanceKind.Original, glanceKind("shot.png", 1440, 3168))
        assertEquals(GlanceKind.Original, glanceKind("a.jpg", 8000, 6000))
        assertEquals(GlanceKind.Original, glanceKind("a.heic", null, null))
        assertEquals(GlanceKind.Original, glanceKind(null, null, null))
    }

    @Test
    fun `已经解好的更清楚图不退回一倍图`() {
        assertEquals(StillFrame.Glance, stillFrame(false, false, false, true, true))
        assertEquals(StillFrame.Glance, stillFrame(false, true, true, false, false))
        assertEquals(StillFrame.Screen, stillFrame(false, true, true, true, false))
        assertEquals(StillFrame.Screen, stillFrame(true, true, true, true, false))
        assertEquals(StillFrame.Sharp, stillFrame(true, true, true, true, true))
        assertEquals(StillFrame.Sharp, stillFrame(false, true, true, false, true))
        assertEquals(StillFrame.Sharp, stillFrame(false, false, true, false, true))
        assertEquals(StillFrame.Glance, stillFrame(true, false, true, false, false))
        assertEquals(StillFrame.Screen, stillFrame(false, true, false, true, false))
    }

    @Test
    fun `清楚图边长是屏幕的 3 倍，至少 1`() {
        assertEquals(3240 to 7200, sharpStillSize(1080, 2400))
        assertEquals(3 to 3, sharpStillSize(1, 1))
        assertEquals(1 to 1, sharpStillSize(0, 0))
        assertEquals(20 to 10, sharpStillSize(10, 5, scale = 2))
    }
}

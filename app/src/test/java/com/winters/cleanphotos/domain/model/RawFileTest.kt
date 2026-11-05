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
    fun `RAW 和 PNG 是重图，JPEG 和没有扩展名不是`() {
        assertTrue(isHeavyStill("a.dng"))
        assertTrue(isHeavyStill("a.DNG"))
        assertTrue(isHeavyStill("a.CR2"))
        assertTrue(isHeavyStill("shot.png"))
        assertTrue(isHeavyStill("shot.PNG"))
        assertFalse(isHeavyStill("a.jpg"))
        assertFalse(isHeavyStill("a.JPEG"))
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
    fun `清楚图边长是屏幕的 3 倍，至少 1`() {
        assertEquals(3240 to 7200, sharpStillSize(1080, 2400))
        assertEquals(3 to 3, sharpStillSize(1, 1))
        assertEquals(1 to 1, sharpStillSize(0, 0))
        assertEquals(20 to 10, sharpStillSize(10, 5, scale = 2))
    }
}

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
}

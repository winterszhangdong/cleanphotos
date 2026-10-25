package com.winters.cleanphotos.feature.allphotos

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 照片页网格的点按坐标。item.offset 不含 contentPadding，手指坐标要先加上 viewportStartOffset。
 */
class PhotoGridHitTest {
    private val padding = 400
    private val viewportStart = -padding
    private val tile = 250

    @Test fun `顶部内边距下_点在上面那格_命中上面而不是下面`() {
        val upperTop = 0
        val lowerTop = tile
        val tapOnUpper = Offset(20f, padding + 40f)
        assertTrue(pointerHitsGridCell(tapOnUpper, 0, upperTop, tile, tile, viewportStart))
        assertFalse(pointerHitsGridCell(tapOnUpper, 0, lowerTop, tile, tile, viewportStart))
    }

    @Test fun `不修正内边距时_点在上面会落到下面那格`() {
        val tapOnUpper = Offset(20f, padding + 40f)
        assertTrue(pointerHitsGridCell(tapOnUpper, 0, tile, tile, tile, viewportStartOffset = 0))
    }

    @Test fun `没有顶部内边距时_坐标原样比对`() {
        val tap = Offset(10f, 30f)
        assertTrue(pointerHitsGridCell(tap, 0, 0, 100, 100, viewportStartOffset = 0))
        assertFalse(pointerHitsGridCell(tap, 0, 100, 100, 100, viewportStartOffset = 0))
    }

    @Test fun `实际边界命中看到的那张_重叠时取中心更近的`() {
        val rects = mapOf(
            1L to Rect(0f, 0f, 100f, 100f),
            2L to Rect(80f, 0f, 180f, 100f),
        )
        assertEquals(1L, hitPhotoId(10f, 10f, rects))
        assertEquals(1L, hitPhotoId(90f, 50f, rects))
        assertEquals(2L, hitPhotoId(140f, 50f, rects))
        assertNull(hitPhotoId(200f, 50f, rects))
    }

    @Test fun `网格原点加上本地坐标才对上根坐标里的格子`() {
        val hits = PhotoHitRects()
        hits.originX = 20f
        hits.originY = 400f
        hits.put(7L, Rect(20f, 400f, 120f, 500f))
        assertEquals(7L, hits.hit(Offset(10f, 20f)))
        assertNull(hits.hit(Offset(10f, 200f)))
    }
}

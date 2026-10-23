package com.winters.cleanphotos.feature.allphotos

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertFalse
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
}

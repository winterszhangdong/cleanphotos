package com.winters.cleanphotos.feature.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewAmbientTest {
    @Test fun `源图模糊半径按铺满屏幕的缩放折回`() {
        // 2400px 的长边，屏幕上 99px 模糊，96px 小图的缩放是 25 倍：99 / 25 = 3.96 → 4
        assertEquals(4, ambientSourceRadius(99f, 96, 2400))
        assertEquals(1, ambientSourceRadius(0f, 96, 2400))
        assertEquals(1, ambientSourceRadius(99f, 96, 0))
    }

    @Test fun `纯色模糊后仍是纯色`() {
        val pixels = IntArray(16) { 0xFF123456.toInt() }
        boxBlur3(pixels, 4, 4, 2)
        assertTrue(pixels.all { it == 0xFF123456.toInt() })
    }

    @Test fun `色块交界被抹开`() {
        val w = 8
        val h = 4
        val pixels = IntArray(w * h) { i -> if (i % w < 4) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        boxBlur3(pixels, w, h, 2)
        val boundary = pixels[3]
        assertTrue(boundary != 0xFFFF0000.toInt())
        assertEquals(0xFF, boundary ushr 24)
    }
}

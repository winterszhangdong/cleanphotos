package com.winters.cleanphotos.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharpnessTest {

    @Test
    fun `纯色方差是 0，清晰图案大于同一图案模糊后`() {
        val solid = IntArray(8 * 8) { 40 }
        assertEquals(0.0, Sharpness.laplacianVariance(solid, 8, 8), 0.0)

        val sharp = IntArray(8 * 8) { i -> if ((i % 8) % 2 == 0) 0 else 255 }
        val blur = IntArray(8 * 8) { i -> (i % 8) * 32 }
        val sharpScore = Sharpness.laplacianVariance(sharp, 8, 8)
        val blurScore = Sharpness.laplacianVariance(blur, 8, 8)
        assertTrue(sharpScore > blurScore)
        assertTrue(sharpScore > 0.0)
    }
}

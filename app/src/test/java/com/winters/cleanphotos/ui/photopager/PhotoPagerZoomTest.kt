package com.winters.cleanphotos.ui.photopager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缩放参数与灵敏度的纯函数测试。
 *
 * 这些测试直接约束 Requirement 1 的调优结果，
 * 避免后续有人把灵敏度或最大倍率悄悄改回去。
 */
class PhotoPagerZoomTest {

    @Test
    fun `pinch放大增量会按灵敏度放大`() {
        val adjusted = adjustPinchZoomDelta(rawZoomDelta = 1.10f)

        assertTrue("调优后的缩放增量应明显大于原始值", adjusted > 1.10f)
        assertEquals("应按 1.4 倍灵敏度放大增量", 1.14f, adjusted, 0.0001f)
    }

    @Test
    fun `pinch缩小增量会按灵敏度增强`() {
        val adjusted = adjustPinchZoomDelta(rawZoomDelta = 0.95f)

        assertEquals("缩小时也应按同一灵敏度增强", 0.93f, adjusted, 0.0001f)
    }

    @Test
    fun `缩放值会被钳位到配置范围`() {
        assertEquals("最大缩放应限制到 3 倍", PhotoPagerMaxZoom, clampPhotoPagerZoom(3.6f), 0.0001f)
        assertEquals("最小缩放应限制到 1 倍", PhotoPagerMinZoom, clampPhotoPagerZoom(0.6f), 0.0001f)
    }
}

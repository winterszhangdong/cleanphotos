package com.winters.cleanphotos.feature.trash

import com.winters.cleanphotos.core.design.MotionDurations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 溶解时钟必须让最后一个可见格子完整溶解完（之前少走一个步长，最后一格停在半溶解）。 */
class TrashDissolveTimingTest {

    private fun startOf(order: Int) = order * MotionDurations.DISSOLVE_STAGGER

    @Test
    fun `头部可见时格子 order 从 1 开始，时钟覆盖最后一格`() {
        // header 在第 0 项，3 张照片的 order = 1, 2, 3
        val total = dissolveClockMillis(lastOrder = 3)
        assertEquals(380 + 3 * 60, total)
        assertTrue(total >= startOf(3) + MotionDurations.DISSOLVE_ITEM)
    }

    @Test
    fun `每个可见格子在时钟结束时进度都是 1`() {
        for (last in 0..20) {
            val total = dissolveClockMillis(last)
            for (order in 0..last) {
                val p = ((total - startOf(order)).toFloat() / MotionDurations.DISSOLVE_ITEM).coerceIn(0f, 1f)
                assertEquals("order=$order last=$last", 1f, p, 0f)
            }
        }
    }

    @Test
    fun `没有可见格子时只走一格时长`() {
        assertEquals(MotionDurations.DISSOLVE_ITEM, dissolveClockMillis(null))
        assertEquals(MotionDurations.DISSOLVE_ITEM, dissolveClockMillis(-1))
    }
}

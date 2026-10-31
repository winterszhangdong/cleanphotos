package com.winters.cleanphotos.core.design

import org.junit.Assert.assertEquals
import org.junit.Test

/** 撤回条按真实时间计 4 秒：隐藏后重新出现不重置。 */
class UndoDeadlineTest {
    @Test
    fun `剩余时间从开始时刻扣_重新出现不回到满格`() {
        assertEquals(4000L, undoRemainingMillis(startedAt = 1_000, now = 1_000, total = 4000))
        // 上下拖动隐藏 1.5 秒后重新出现：只剩 2.5 秒
        assertEquals(2500L, undoRemainingMillis(startedAt = 1_000, now = 2_500, total = 4000))
        assertEquals(0L, undoRemainingMillis(startedAt = 1_000, now = 9_000, total = 4000))
    }

    @Test
    fun `从到期时刻反推起点_到点后剩余为 0`() {
        val total = 4000
        val deadline = 5_000L
        val startedAt = deadline - total
        assertEquals(4000L, undoRemainingMillis(startedAt, now = 1_000, total = total))
        assertEquals(1500L, undoRemainingMillis(startedAt, now = 3_500, total = total))
        assertEquals(0L, undoRemainingMillis(startedAt, now = deadline, total = total))
        assertEquals(0L, undoRemainingMillis(startedAt, now = deadline + 1, total = total))
    }
}

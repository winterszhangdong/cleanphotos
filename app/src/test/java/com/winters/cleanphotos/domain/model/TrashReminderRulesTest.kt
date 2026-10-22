package com.winters.cleanphotos.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrashReminderRulesTest {

    private val now = 1_700_000_000_000L
    private val gb = TrashReminderRules.SIZE_THRESHOLD_BYTES
    private val age = TrashReminderRules.AGE_THRESHOLD_MS
    private val week = TrashReminderRules.INTERVAL_MS

    @Test
    fun `空垃圾桶不提醒`() {
        assertNull(eval(count = 0, totalBytes = gb, earliest = now - age, shown = null))
    }

    @Test
    fun `正好 1 GB 用大小文案`() {
        assertEquals(TrashReminderReason.Size, eval(count = 1, totalBytes = gb, earliest = now, shown = null))
    }

    @Test
    fun `差 1 字节不到 1 GB 且未满 30 天不提醒`() {
        assertNull(eval(count = 1, totalBytes = gb - 1, earliest = now - age + 1, shown = null))
    }

    @Test
    fun `正好 30 天用天数文案`() {
        assertEquals(TrashReminderReason.Age, eval(count = 1, totalBytes = 0, earliest = now - age, shown = null))
    }

    @Test
    fun `两条都满足时选大小文案`() {
        assertEquals(TrashReminderReason.Size, eval(count = 2, totalBytes = gb, earliest = now - age, shown = null))
    }

    @Test
    fun `从没提醒过可以提醒`() {
        assertEquals(TrashReminderReason.Size, eval(count = 1, totalBytes = gb, earliest = now, shown = null))
    }

    @Test
    fun `正好隔了 7 天可以再提醒`() {
        assertEquals(TrashReminderReason.Age, eval(count = 1, totalBytes = 0, earliest = now - age, shown = now - week))
    }

    @Test
    fun `距上次提醒差 1 毫秒不满 7 天不提醒`() {
        assertNull(eval(count = 1, totalBytes = gb, earliest = now - age, shown = now - week + 1))
    }

    @Test
    fun `size 为 null 记 0`() {
        assertEquals(0L, TrashReminderRules.totalBytes(listOf(null, null)))
        assertEquals(gb, TrashReminderRules.totalBytes(listOf(null, gb)))
        assertNull(eval(count = 2, totalBytes = TrashReminderRules.totalBytes(listOf(null, null)), earliest = now, shown = null))
        assertEquals(
            TrashReminderReason.Size,
            eval(count = 2, totalBytes = TrashReminderRules.totalBytes(listOf(null, gb)), earliest = now, shown = null),
        )
    }

    private fun eval(count: Int, totalBytes: Long, earliest: Long?, shown: Long?) =
        TrashReminderRules.evaluate(count, totalBytes, earliest, shown, now)
}

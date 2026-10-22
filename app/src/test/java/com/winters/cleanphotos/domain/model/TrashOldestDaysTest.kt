package com.winters.cleanphotos.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrashOldestDaysTest {

    private val now = 1_700_000_000_000L
    private val age = TrashReminderRules.AGE_THRESHOLD_MS
    private val day = age / 30

    @Test
    fun `垃圾桶为空不显示天数`() {
        assertNull(TrashOldestDays.wholeDays(emptyList(), now))
    }

    @Test
    fun `正好 30 天显示 30，较新的一张不改变最早`() {
        assertEquals(30, TrashOldestDays.wholeDays(listOf(now - age), now))
        assertEquals(30, TrashOldestDays.wholeDays(listOf(now, now - age, now - day), now))
    }

    @Test
    fun `差 1 毫秒不到 30 天不显示天数`() {
        assertNull(TrashOldestDays.wholeDays(listOf(now - age + 1), now))
    }

    @Test
    fun `超过 30 天按已放的整天数`() {
        assertEquals(45, TrashOldestDays.wholeDays(listOf(now - day * 45), now))
    }
}

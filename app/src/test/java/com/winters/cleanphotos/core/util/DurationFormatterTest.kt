package com.winters.cleanphotos.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatterTest {
    @Test
    fun formatUnderOneHour() {
        assertEquals("0:00", DurationFormatter.format(0))
        assertEquals("0:00", DurationFormatter.format(999))
        assertEquals("0:01", DurationFormatter.format(1000))
        assertEquals("1:01", DurationFormatter.format(61_000))
        assertEquals("59:59", DurationFormatter.format(3_599_000))
    }

    @Test
    fun formatAtLeastOneHour() {
        assertEquals("1:00:00", DurationFormatter.format(3_600_000))
        assertEquals("1:01:01", DurationFormatter.format(3_661_000))
        assertEquals("10:00:00", DurationFormatter.format(36_000_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeThrows() {
        DurationFormatter.format(-1)
    }
}

package com.winters.cleanphotos.core.util

import com.winters.cleanphotos.TestPhotoData
import com.winters.cleanphotos.domain.model.PhotoSortOrder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DayDisplayTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test
    fun visibleDayPhotos_keepsFirstEightInCurrentOrder() {
        val photos = (1..10).map { createId ->
            TestPhotoData.createTestPhoto(id = createId.toLong())
        }

        val visible = DayDisplay.visibleDayPhotos(photos)

        assertEquals(8, visible.size)
        assertEquals((1L..8L).toList(), visible.map { it.id })
    }

    @Test
    fun dayHeader_isDayNumeralAndMonth() {
        val timestamp = LocalDate.of(2024, 9, 21)
            .atStartOfDay(shanghai)
            .toInstant()
            .toEpochMilli()

        val header = DayDisplay.dayHeader(timestamp, shanghai)

        assertEquals("21", header.dayNumeral)
        assertEquals("9月", header.monthLabel)
    }

    @Test
    fun cleanPageDate_isMonthDay() {
        val timestamp = LocalDate.of(2024, 9, 21)
            .atStartOfDay(shanghai)
            .toInstant()
            .toEpochMilli()

        assertEquals("9月21日", DayDisplay.cleanPageDate(timestamp, shanghai))
    }

    @Test
    fun dayHeaderFromKey_parsesIsoAndEnglishGroupKey() {
        assertEquals("21", DayDisplay.dayHeaderFromKey("2024-09-21").dayNumeral)
        assertEquals("9月", DayDisplay.dayHeaderFromKey("2024-09-21").monthLabel)
        assertEquals("21", DayDisplay.dayHeaderFromKey("September 21, 2024").dayNumeral)
        assertEquals("9月", DayDisplay.dayHeaderFromKey("September 21, 2024").monthLabel)
    }

    @Test
    fun sortLabel_oldestIs最早优先() {
        assertEquals("最早优先", DayDisplay.sortLabel(PhotoSortOrder.OldestFirst))
        assertEquals("最新优先", DayDisplay.sortLabel(PhotoSortOrder.NewestFirst))
    }
}

package com.winters.cleanphotos.core.util

import com.winters.cleanphotos.TestPhotoData
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class DayDisplayTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val zh = Locale.SIMPLIFIED_CHINESE
    private val en = Locale.ENGLISH

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

        val header = DayDisplay.dayHeader(timestamp, shanghai, zh)

        assertEquals("21", header.dayNumeral)
        assertEquals("9月", header.monthLabel)
    }

    @Test
    fun cleanPageDate_isMonthDay() {
        val timestamp = LocalDate.of(2024, 9, 21)
            .atStartOfDay(shanghai)
            .toInstant()
            .toEpochMilli()

        assertEquals("9月21日", DayDisplay.cleanPageDate(timestamp, shanghai, zh))
        assertEquals("Sep 21", DayDisplay.cleanPageDate(timestamp, shanghai, en))
    }

    @Test
    fun dayHeaderFromKey_parsesIsoAndEnglishGroupKey() {
        assertEquals("21", DayDisplay.dayHeaderFromKey("2024-09-21", zh).dayNumeral)
        assertEquals("9月", DayDisplay.dayHeaderFromKey("2024-09-21", zh).monthLabel)
        assertEquals("21", DayDisplay.dayHeaderFromKey("September 21, 2024", zh).dayNumeral)
        assertEquals("9月", DayDisplay.dayHeaderFromKey("September 21, 2024", zh).monthLabel)
    }

    @Test
    fun monthTitle_splitsChineseNumeralAndUsesShortEnglishName() {
        val october = LocalDate.of(2026, 10, 21)
        val zhTitle = DayDisplay.monthTitle(october, zh)
        assertEquals("10", zhTitle.dayNumeral)
        assertEquals("月", zhTitle.monthLabel)
        val enTitle = DayDisplay.monthTitle(october, en)
        assertEquals("Oct", enTitle.dayNumeral)
        assertEquals("", enTitle.monthLabel)
    }

    @Test
    fun englishLocale_usesShortMonthAndWeekday() {
        val date = LocalDate.of(2026, 9, 2)
        assertEquals("Sep · Wed", DayDisplay.dayLabel(date, en).monthLabel)
        assertEquals("Sep 2 · Wed", DayDisplay.summaryEyebrow(date, en))
        assertEquals("9月2日 · 周三", DayDisplay.summaryEyebrow(date, zh))
        assertEquals("Sep", DayDisplay.monthName(9, en))
        assertEquals("9月", DayDisplay.monthName(9, zh))
    }

    @Test
    fun anyChineseRegion_usesChineseDateFormat() {
        assertEquals("9月2日", DayDisplay.cleanDate(LocalDate.of(2026, 9, 2), Locale.TRADITIONAL_CHINESE))
    }

    @Test
    fun cleanDateOfEpochDay_unknownIsNull() {
        assertEquals(null, DayDisplay.cleanDateOfEpochDay(Long.MIN_VALUE, en))
        assertEquals("Sep 2", DayDisplay.cleanDateOfEpochDay(LocalDate.of(2026, 9, 2).toEpochDay(), en))
    }
}

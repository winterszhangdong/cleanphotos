package com.winters.cleanphotos.domain.model

import com.winters.cleanphotos.TestPhotoData
import com.winters.cleanphotos.domain.usecase.FilterPhotosUseCase
import com.winters.cleanphotos.fake.FakeSeenStateRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.Year
import java.time.ZoneId

class OnThisDayRulesTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2024, 3, 1)

    private fun at(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun photo(
        id: Long,
        dateTaken: Long?,
        mediaType: MediaType = MediaType.Image,
    ) = TestPhotoData.createTestPhoto(id).copy(dateTaken = dateTaken, mediaType = mediaType)

    @Test
    fun `同月日且更早的年份命中_今年和不同日不命中`() {
        assertTrue(OnThisDayRules.isOnThisDay(photo(1, at(LocalDate.of(2020, 3, 1))), today, zone))
        assertTrue(OnThisDayRules.isOnThisDay(photo(2, at(LocalDate.of(2023, 3, 1))), today, zone))
        assertFalse(OnThisDayRules.isOnThisDay(photo(3, at(LocalDate.of(2024, 3, 1))), today, zone))
        assertFalse(OnThisDayRules.isOnThisDay(photo(4, at(LocalDate.of(2020, 3, 2))), today, zone))
        assertFalse(OnThisDayRules.isOnThisDay(photo(5, at(LocalDate.of(2020, 2, 28))), today, zone))
    }

    @Test
    fun `二月二十九日只在二月二十九日命中`() {
        val leapDay = LocalDate.of(2024, 2, 29)
        val leapPhoto = photo(1, at(LocalDate.of(2020, 2, 29)))
        assertTrue(OnThisDayRules.isOnThisDay(leapPhoto, leapDay, zone))
        assertFalse(OnThisDayRules.isOnThisDay(photo(2, at(LocalDate.of(2024, 2, 29))), leapDay, zone))
        assertFalse(OnThisDayRules.isOnThisDay(leapPhoto, LocalDate.of(2023, 2, 28), zone))
        assertFalse(OnThisDayRules.isOnThisDay(leapPhoto, LocalDate.of(2023, 3, 1), zone))
        assertFalse(OnThisDayRules.isOnThisDay(leapPhoto, today, zone))
    }

    @Test
    fun `没有拍摄时间不命中_视频同样命中`() {
        assertFalse(OnThisDayRules.isOnThisDay(photo(1, null), today, zone))
        assertFalse(OnThisDayRules.isOnThisDay(photo(2, 0L), today, zone))
        assertFalse(OnThisDayRules.isOnThisDay(photo(3, -1L), today, zone))
        assertTrue(
            OnThisDayRules.isOnThisDay(
                photo(4, at(LocalDate.of(2019, 3, 1)), MediaType.Video),
                today,
                zone,
            ),
        )
    }

    @Test
    fun `FilterPhotosUseCase_OnThisDay只留往年同月日且不看已看`() = runTest {
        val clockZone = ZoneId.systemDefault()
        val now = LocalDate.now(clockZone)
        fun millis(date: LocalDate) = date.atStartOfDay(clockZone).toInstant().toEpochMilli()
        val lastYear = photo(1, millis(previousSameDay(now, 1)))
        val thisYear = photo(2, millis(now))
        val otherDay = photo(3, millis(now.minusDays(1)))
        val video = photo(4, millis(previousSameDay(now, 2)), MediaType.Video)
        val noDate = photo(5, null)
        val filter = FilterPhotosUseCase(FakeSeenStateRepository())

        assertEquals(
            listOf(1L, 4L),
            filter(listOf(lastYear, thisYear, otherDay, video, noDate), PhotoFilterMode.OnThisDay, setOf(1L, 4L), today = now).map { it.id },
        )
    }

    @Test
    fun `纯函数用传入的今天而不是当前日期`() {
        val clockZone = ZoneId.systemDefault()
        val today = LocalDate.of(2020, 6, 15)
        val hit = photo(1, today.minusYears(3).atStartOfDay(clockZone).toInstant().toEpochMilli())
        val filter = FilterPhotosUseCase(FakeSeenStateRepository())
        assertEquals(
            listOf(1L),
            filter(listOf(hit), PhotoFilterMode.OnThisDay, emptySet(), today = today).map { it.id },
        )
    }
}

/** 往前第 n 个与今天月日相同的日期。2 月 29 日跳过非闰年，避免 minusYears 落到 2 月 28 日。 */
private fun previousSameDay(today: LocalDate, nth: Int): LocalDate {
    var year = today.year
    var found = 0
    while (found < nth) {
        year -= 1
        if (today.monthValue == 2 && today.dayOfMonth == 29 && !Year.isLeap(year.toLong())) continue
        found++
    }
    return LocalDate.of(year, today.monthValue, today.dayOfMonth)
}

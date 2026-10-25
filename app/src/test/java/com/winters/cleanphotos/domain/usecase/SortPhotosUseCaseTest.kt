package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.TestPhotoData
import com.winters.cleanphotos.domain.model.PhotoSortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SortPhotosUseCaseTest {

    private val sort = SortPhotosUseCase()

    private fun photo(id: Long, dateTaken: Long?, size: Long? = 100L) =
        TestPhotoData.createTestPhoto(id).copy(dateTaken = dateTaken, size = size)

    @Test
    fun `NewestFirst 新的在前_没有拍摄时间的排在最后`() {
        val photos = listOf(
            photo(3, 1_000),
            photo(4, null),
            photo(1, 3_000),
            photo(2, 2_000),
        )
        assertEquals(listOf(1L, 2L, 3L, 4L), sort(photos, PhotoSortOrder.NewestFirst).map { it.id })
    }

    @Test
    fun `OldestFirst 旧的在前_没有拍摄时间的排在最后`() {
        val photos = listOf(
            photo(1, 3_000),
            photo(4, null),
            photo(3, 1_000),
            photo(2, 2_000),
        )
        assertEquals(listOf(3L, 2L, 1L, 4L), sort(photos, PhotoSortOrder.OldestFirst).map { it.id })
    }

    @Test
    fun `LargestFirst 按大小降序_相同再按拍摄时间新到旧`() {
        val photos = listOf(
            photo(1, 4_000, size = 10),
            photo(2, 1_000, size = 50),
            photo(4, null, size = 50),
            photo(3, 3_000, size = 50),
        )
        assertEquals(listOf(3L, 2L, 4L, 1L), sort(photos, PhotoSortOrder.LargestFirst).map { it.id })
    }

    @Test
    fun `LargestFirst 没有 size 直接出错`() {
        val thrown = kotlin.runCatching {
            // 只有一张时排序不会比较，size 的检查发生在比较里
            sort(listOf(photo(7, 1_000, size = null), photo(8, 2_000, size = 10)), PhotoSortOrder.LargestFirst)
        }
        assertTrue(thrown.exceptionOrNull() is IllegalStateException)
        assertTrue(thrown.exceptionOrNull()!!.message!!.contains("7"))
    }
}

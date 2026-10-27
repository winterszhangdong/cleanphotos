package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.TestPhotoData.createTestPhoto
import com.winters.cleanphotos.domain.model.AlbumRules
import com.winters.cleanphotos.domain.model.FilingRecord
import com.winters.cleanphotos.domain.model.PhotoAlbum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 底片袋规则、托盘排序、「已冲洗」按天合并。 */
class AlbumUseCasesTest {

    private fun photo(id: Long, path: String?, taken: Long = id, day: String = "October 24, 2026") =
        createTestPhoto(id = id, dateGroup = day).copy(relativePath = path, dateTaken = taken)

    @Test
    fun `只有 DCIM 和 Pictures 下的子文件夹算袋子`() {
        assertTrue(AlbumRules.isAlbumPath("DCIM/Camera/"))
        assertTrue(AlbumRules.isAlbumPath("Pictures/旅行/"))
        assertFalse(AlbumRules.isAlbumPath("DCIM/"))
        assertFalse(AlbumRules.isAlbumPath("Download/"))
        assertFalse(AlbumRules.isAlbumPath("Pictures/a|b/"))
    }

    @Test
    fun `相册名不能为空、不能以点开头、不能带路径字符`() {
        assertTrue(AlbumRules.isValidName("秋天的海"))
        assertFalse(AlbumRules.isValidName("  "))
        assertFalse(AlbumRules.isValidName(".hidden"))
        assertFalse(AlbumRules.isValidName("a/b"))
        assertEquals("ab", AlbumRules.sanitizeInput("a/b"))
        assertEquals(AlbumRules.MAX_NAME_LENGTH, AlbumRules.sanitizeInput("x".repeat(100)).length)
        assertEquals("Pictures/旅行/", AlbumRules.pathFor(" 旅行 "))
    }

    @Test
    fun `同名袋子忽略大小写合并`() {
        val albums = listOf(PhotoAlbum("Pictures/Cats/", "Cats", 3, emptyList()))
        assertEquals("Pictures/Cats/", AlbumRules.existingFor("cats", albums)?.relativePath)
        assertNull(AlbumRules.existingFor("dogs", albums))
    }

    @Test
    fun `同名袋子不管在哪个根目录_首尾空白和大小写都不算不同_不另建`() {
        val albums = listOf(
            PhotoAlbum("DCIM/Camera/", "Camera", 20, emptyList()),
            PhotoAlbum("Pictures/旅行/", "旅行", 3, emptyList()),
        )
        assertEquals("DCIM/Camera/", AlbumRules.existingFor("camera", albums)?.relativePath)
        assertEquals("DCIM/Camera/", AlbumRules.existingFor("  CAMERA ", albums)?.relativePath)
        assertEquals("Pictures/旅行/", AlbumRules.existingFor(" 旅行", albums)?.relativePath)
        // 名字只是包含、或者是上一级文件夹，不算同名
        assertNull(AlbumRules.existingFor("Cam", albums))
        assertNull(AlbumRules.existingFor("DCIM", albums))
    }

    @Test
    fun `好几个同名时先放进 Pictures 下那只_否则按托盘顺序取第一只`() {
        val both = listOf(
            PhotoAlbum("DCIM/Cats/", "Cats", 2, emptyList()),
            PhotoAlbum("Pictures/cats/", "cats", 5, emptyList()),
        )
        assertEquals("Pictures/cats/", AlbumRules.existingFor("Cats", both)?.relativePath)
        val nested = listOf(
            PhotoAlbum("DCIM/Trip/Cats/", "Cats", 1, emptyList()),
            PhotoAlbum("DCIM/Cats/", "Cats", 2, emptyList()),
        )
        assertEquals("DCIM/Trip/Cats/", AlbumRules.existingFor("cats", nested)?.relativePath)
    }

    @Test
    fun `托盘按最近装过排序_没装过的按最新一张`() {
        val library = listOf(
            photo(1, "DCIM/Camera/", taken = 300),
            photo(2, "Pictures/旅行/", taken = 100),
            photo(3, "Pictures/猫/", taken = 200),
            photo(4, "Download/", taken = 999),
            photo(5, null, taken = 999),
        )
        val log = listOf(FilingRecord(9, "Pictures/旅行/", "October 24, 2026", filedAt = 50))
        val albums = BuildAlbumsUseCase()(library, log)
        assertEquals(listOf("Pictures/旅行/", "DCIM/Camera/", "Pictures/猫/"), albums.map { it.relativePath })
        assertEquals("旅行", albums[0].name)
        assertEquals(1, albums[0].count)
    }

    @Test
    fun `露角取最新的一张_最多五张`() {
        val library = (1L..7L).map { photo(it, "Pictures/旅行/", taken = it) }
        val album = BuildAlbumsUseCase()(library, emptyList()).single()
        assertEquals(7, album.count)
        assertEquals(listOf(7L, 6L, 5L, 4L, 3L), album.recent.map { it.id })
    }

    @Test
    fun `已冲洗按天合并_只算还在图库里的`() {
        val library = listOf(photo(1, "Pictures/旅行/"), photo(2, "Pictures/旅行/"), photo(3, "Pictures/猫/", day = "October 22, 2026"))
            .associateBy { it.id }
        val log = listOf(
            FilingRecord(1, "Pictures/旅行/", "October 24, 2026", 10),
            FilingRecord(2, "Pictures/旅行/", "October 24, 2026", 10),
            FilingRecord(3, "Pictures/猫/", "October 22, 2026", 20),
            FilingRecord(99, "Pictures/猫/", "October 22, 2026", 30),
        )
        val days = BuildFiledDaysUseCase()(log, library)
        assertEquals(listOf("October 22, 2026", "October 24, 2026"), days.map { it.dayKey })
        assertEquals(1, days[0].count)
        assertEquals(2, days[1].albums.single().count)
        assertEquals(listOf(1L, 2L), days[1].photos.map { it.id })
    }
}

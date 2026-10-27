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
    fun `托盘按最后装进去的时间倒序_没装过的按张数从多到少_再按名字`() {
        val library = listOf(
            photo(1, "DCIM/Camera/", taken = 300),
            photo(2, "Pictures/旅行/", taken = 100),
            photo(3, "Pictures/猫/", taken = 200),
            photo(6, "Pictures/猫/", taken = 201),
            photo(7, "Pictures/Beach/", taken = 1),
            photo(8, "Pictures/Beach/", taken = 2),
            photo(9, "Pictures/秋天/", taken = 50),
            photo(4, "Download/", taken = 999),
            photo(5, null, taken = 999),
        )
        val log = listOf(
            FilingRecord(19, "Pictures/旅行/", "October 24, 2026", filedAt = 50),
            FilingRecord(20, "Pictures/秋天/", "October 24, 2026", filedAt = 80),
            FilingRecord(21, "Pictures/旅行/", "October 24, 2026", filedAt = 40),
        )
        val albums = BuildAlbumsUseCase()(library, log)
        // 秋天 80 → 旅行 50 → 没装过：Beach 2 张、猫 2 张（同张数按名字）→ Camera 1 张（虽然最新一张最近）
        assertEquals(
            listOf("Pictures/秋天/", "Pictures/旅行/", "Pictures/Beach/", "Pictures/猫/", "DCIM/Camera/"),
            albums.map { it.relativePath },
        )
        assertEquals("旅行", albums[1].name)
        assertEquals(1, albums[1].count)
        assertEquals(50L, albums[1].lastFiledAt)
        assertNull(albums[2].lastFiledAt)
    }

    @Test
    fun `撤回删掉记录后按剩下的记录排`() {
        val library = listOf(photo(1, "Pictures/A/"), photo(2, "Pictures/B/"), photo(3, "Pictures/B/"))
        val before = listOf(FilingRecord(1, "Pictures/A/", "d", 10), FilingRecord(9, "Pictures/A/", "d", 30), FilingRecord(2, "Pictures/B/", "d", 20))
        assertEquals(listOf("Pictures/A/", "Pictures/B/"), BuildAlbumsUseCase()(library, before).map { it.relativePath })
        // 撤回了 A 最新那次（filedAt 30）：A 只剩 10，B 的 20 更晚
        val after = before.filterNot { it.filedAt == 30L }
        assertEquals(listOf("Pictures/B/", "Pictures/A/"), BuildAlbumsUseCase()(library, after).map { it.relativePath })
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

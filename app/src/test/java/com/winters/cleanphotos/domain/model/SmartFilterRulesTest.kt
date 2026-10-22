package com.winters.cleanphotos.domain.model

import com.winters.cleanphotos.TestPhotoData
import com.winters.cleanphotos.domain.usecase.FilterPhotosUseCase
import com.winters.cleanphotos.fake.FakeSeenStateRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartFilterRulesTest {

    private fun photo(
        id: Long = 1L,
        relativePath: String? = null,
        bucketName: String? = null,
        size: Long? = 1024L,
        mediaType: MediaType = MediaType.Image,
    ) = TestPhotoData.createTestPhoto(id).copy(
        relativePath = relativePath,
        bucketName = bucketName,
        size = size,
        mediaType = mediaType,
    )

    @Test
    fun `截图_路径或文件夹名命中且不区分大小写`() {
        assertTrue(SmartFilterRules.isScreenshot(photo(relativePath = "Pictures/Screenshots/")))
        assertTrue(SmartFilterRules.isScreenshot(photo(relativePath = "dcim/SCREENSHOT/")))
        assertTrue(SmartFilterRules.isScreenshot(photo(bucketName = "截图")))
        assertTrue(SmartFilterRules.isScreenshot(photo(bucketName = "屏幕截屏")))
        assertTrue(SmartFilterRules.isScreenshot(photo(relativePath = null, bucketName = "My Screenshot")))
        assertFalse(SmartFilterRules.isScreenshot(photo(relativePath = "DCIM/Camera/", bucketName = "Camera")))
        assertFalse(SmartFilterRules.isScreenshot(photo(relativePath = "screen", bucketName = "截")))
        assertFalse(SmartFilterRules.isScreenshot(photo()))
    }

    @Test
    fun `大文件_大于等于10MB_null和不足都不算_图片视频同一阈值`() {
        val min = SmartFilterRules.LARGE_FILE_MIN_BYTES
        assertEquals(10L * 1024 * 1024, min)
        assertTrue(SmartFilterRules.isLargeFile(photo(size = min)))
        assertTrue(SmartFilterRules.isLargeFile(photo(size = min + 1, mediaType = MediaType.Video)))
        assertFalse(SmartFilterRules.isLargeFile(photo(size = min - 1)))
        assertFalse(SmartFilterRules.isLargeFile(photo(size = null)))
        assertFalse(SmartFilterRules.isLargeFile(photo(size = 0)))
    }

    @Test
    fun `聊天软件_路径包含关键字或以Pictures_QQ开头_文件夹名不算`() {
        assertTrue(SmartFilterRules.isChatApp(photo(relativePath = "Pictures/WeiXin/")))
        assertTrue(SmartFilterRules.isChatApp(photo(relativePath = "WhatsApp/Media/")))
        assertTrue(SmartFilterRules.isChatApp(photo(relativePath = "Download/TELEGRAM/")))
        assertTrue(SmartFilterRules.isChatApp(photo(relativePath = "Pictures/QQ/")))
        assertTrue(SmartFilterRules.isChatApp(photo(relativePath = "pictures/qq/images/")))
        assertFalse(SmartFilterRules.isChatApp(photo(relativePath = "Pictures/QQ")))
        assertFalse(SmartFilterRules.isChatApp(photo(relativePath = "Download/QQ/")))
        assertFalse(SmartFilterRules.isChatApp(photo(relativePath = "DCIM/Camera/", bucketName = "WhatsApp")))
        assertFalse(SmartFilterRules.isChatApp(photo(relativePath = null, bucketName = "weixin")))
        assertFalse(SmartFilterRules.isChatApp(photo()))
    }

    @Test
    fun `FilterPhotosUseCase_新模式只留命中项且不看已看`() = runTest {
        val seenShot = photo(1, relativePath = "Pictures/Screenshots/", size = 100)
        val big = photo(2, relativePath = "DCIM/Camera/", size = SmartFilterRules.LARGE_FILE_MIN_BYTES, mediaType = MediaType.Video)
        val chat = photo(3, relativePath = "Pictures/QQ/a.jpg")
        val plain = photo(4, relativePath = "DCIM/Camera/", bucketName = "Camera", size = 100)
        val photos = listOf(seenShot, big, chat, plain)
        val filter = FilterPhotosUseCase(FakeSeenStateRepository())
        val seen = setOf(1L, 2L, 3L)

        assertEquals(listOf(1L), filter(photos, PhotoFilterMode.Screenshots, seen).map { it.id })
        assertEquals(listOf(2L), filter(photos, PhotoFilterMode.LargeFiles, seen).map { it.id })
        assertEquals(listOf(3L), filter(photos, PhotoFilterMode.ChatApps, seen).map { it.id })
        assertEquals(listOf(4L), filter(photos, PhotoFilterMode.UnseenOnly, seen).map { it.id })
        assertEquals(4, filter(photos, PhotoFilterMode.AllPhotos, seen).size)
    }
}

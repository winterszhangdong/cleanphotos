package com.winters.cleanphotos

import android.net.Uri
import com.winters.cleanphotos.domain.model.PhotoItem
import io.mockk.mockk

/**
 * 测试数据工厂。
 *
 * 提供统一的测试照片数据，避免各测试类重复创建。
 * 直接使用 relaxed mock Uri，不依赖 Uri.parse()。
 */
object TestPhotoData {

    /** 创建单张测试照片 */
    fun createTestPhoto(
        id: Long = 1L,
        dateGroup: String = "2024-05-20",
        isFavorite: Boolean = false,
        isTrashed: Boolean = false
    ): PhotoItem {
        return PhotoItem(
            id = id,
            uri = mockk<Uri>(relaxed = true),
            displayName = "photo_$id.jpg",
            dateTaken = 1716163200000L + id * 86400000L,
            dateGroup = dateGroup,
            size = 1024L * 100,
            width = 1080,
            height = 1920,
            isFavorite = isFavorite,
            isTrashed = isTrashed
        )
    }

    /** 创建多张测试照片（同一日期） */
    fun createTestPhotos(count: Int): List<PhotoItem> {
        return (1..count).map { i -> createTestPhoto(id = i.toLong()) }
    }

    /** 创建多日期测试照片 */
    fun createMultiDateTestPhotos(): List<PhotoItem> {
        return listOf(
            // 2024-05-20: 10 张（超过首页每组展示上限 8 张）
            *(1..10).map { createTestPhoto(id = it.toLong(), dateGroup = "2024-05-20") }.toTypedArray(),
            // 2024-05-19: 3 张
            *(11..13).map { createTestPhoto(id = it.toLong(), dateGroup = "2024-05-19") }.toTypedArray(),
            // 2024-05-18: 1 张
            createTestPhoto(id = 14L, dateGroup = "2024-05-18")
        )
    }
}

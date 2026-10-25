package com.winters.cleanphotos.feature.trash

import com.winters.cleanphotos.TestPhotoData.createTestPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 垃圾桶预览的下标：画面、横滑目标、外部删掉后停在哪一张。 */
class TrashPreviewIndexTest {
    private val photos = listOf(createTestPhoto(1), createTestPhoto(2), createTestPhoto(3))

    @Test fun `画面下标优先用还在列表里的本地 id`() {
        assertEquals(1, trashPreviewShownIndex(photos, displayPhotoId = 2, currentPhotoId = 1, displayIndex = 0))
    }

    @Test fun `本地 id 已经不在时改用 ViewModel 的 id`() {
        assertEquals(2, trashPreviewShownIndex(photos, displayPhotoId = 9, currentPhotoId = 3, displayIndex = 0))
    }

    @Test fun `两个 id 都不在时把旧下标夹进列表`() {
        assertEquals(2, trashPreviewShownIndex(photos, displayPhotoId = 9, currentPhotoId = 8, displayIndex = 99))
        assertEquals(0, trashPreviewShownIndex(photos, displayPhotoId = 9, currentPhotoId = 8, displayIndex = -1))
    }

    @Test fun `空列表下标为 0`() {
        assertEquals(0, trashPreviewShownIndex(emptyList(), 1, 1, 5))
    }

    @Test fun `左滑去下一张_右滑去上一张_到头或找不到就回弹`() {
        assertEquals(1, trashPreviewSwipeTarget(photos, photoId = 1, swipeLeft = true, swipeRight = false))
        assertEquals(0, trashPreviewSwipeTarget(photos, photoId = 2, swipeLeft = false, swipeRight = true))
        assertNull(trashPreviewSwipeTarget(photos, photoId = 1, swipeLeft = false, swipeRight = true))
        assertNull(trashPreviewSwipeTarget(photos, photoId = 3, swipeLeft = true, swipeRight = false))
        assertNull(trashPreviewSwipeTarget(photos, photoId = 9, swipeLeft = true, swipeRight = false))
    }

    @Test fun `从列表消失后停在原下标_最后一张则停在新的最后一张`() {
        assertEquals(3L, previewPhotoAfterRemoval(photos, removedId = 2, listOf(photos[0], photos[2]))!!.id)
        assertEquals(2L, previewPhotoAfterRemoval(photos, removedId = 3, listOf(photos[0], photos[1]))!!.id)
        assertEquals(1L, previewPhotoAfterRemoval(photos, removedId = 9, listOf(photos[0], photos[2]))!!.id)
        assertNull(previewPhotoAfterRemoval(photos, removedId = 2, emptyList()))
    }
}

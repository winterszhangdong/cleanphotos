package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoSortOrder
import javax.inject.Inject

/**
 * 对照片列表按排序方式排序。
 */
class SortPhotosUseCase @Inject constructor() {
    operator fun invoke(photos: List<PhotoItem>, sortOrder: PhotoSortOrder): List<PhotoItem> {
        return when (sortOrder) {
            PhotoSortOrder.NewestFirst -> photos.sortedWith(newestFirst)
            PhotoSortOrder.OldestFirst -> photos.sortedWith(oldestFirst)
            PhotoSortOrder.LargestFirst -> photos.sortedWith(largestFirst)
        }
    }

    private companion object {
        /** 没有拍摄时间的排在最后。当成 0 的话，从早到晚会把它们排到最前。 */
        val newestFirst: Comparator<PhotoItem> =
            compareByDescending<PhotoItem> { it.dateTaken != null }.thenByDescending { it.dateTaken }
        val oldestFirst: Comparator<PhotoItem> =
            compareByDescending<PhotoItem> { it.dateTaken != null }.thenBy { it.dateTaken }
        val largestFirst: Comparator<PhotoItem> =
            compareByDescending<PhotoItem> { it.size ?: error("按大小排序的照片没有 size：${it.id}") }
                .thenByDescending { it.dateTaken != null }
                .thenByDescending { it.dateTaken }
    }
}

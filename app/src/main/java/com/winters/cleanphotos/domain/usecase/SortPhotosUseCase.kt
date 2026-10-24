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
            PhotoSortOrder.NewestFirst -> photos.sortedByDescending { it.dateTaken ?: 0L }
            PhotoSortOrder.OldestFirst -> photos.sortedBy { it.dateTaken ?: 0L }
            PhotoSortOrder.LargestFirst -> photos.sortedWith(
                compareByDescending<PhotoItem> { it.size ?: error("按大小排序的照片没有 size：${it.id}") }
                    .thenByDescending { it.dateTaken ?: 0L },
            )
        }
    }
}

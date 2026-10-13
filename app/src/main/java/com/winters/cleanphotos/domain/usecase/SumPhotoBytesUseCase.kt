package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.PhotoItem
import javax.inject.Inject

/** 一组照片的总大小。size 未知按 0 计，与 ObserveTrashSummaryUseCase 一致。 */
class SumPhotoBytesUseCase @Inject constructor() {
    operator fun invoke(photos: List<PhotoItem>, ids: Set<Long>): Long =
        photos.filter { it.id in ids }.sumOf { it.size ?: 0L }
}

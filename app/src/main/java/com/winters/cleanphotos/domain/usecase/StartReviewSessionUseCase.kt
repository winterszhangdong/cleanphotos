package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.PhotoFilterMode
import com.winters.cleanphotos.domain.model.PhotoSortOrder
import com.winters.cleanphotos.domain.model.ReviewScope
import com.winters.cleanphotos.domain.model.ReviewSession
import com.winters.cleanphotos.domain.repository.PhotoRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * 启动清理会话。
 *
 * 根据 scope 决定候选队列来源：
 * - AllPhotos：observeAllPhotos() → 排序 → 过滤 → 定位
 * - Favorites：observeFavoritedPhotos() → 排序 → 定位（跳过过滤）
 */
class StartReviewSessionUseCase @Inject constructor(
    private val photoRepository: PhotoRepository,
    private val sortPhotosUseCase: SortPhotosUseCase,
    private val filterPhotosUseCase: FilterPhotosUseCase
) {
    suspend operator fun invoke(
        startPhotoId: Long,
        sortOrder: PhotoSortOrder,
        filterMode: PhotoFilterMode,
        scope: ReviewScope
    ): ReviewSession {
        val photos = when (scope) {
            ReviewScope.AllPhotos -> {
                val allPhotos = photoRepository.observeAllPhotos().first()
                val sorted = sortPhotosUseCase(allPhotos, sortOrder)
                filterPhotosUseCase(sorted, filterMode)
            }
            ReviewScope.Favorites -> {
                val favoritedPhotos = photoRepository.observeFavoritedPhotos().first()
                sortPhotosUseCase(favoritedPhotos, sortOrder)
            }
        }

        // 在最终列表中查找起始照片
        val startIndex = photos.indexOfFirst { it.id == startPhotoId }
        check(startIndex >= 0) {
            "照片 ID $startPhotoId 不在最终列表中（scope=$scope, sortOrder=$sortOrder, filterMode=$filterMode, listSize=${photos.size}）"
        }

        return ReviewSession(
            photos = photos,
            currentIndex = startIndex,
            totalCount = photos.size,
            lastAction = null
        )
    }
}

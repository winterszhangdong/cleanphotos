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
 * - AllPhotos：observeAllPhotos() → 按范围筛（不叠「只看没看过」）→ 排序 → 需要时再叠「只看没看过」→ 定位
 * - Favorites：observeFavoritedPhotos() → 排序 → 定位（不筛，也不吃「只看没看过」）
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
        scope: ReviewScope,
        unseenOnly: Boolean = false,
    ): ReviewSession {
        // 进程被回收后系统直接重建清理页：照片页还没刷新过，内存照片表是空的，先加载一次
        photoRepository.ensurePhotosLoaded()

        val candidates = when (scope) {
            ReviewScope.AllPhotos -> photoRepository.observeAllPhotos().first()
            ReviewScope.Favorites -> photoRepository.observeFavoritedPhotos().first()
        }
        // 先按范围筛再排。按大小排时库里 size 为 null 的照片不在范围内，不能拿去比较。
        // 收藏范围跳过过滤，也不吃「只看没看过」。
        val ranged = when (scope) {
            ReviewScope.AllPhotos -> filterPhotosUseCase(candidates, filterMode, unseenOnly = false)
            ReviewScope.Favorites -> candidates
        }
        val sorted = sortPhotosUseCase(ranged, sortOrder)
        val photos = when (scope) {
            ReviewScope.AllPhotos -> if (unseenOnly) filterPhotosUseCase(sorted, filterMode, unseenOnly = true) else sorted
            ReviewScope.Favorites -> sorted
        }

        val startIndex = startIndexOf(startPhotoId, photos, sorted)
        check(startIndex >= 0) {
            "照片 ID $startPhotoId 不在最终列表中（scope=$scope, sortOrder=$sortOrder, filterMode=$filterMode, unseenOnly=$unseenOnly, listSize=${photos.size}）"
        }

        return ReviewSession(
            photos = photos,
            currentIndex = startIndex,
            totalCount = photos.size,
            lastAction = null
        )
    }

    /**
     * 起点在最终列表里就用它；不在（例如「只看未看」时起点进入会话就被标为看过，进程重建后被过滤掉）
     * 但仍在排序后的完整列表里：落到它后面最近的一张，后面没有就取它前面最近的一张。都没有返回 -1。
     */
    private fun startIndexOf(startPhotoId: Long, photos: List<com.winters.cleanphotos.domain.model.PhotoItem>, sorted: List<com.winters.cleanphotos.domain.model.PhotoItem>): Int {
        val direct = photos.indexOfFirst { it.id == startPhotoId }
        if (direct >= 0 || photos.isEmpty()) return direct
        val anchor = sorted.indexOfFirst { it.id == startPhotoId }
        if (anchor < 0) return -1
        val rank = HashMap<Long, Int>(sorted.size * 2)
        sorted.forEachIndexed { i, p -> rank[p.id] = i }
        val after = photos.indexOfFirst { (rank[it.id] ?: -1) > anchor }
        return if (after >= 0) after else photos.lastIndex
    }
}

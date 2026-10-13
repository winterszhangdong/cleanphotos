package com.winters.cleanphotos.data.repository

import com.winters.cleanphotos.data.datasource.AppFavoriteStateDataSource
import com.winters.cleanphotos.data.datasource.AppSeenStateDataSource
import com.winters.cleanphotos.data.datasource.AppTrashStateDataSource
import com.winters.cleanphotos.data.datasource.MediaStoreTrashDataSource
import com.winters.cleanphotos.domain.model.AppTrashEntry
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.repository.ReviewRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 清理页仓库实现。
 *
 * 核心语义变更：
 * - favoritePhoto：写入 AppFavoriteStore + markSeen，直接返回 Success（无系统授权）
 * - removeFavorite：移除 AppFavoriteStore 条目 + markSeen，直接返回 Success
 * - movePhotoToTrash：快照 wasFavorited → 写入 AppTrashStore → 移除 AppFavoriteStore 条目 → markSeen
 * - restorePhotoFromTrash：读取垃圾桶快照恢复 AppFavoriteStore → 移除 AppTrashStore 条目 → markSeen
 * - restorePhotosFromTrash：批量恢复收藏快照 + 移除 AppTrashStore 条目
 * - deletePhotosPermanently：走系统授权，成功后清理三 Store
 */
@Singleton
class ReviewRepositoryImpl @Inject constructor(
    private val mediaStoreTrashDataSource: MediaStoreTrashDataSource,
    private val appTrashStateDataSource: AppTrashStateDataSource,
    private val appFavoriteStateDataSource: AppFavoriteStateDataSource,
    private val appSeenStateDataSource: AppSeenStateDataSource
) : ReviewRepository {

    /**
     * 收藏照片。写入 AppFavoriteStore + 标记已看过，直接返回 Success。
     */
    override suspend fun favoritePhoto(photo: PhotoItem): PhotoOperationResult {
        appFavoriteStateDataSource.upsertEntry(photo.id, System.currentTimeMillis())
        appSeenStateDataSource.markSeen(photo.id)
        return PhotoOperationResult.Success
    }

    /**
     * 取消收藏。移除 AppFavoriteStore 条目 + 标记已看过，直接返回 Success。
     */
    override suspend fun removeFavorite(photo: PhotoItem): PhotoOperationResult {
        appFavoriteStateDataSource.removeEntry(photo.id)
        appSeenStateDataSource.markSeen(photo.id)
        return PhotoOperationResult.Success
    }

    /**
     * 移入 App 内垃圾桶。
     * 快照当前收藏状态 → 写入垃圾桶 → 移除收藏条目 → 标记已看过。
     */
    override suspend fun movePhotoToTrash(photo: PhotoItem): PhotoOperationResult {
        // 读取当前收藏条目（如果有），用于快照
        val currentFavorite = appFavoriteStateDataSource.observeEntries().first()
            .find { it.photoId == photo.id }

        val wasFavorited = currentFavorite != null
        val favoritedAt = currentFavorite?.favoritedAt

        // 写入垃圾桶（含收藏快照）
        appTrashStateDataSource.upsertEntry(
            photoId = photo.id,
            movedToTrashAt = System.currentTimeMillis(),
            wasFavorited = wasFavorited,
            favoritedAt = favoritedAt
        )

        // 移除收藏条目（如果存在）
        if (wasFavorited) {
            appFavoriteStateDataSource.removeEntry(photo.id)
        }

        appSeenStateDataSource.markSeen(photo.id)
        return PhotoOperationResult.Success
    }

    /**
     * 从 App 内垃圾桶恢复单张照片。
     * 读取快照恢复收藏 → 移除垃圾桶条目 → 标记已看过。
     */
    override suspend fun restorePhotoFromTrash(photo: PhotoItem): PhotoOperationResult {
        // 读取垃圾桶条目的收藏快照
        val trashEntry = appTrashStateDataSource.observeEntries().first()
            .find { it.photoId == photo.id }

        // 恢复收藏快照
        if (trashEntry != null && trashEntry.wasFavorited && trashEntry.favoritedAt != null) {
            appFavoriteStateDataSource.upsertEntry(photo.id, trashEntry.favoritedAt)
        }

        // 移除垃圾桶条目
        appTrashStateDataSource.removeEntry(photo.id)
        appSeenStateDataSource.markSeen(photo.id)
        return PhotoOperationResult.Success
    }

    /**
     * 从 App 内垃圾桶批量恢复照片。
     * 批量恢复收藏快照 + 移除垃圾桶条目。
     */
    override suspend fun restorePhotosFromTrash(photos: List<PhotoItem>): PhotoOperationResult {
        val photoIds = photos.map { it.id }.toSet()

        // 读取所有垃圾桶条目的收藏快照
        val trashEntries = appTrashStateDataSource.observeEntries().first()

        // 恢复收藏快照（一次写入）
        appFavoriteStateDataSource.upsertEntries(
            trashEntries
                .filter { it.photoId in photoIds && it.wasFavorited && it.favoritedAt != null }
                .associate { it.photoId to it.favoritedAt!! }
        )

        // 批量移除垃圾桶条目 + 批量标记已看过
        appTrashStateDataSource.removeEntries(photoIds)
        appSeenStateDataSource.markSeen(photoIds)
        return PhotoOperationResult.Success
    }

    /**
     * 永久删除照片。
     * 继续使用系统授权流程（MediaStore.createDeleteRequest）。
     * 成功后清理三 Store。
     */
    override suspend fun deletePhotosPermanently(photos: List<PhotoItem>): PhotoOperationResult {
        // createDeleteRequest / delete 是 binder 调用，整段放进 IO
        val result = withContext(Dispatchers.IO) { mediaStoreTrashDataSource.deletePermanently(photos) }
        if (result is PhotoOperationResult.Success) {
            val photoIds = photos.map { it.id }.toSet()
            appFavoriteStateDataSource.removeEntries(photoIds)
            appSeenStateDataSource.removeEntries(photoIds)
            appTrashStateDataSource.removeEntries(photoIds)
        }
        return result
    }
}

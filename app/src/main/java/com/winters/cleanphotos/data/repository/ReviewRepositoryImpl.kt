package com.winters.cleanphotos.data.repository

import com.winters.cleanphotos.data.datasource.AppFavoriteStateDataSource
import com.winters.cleanphotos.data.datasource.AppSeenStateDataSource
import com.winters.cleanphotos.data.datasource.AppTrashStateDataSource
import com.winters.cleanphotos.data.datasource.MediaStoreTrashDataSource
import com.winters.cleanphotos.domain.model.AppTrashEntry
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.model.SwipeSideEffects
import com.winters.cleanphotos.data.preferences.UserPreferencesDataSource
import androidx.datastore.preferences.core.MutablePreferences
import com.winters.cleanphotos.domain.repository.ReviewRepository
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
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
    private val appSeenStateDataSource: AppSeenStateDataSource,
    private val userPreferencesDataSource: UserPreferencesDataSource,
) : ReviewRepository {

    /**
     * 收藏照片。写入 AppFavoriteStore + 标记已看过（一次写入），直接返回 Success。
     */
    override suspend fun favoritePhoto(photo: PhotoItem, effects: SwipeSideEffects): PhotoOperationResult {
        val now = System.currentTimeMillis()
        editStores { p ->
            appFavoriteStateDataSource.upsertIn(p, mapOf(photo.id to now))
            appSeenStateDataSource.markSeenIn(p, setOf(photo.id))
            applyEffects(p, effects)
        }
        return PhotoOperationResult.Success
    }

    /**
     * 取消收藏。移除 AppFavoriteStore 条目 + 标记已看过（一次写入），直接返回 Success。
     */
    override suspend fun removeFavorite(photo: PhotoItem, effects: SwipeSideEffects): PhotoOperationResult {
        editStores { p ->
            appFavoriteStateDataSource.removeIn(p, setOf(photo.id))
            appSeenStateDataSource.markSeenIn(p, setOf(photo.id))
            applyEffects(p, effects)
        }
        return PhotoOperationResult.Success
    }

    /**
     * 移入 App 内垃圾桶。
     * 快照当前收藏状态 → 写入垃圾桶 → 移除收藏条目 → 标记已看过，在同一次 edit 里完成（读改写原子、只写一次文件）。
     */
    override suspend fun movePhotoToTrash(photo: PhotoItem, effects: SwipeSideEffects): PhotoOperationResult {
        val now = System.currentTimeMillis()
        editStores { p ->
            val currentFavorite = appFavoriteStateDataSource.entriesIn(p).find { it.photoId == photo.id }
            appTrashStateDataSource.upsertIn(
                p,
                listOf(
                    AppTrashEntry(
                        photoId = photo.id,
                        movedToTrashAt = now,
                        wasFavorited = currentFavorite != null,
                        favoritedAt = currentFavorite?.favoritedAt,
                    ),
                ),
            )
            if (currentFavorite != null) appFavoriteStateDataSource.removeIn(p, setOf(photo.id))
            appSeenStateDataSource.markSeenIn(p, setOf(photo.id))
            applyEffects(p, effects)
        }
        return PhotoOperationResult.Success
    }

    /**
     * 从 App 内垃圾桶恢复单张照片。
     * 读取快照恢复收藏 → 移除垃圾桶条目 → 标记已看过（一次写入）。
     */
    override suspend fun restorePhotoFromTrash(photo: PhotoItem): PhotoOperationResult {
        restoreInOneEdit(setOf(photo.id))
        return PhotoOperationResult.Success
    }

    /**
     * 从 App 内垃圾桶批量恢复照片。
     * 批量恢复收藏快照 + 移除垃圾桶条目 + 标记已看过（一次写入）。
     */
    override suspend fun restorePhotosFromTrash(photos: List<PhotoItem>): PhotoOperationResult {
        restoreInOneEdit(photos.mapTo(HashSet()) { it.id })
        return PhotoOperationResult.Success
    }

    private suspend fun restoreInOneEdit(photoIds: Set<Long>) {
        if (photoIds.isEmpty()) return
        editStores { p ->
            val snapshots = appTrashStateDataSource.entriesIn(p)
                .filter { it.photoId in photoIds && it.wasFavorited && it.favoritedAt != null }
                .associate { it.photoId to it.favoritedAt!! }
            appFavoriteStateDataSource.upsertIn(p, snapshots)
            appTrashStateDataSource.removeIn(p, photoIds)
            appSeenStateDataSource.markSeenIn(p, photoIds)
        }
    }

    override suspend fun favoritePhotos(photos: List<PhotoItem>): PhotoOperationResult {
        val now = System.currentTimeMillis()
        appFavoriteStateDataSource.upsertEntries(photos.associate { it.id to now })
        return PhotoOperationResult.Success
    }

    override suspend fun removeFavorites(photos: List<PhotoItem>): PhotoOperationResult {
        appFavoriteStateDataSource.removeEntries(photos.map { it.id }.toSet())
        return PhotoOperationResult.Success
    }

    /**
     * 批量移入垃圾桶：和单张一样把收藏状态快照进垃圾桶条目再移除收藏条目，
     * 所以收藏页看不到它，从垃圾桶恢复时收藏跟着回来。快照和写入在同一次 edit 里。
     */
    override suspend fun movePhotosToTrash(photos: List<PhotoItem>): PhotoOperationResult {
        if (photos.isEmpty()) return PhotoOperationResult.Success
        val now = System.currentTimeMillis()
        editStores { p ->
            val favorites = appFavoriteStateDataSource.entriesIn(p).associateBy { it.photoId }
            appTrashStateDataSource.upsertIn(
                p,
                photos.map { photo ->
                    val fav = favorites[photo.id]
                    AppTrashEntry(photoId = photo.id, movedToTrashAt = now, wasFavorited = fav != null, favoritedAt = fav?.favoritedAt)
                },
            )
            appFavoriteStateDataSource.removeIn(p, photos.map { it.id }.filter { it in favorites }.toSet())
        }
        return PhotoOperationResult.Success
    }

    /** 滑动附带的写入：下一张看过 + 滑动计数，放在操作的同一次 edit 里。 */
    private fun applyEffects(p: MutablePreferences, effects: SwipeSideEffects) {
        effects.markSeenId?.let { appSeenStateDataSource.markSeenIn(p, setOf(it)) }
        if (effects.countSwipe) userPreferencesDataSource.incrementSwipeActionCountIn(p)
    }

    /** 收藏 / 看过 / 垃圾桶三个数据源共用同一个 DataStore：一次操作的所有改动合成一次 edit。 */
    private suspend fun editStores(block: (MutablePreferences) -> Unit) {
        appTrashStateDataSource.dataStore.edit { block(it) }
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
            editStores { p -> removeAllStateIn(p, photoIds) }
        }
        return result
    }

    override suspend fun forgetDeletedTrashEntries(photoIds: Set<Long>) {
        if (photoIds.isEmpty()) return
        editStores { p ->
            val stillTrashed = appTrashStateDataSource.entriesIn(p).mapNotNullTo(HashSet()) { e -> e.photoId.takeIf { it in photoIds } }
            if (stillTrashed.isNotEmpty()) removeAllStateIn(p, stillTrashed)
        }
    }

    private fun removeAllStateIn(p: MutablePreferences, photoIds: Set<Long>) {
        appFavoriteStateDataSource.removeIn(p, photoIds)
        appSeenStateDataSource.removeIn(p, photoIds)
        appTrashStateDataSource.removeIn(p, photoIds)
    }
}

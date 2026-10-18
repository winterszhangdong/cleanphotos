package com.winters.cleanphotos.domain.repository

import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.model.SwipeSideEffects

interface ReviewRepository {
    /** [effects]：清理页滑动附带的看过 / 计数写入，和操作在同一次写入里完成（失败则都不写）。 */
    suspend fun favoritePhoto(photo: PhotoItem, effects: SwipeSideEffects = SwipeSideEffects.None): PhotoOperationResult
    suspend fun removeFavorite(photo: PhotoItem, effects: SwipeSideEffects = SwipeSideEffects.None): PhotoOperationResult
    suspend fun movePhotoToTrash(photo: PhotoItem, effects: SwipeSideEffects = SwipeSideEffects.None): PhotoOperationResult
    suspend fun restorePhotoFromTrash(photo: PhotoItem): PhotoOperationResult
    suspend fun restorePhotosFromTrash(photos: List<PhotoItem>): PhotoOperationResult
    suspend fun deletePhotosPermanently(photos: List<PhotoItem>): PhotoOperationResult
    /**
     * 清空垃圾桶后确认已从图库消失的照片：清掉它们的垃圾桶 / 收藏 / 看过记录。
     * 只处理此刻仍在垃圾桶里的 id（期间被恢复的不动）。系统授权删除和部分照片权限下，刷新时的失效清理不会跑，靠这里收尾。
     */
    suspend fun forgetDeletedTrashEntries(photoIds: Set<Long>)

    /** 批量收藏：收藏 Store 一次写入。不标记看过（由 UseCase 统一批量标记）。 */
    suspend fun favoritePhotos(photos: List<PhotoItem>): PhotoOperationResult
    /** 批量取消收藏：收藏 Store 一次写入。不标记看过。 */
    suspend fun removeFavorites(photos: List<PhotoItem>): PhotoOperationResult
    /** 批量移入 App 内垃圾桶：逐张快照收藏状态后垃圾桶 Store 一次写入、收藏 Store 一次移除。不标记看过。 */
    suspend fun movePhotosToTrash(photos: List<PhotoItem>): PhotoOperationResult
}

package com.winters.cleanphotos.domain.repository

import com.winters.cleanphotos.domain.model.AppTrashEntry
import com.winters.cleanphotos.domain.model.PhotoItem
import kotlinx.coroutines.flow.Flow

interface PhotoRepository {
    /** 返回不在 App 垃圾桶且不在 App 收藏中的照片 */
    fun observeAllPhotos(): Flow<List<PhotoItem>>
    /** 返回在 App 收藏中且不在 App 垃圾桶中的照片 */
    fun observeFavoritedPhotos(): Flow<List<PhotoItem>>
    suspend fun refreshPhotos()
    /** 回到前台时刷新：距上次刷新不足 [minIntervalMs] 时跳过。 */
    suspend fun refreshPhotosIfStale(minIntervalMs: Long) = refreshPhotos()
    /** 本进程还没加载过照片表时先加载（进程被回收后直接重建清理页）。 */
    suspend fun ensurePhotosLoaded() {}
    fun observeTrashedPhotos(): Flow<List<PhotoItem>>
    suspend fun refreshTrashedPhotos()
    /** App 垃圾桶条目（含移入时间）。 */
    fun observeTrashEntries(): Flow<List<AppTrashEntry>>
}

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
    fun observeTrashedPhotos(): Flow<List<PhotoItem>>
    suspend fun refreshTrashedPhotos()
    /** App 垃圾桶条目（含移入时间）。 */
    fun observeTrashEntries(): Flow<List<AppTrashEntry>>
}

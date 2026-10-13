package com.winters.cleanphotos.fake

import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.repository.PhotoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Fake PhotoRepository 用于测试。
 *
 * 内部使用 MutableStateFlow 模拟数据变化，
 * 支持设置异常来测试失败路径。
 */
class FakePhotoRepository : PhotoRepository {

    private val photosFlow = MutableStateFlow<List<PhotoItem>>(emptyList())
    private val trashedPhotosFlow = MutableStateFlow<List<PhotoItem>>(emptyList())

    /** 设置是否在 refreshPhotos 时抛出异常 */
    var shouldThrowOnRefresh: Boolean = false

    /** 预设照片列表 */
    fun setPhotos(photos: List<PhotoItem>) {
        photosFlow.value = photos
    }

    /** 预设垃圾桶照片列表 */
    fun setTrashedPhotos(photos: List<PhotoItem>) {
        trashedPhotosFlow.value = photos
    }

    override fun observeAllPhotos(): Flow<List<PhotoItem>> = photosFlow

    override suspend fun refreshPhotos() {
        if (shouldThrowOnRefresh) {
            throw RuntimeException("查询照片失败")
        }
        // Fake 实现：不做额外操作，数据通过 setPhotos 设置
    }

    /** 预设收藏照片列表 */
    fun setFavoritedPhotos(photos: List<PhotoItem>) {
        favoritedPhotosFlow.value = photos
    }

    private val favoritedPhotosFlow = MutableStateFlow<List<PhotoItem>>(emptyList())

    override fun observeFavoritedPhotos(): Flow<List<PhotoItem>> = favoritedPhotosFlow

    val trashEntriesFlow = MutableStateFlow<List<com.winters.cleanphotos.domain.model.AppTrashEntry>>(emptyList())
    override fun observeTrashEntries(): Flow<List<com.winters.cleanphotos.domain.model.AppTrashEntry>> = trashEntriesFlow

    override fun observeTrashedPhotos(): Flow<List<PhotoItem>> = trashedPhotosFlow

    override suspend fun refreshTrashedPhotos() {
        if (shouldThrowOnRefresh) {
            throw RuntimeException("查询垃圾桶失败")
        }
    }
}

package com.winters.cleanphotos.fake

import com.winters.cleanphotos.domain.model.FilingOutcome
import com.winters.cleanphotos.domain.model.FilingRecord
import com.winters.cleanphotos.domain.model.MovedPhoto
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.repository.AlbumRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** 装袋仓库的假实现：移动结果由 [failIds] 决定，记录写进 [log]。 */
class FakeAlbumRepository : AlbumRepository {
    val log = MutableStateFlow<List<FilingRecord>>(emptyList())
    var prepareResult: PhotoOperationResult = PhotoOperationResult.Success
    var failIds: Set<Long> = emptySet()
    val filed = mutableListOf<Pair<List<Long>, String>>()
    val undone = mutableListOf<FilingOutcome>()
    /** 装袋成功后回调（测试里用来把照片从收藏里拿掉）。 */
    var onFiled: (List<PhotoItem>) -> Unit = {}

    override fun observeFilingLog(): Flow<List<FilingRecord>> = log

    /** 每次授权检查传进来的照片 id。 */
    val prepared = mutableListOf<List<Long>>()

    override suspend fun prepareMove(photos: List<PhotoItem>): PhotoOperationResult {
        prepared += photos.map { it.id }
        return prepareResult
    }

    override suspend fun fileToAlbum(photos: List<PhotoItem>, relativePath: String): FilingOutcome {
        filed += photos.map { it.id } to relativePath
        val (failed, ok) = photos.partition { it.id in failIds }
        onFiled(ok)
        return FilingOutcome(relativePath, ok.map { MovedPhoto(it, it.relativePath ?: "DCIM/Camera/") }, failed, ok.associate { it.id to 1L })
    }

    override suspend fun undoFiling(outcome: FilingOutcome): List<PhotoItem> {
        undone += outcome
        return emptyList()
    }

    override suspend fun systemFavoriteRequest(photos: List<PhotoItem>): PhotoOperationResult? = null
}

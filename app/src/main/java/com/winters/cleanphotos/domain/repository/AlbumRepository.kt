package com.winters.cleanphotos.domain.repository

import com.winters.cleanphotos.domain.model.FilingOutcome
import com.winters.cleanphotos.domain.model.FilingRecord
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import kotlinx.coroutines.flow.Flow

/** 装袋：把收藏移进系统相册文件夹，并记下「已冲洗」。 */
interface AlbumRepository {
    /** 按装袋时间倒序。 */
    fun observeFilingLog(): Flow<List<FilingRecord>>

    /**
     * 移动前的授权检查。Success = 可以直接移动；NeedSystemApproval = 先启动系统授权（Android 11+）；
     * Failure(WritePermissionRequired) = Android 8–10 还没有存储写权限。
     */
    suspend fun prepareMove(photos: List<PhotoItem>): PhotoOperationResult

    /** 移动 + 移出收藏 + 记录，一次完成；只记移动成功的。 */
    suspend fun fileToAlbum(photos: List<PhotoItem>, relativePath: String): FilingOutcome

    /** 撤回 [outcome]：移回原文件夹、写回收藏时间、删掉记录。返回移回失败的照片。 */
    suspend fun undoFiling(outcome: FilingOutcome): List<PhotoItem>

    /** Android 11+ 系统收藏的确认请求；更低版本没有系统收藏，返回 null。 */
    suspend fun systemFavoriteRequest(photos: List<PhotoItem>): PhotoOperationResult?
}

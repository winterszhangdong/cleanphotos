package com.winters.cleanphotos.domain.model

import android.content.IntentSender

/**
 * 照片业务操作结果。
 *
 * 收藏、取消收藏、移动到垃圾桶、从垃圾桶恢复、永久删除都可能因为系统媒体权限而需要额外授权，
 * 所以 Repository 和 UseCase 不能只用 Unit 表示成功。
 */
sealed interface PhotoOperationResult {
    /** 系统操作已完成，可以更新清理队列 */
    data object Success : PhotoOperationResult

    /** 系统要求用户授权媒体修改，页面需要启动系统授权流程 */
    data class NeedSystemApproval(
        val request: SystemMediaActionRequest
    ) : PhotoOperationResult

    /** 操作失败，不能切换照片 */
    data class Failure(
        val reason: PhotoOperationFailureReason
    ) : PhotoOperationResult
}

/**
 * 系统媒体操作类型
 */
enum class SystemMediaActionType {
    MoveToTrash,
    RestoreFromTrash,
    /** 永久删除（仅用于清空垃圾桶） */
    DeletePermanently,
    /** 装进相册前拿写权限（Android 11+ createWriteRequest，整批一次） */
    MoveToAlbum,
    /** 装袋时顺带加系统收藏（Android 11+ createFavoriteRequest，系统确认后自己写入） */
    SystemFavorite,
}

/**
 * 系统媒体操作请求，包含启动系统授权所需的 IntentSender。
 *
 * data 层通过 MediaStore 生成 PendingIntent/IntentSender，
 * ViewModel 只转发给 UI 层启动，Compose 不直接访问 MediaStore。
 *
 * 支持批量操作：单张操作时 photos = listOf(photo)。
 */
data class SystemMediaActionRequest(
    val photos: List<PhotoItem>,
    val actionType: SystemMediaActionType,
    val requestToken: String,
    val intentSender: IntentSender
)

/**
 * 操作失败原因
 */
enum class PhotoOperationFailureReason {
    /** 当前系统版本或媒体提供方不支持系统收藏 */
    SystemFavoriteUnsupported,
    /** 没有足够权限 */
    PermissionDenied,
    /** 用户取消系统授权 */
    UserCancelled,
    /** MediaStore 操作失败 */
    MediaStoreFailed,
    /** Android 8–10 永久删除前需要先拿到 WRITE_EXTERNAL_STORAGE（页面申请到权限后重试） */
    WritePermissionRequired
}

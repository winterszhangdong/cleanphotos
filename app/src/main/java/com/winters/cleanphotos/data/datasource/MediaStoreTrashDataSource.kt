package com.winters.cleanphotos.data.datasource

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.winters.cleanphotos.core.permission.PermissionChecker
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationFailureReason
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.model.SystemMediaActionRequest
import com.winters.cleanphotos.domain.model.SystemMediaActionType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通过 MediaStore 操作垃圾桶相关功能。
 *
 * 职责：永久删除（批量）。移入 / 移出垃圾桶是 App 内语义，不碰 MediaStore（lessons 2026-05-06）。
 * Android 11+ 通过 MediaStore.createDeleteRequest 获取系统授权 IntentSender；
 * Android 8–10 先要 WRITE_EXTERNAL_STORAGE（没有时返回 WritePermissionRequired，由页面申请后重试），再直接删除。
 */
@Singleton
class MediaStoreTrashDataSource @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val contentResolver: ContentResolver get() = context.contentResolver

    private companion object {
        const val TAG = "MediaStoreTrash"
    }

    /**
     * 批量永久删除照片。
     */
    fun deletePermanently(photos: List<PhotoItem>): PhotoOperationResult {
        if (photos.isEmpty()) return PhotoOperationResult.Success

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ 必须通过 createDeleteRequest 获取系统授权
                createDeleteRequest(photos)
            } else if (!PermissionChecker.hasLegacyWritePermission(context)) {
                // Android 8–10：没有写权限时 delete 只能删本 App 建的条目，其余照片静默删不掉
                PhotoOperationResult.Failure(PhotoOperationFailureReason.WritePermissionRequired)
            } else {
                // Android 8–10 有写权限后直接删除（10 依赖 requestLegacyExternalStorage）
                deleteDirectly(photos)
            }
        } catch (e: Exception) {
            PhotoOperationResult.Failure(PhotoOperationFailureReason.MediaStoreFailed)
        }
    }

    /**
     * 直接删除照片（Android 11 以下）。
     */
    private fun deleteDirectly(photos: List<PhotoItem>): PhotoOperationResult {
        // 单张失败（文件被占用、已被别的 App 删掉）不中断后面的：能删的都删掉，结果按「有没有删不掉的」算；
        // 调用方失败后也会刷新列表并清掉已经消失的照片（TrashViewModel）
        var allDeleted = true
        for (photo in photos) {
            val deleted = try {
                contentResolver.delete(photo.uri, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "删除照片 ${photo.id} 失败", e)
                0
            }
            if (deleted <= 0) allDeleted = false
        }
        return if (allDeleted) {
            PhotoOperationResult.Success
        } else {
            PhotoOperationResult.Failure(PhotoOperationFailureReason.MediaStoreFailed)
        }
    }

    /**
     * 通过 MediaStore.createDeleteRequest 获取永久删除的系统授权 IntentSender。
     * Android 11+ 使用系统级删除确认。
     */
    private fun createDeleteRequest(photos: List<PhotoItem>): PhotoOperationResult {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val uris = photos.map { it.uri }
                val pendingIntent = MediaStore.createDeleteRequest(
                    contentResolver,
                    uris
                )
                PhotoOperationResult.NeedSystemApproval(
                    SystemMediaActionRequest(
                        photos = photos,
                        actionType = SystemMediaActionType.DeletePermanently,
                        requestToken = UUID.randomUUID().toString(),
                        intentSender = pendingIntent.intentSender
                    )
                )
            } else {
                PhotoOperationResult.Failure(PhotoOperationFailureReason.MediaStoreFailed)
            }
        } catch (e: Exception) {
            PhotoOperationResult.Failure(PhotoOperationFailureReason.MediaStoreFailed)
        }
    }
}

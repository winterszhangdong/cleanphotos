package com.winters.cleanphotos.core.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionChecker {
    /** 完整访问，或 Android 14+ 的「只选部分照片」都算可用。 */
    fun hasPhotoPermission(context: Context): Boolean {
        if (granted(context, getRequiredPermission())) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }

    /** 完整访问：查询结果就是整个图库，才能据此判断哪些条目已被外部删除。 */
    fun hasFullPhotoAccess(context: Context): Boolean = granted(context, getRequiredPermission())

    fun getRequiredPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    /** 系统授权弹窗请求的权限组；Android 14+ 带上部分访问，系统弹窗会给出「选择照片」。 */
    fun requestPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else {
            arrayOf(getRequiredPermission())
        }

    /**
     * Android 8–10 永久删除别的 App 拍的照片需要的运行时权限；11+ 走 createDeleteRequest，返回 null。
     * Android 10 配合清单里的 requestLegacyExternalStorage，否则要逐张处理 RecoverableSecurityException。
     */
    val legacyWritePermission: String?
        get() = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) Manifest.permission.WRITE_EXTERNAL_STORAGE else null

    fun hasLegacyWritePermission(context: Context): Boolean =
        legacyWritePermission?.let { granted(context, it) } ?: true

    /** 「只选部分照片」只在 Android 14+ 出现。 */
    val supportsPartialAccess: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

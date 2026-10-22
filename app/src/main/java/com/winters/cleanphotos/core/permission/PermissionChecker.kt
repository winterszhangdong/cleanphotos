package com.winters.cleanphotos.core.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

object PermissionChecker {
    /** 完整访问，或 Android 14+ 的「只选部分照片」都算可用。仍只看图片权限，不要求视频。 */
    fun hasPhotoPermission(context: Context): Boolean =
        isPhotoPermissionGranted(Build.VERSION.SDK_INT) { granted(context, it) }

    /**
     * 完整访问：查询结果就是整个图库，才能据此判断哪些条目已被外部删除。
     * Android 13+ 必须图片和视频都已授权，否则查询不含视频，失效清理会误删视频的收藏 / 看过 / 垃圾桶记录。
     */
    fun hasFullPhotoAccess(context: Context): Boolean =
        isFullPhotoAccess(Build.VERSION.SDK_INT) { granted(context, it) }

    /** 能否查询视频表。Android 14 的部分访问也能读到用户选中的视频。 */
    fun canReadVideos(context: Context): Boolean =
        isVideoReadable(Build.VERSION.SDK_INT) { granted(context, it) }

    fun getRequiredPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    /** 系统授权弹窗请求的权限组；Android 13+ 带上视频，14+ 再带部分访问。 */
    fun requestPermissions(): Array<String> = permissionsToRequest(Build.VERSION.SDK_INT)

    /**
     * 已有图片权限、还没有视频权限时，本进程第一次返回要补请求的视频权限，之后返回 null。
     * 只应由「冷启动进入照片页时已经有图片权限」的路径调用。系统拒绝两次后自己不再弹。
     */
    fun consumeMissingVideoPermissionRequest(context: Context): Array<String>? {
        val missing = missingVideoPermissions(Build.VERSION.SDK_INT) { granted(context, it) } ?: return null
        if (!missingVideoPrompted.compareAndSet(false, true)) return null
        return missing
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

    private val missingVideoPrompted = AtomicBoolean(false)

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

internal fun isPhotoPermissionGranted(sdkInt: Int, isGranted: (String) -> Boolean): Boolean {
    if (isGranted(requiredPermission(sdkInt))) return true
    return sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
}

internal fun isFullPhotoAccess(sdkInt: Int, isGranted: (String) -> Boolean): Boolean {
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        return isGranted(Manifest.permission.READ_MEDIA_IMAGES) &&
            isGranted(Manifest.permission.READ_MEDIA_VIDEO)
    }
    return isGranted(Manifest.permission.READ_EXTERNAL_STORAGE)
}

internal fun isVideoReadable(sdkInt: Int, isGranted: (String) -> Boolean): Boolean {
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        if (isGranted(Manifest.permission.READ_MEDIA_VIDEO)) return true
        return sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }
    return isGranted(Manifest.permission.READ_EXTERNAL_STORAGE)
}

internal fun permissionsToRequest(sdkInt: Int): Array<String> =
    when {
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        sdkInt >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

/** 已授权图片、缺视频权限时要补的权限；否则 null。部分访问不算「已授权图片」。 */
internal fun missingVideoPermissions(sdkInt: Int, isGranted: (String) -> Boolean): Array<String>? {
    if (sdkInt < Build.VERSION_CODES.TIRAMISU) return null
    if (!isGranted(Manifest.permission.READ_MEDIA_IMAGES)) return null
    if (isGranted(Manifest.permission.READ_MEDIA_VIDEO)) return null
    return arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
}

private fun requiredPermission(sdkInt: Int): String =
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

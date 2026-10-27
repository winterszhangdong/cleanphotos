package com.winters.cleanphotos.data.datasource

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import com.winters.cleanphotos.domain.model.PhotoItem
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把照片移到另一个相册文件夹（装袋 / 撤回装袋），以及装袋时顺带写系统收藏。
 *
 * - Android 11+：先用 createWriteRequest 整批拿写权限（已经拿到的不再要），再改 RELATIVE_PATH，文件随之移动、_ID 不变。
 * - Android 10：清单里 requestLegacyExternalStorage + WRITE_EXTERNAL_STORAGE，直接改 RELATIVE_PATH，不弹窗。
 * - Android 8–9：没有 RELATIVE_PATH：重命名文件，再把新路径写回 DATA（同一行，_ID 不变）。
 */
@Singleton
class MediaStoreAlbumDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val contentResolver: ContentResolver get() = context.contentResolver

    /** Android 11+ 里还没有写权限的那些照片的整批授权；全都有了或系统低于 11 时返回 null。 */
    fun writeRequest(photos: List<PhotoItem>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val pid = Process.myPid()
        val uid = Process.myUid()
        val missing = photos.map { it.uri }.filter {
            context.checkUriPermission(it, pid, uid, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return null
        return MediaStore.createWriteRequest(contentResolver, missing).intentSender
    }

    /** 系统收藏：系统确认后由系统自己写入。 */
    @RequiresApi(Build.VERSION_CODES.R)
    fun favoriteRequest(photos: List<PhotoItem>): IntentSender =
        MediaStore.createFavoriteRequest(contentResolver, photos.map { it.uri }, true).intentSender

    /**
     * 把 [photo] 移到 [targetPath]（RELATIVE_PATH 格式）。已经在那里时直接算成功。
     * 目标有同名文件时另起「名字 (1).jpg」，不覆盖（Android 10+ 由媒体库起名，8–9 见 [freeFileName]）。
     * 失败（文件已不在、没有权限、目录不允许）返回 false，不抛错：一批里其余照片照常移动。
     */
    fun move(photo: PhotoItem, targetPath: String): Boolean {
        if (photo.relativePath.equals(targetPath, ignoreCase = true)) return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) moveByRelativePath(photo, targetPath)
            else moveByRename(photo, targetPath)
        } catch (e: Exception) {
            // SecurityException（授权被收回）、IllegalArgumentException / IllegalStateException（同名、目录不允许）
            Log.w(TAG, "移动照片 ${photo.id} 到 $targetPath 失败", e)
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun moveByRelativePath(photo: PhotoItem, targetPath: String): Boolean {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, targetPath) }
        if (contentResolver.update(photo.uri, values, null, null) <= 0) return false
        // 有的系统重名时不报错、也不移动：读回来确认真的到了目标文件夹
        return currentRelativePath(photo)?.equals(targetPath, ignoreCase = true) == true
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun currentRelativePath(photo: PhotoItem): String? =
        contentResolver.query(photo.uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use {
            if (it.moveToFirst()) normalizeRelativePath(it.getString(0)) else null
        }

    @Suppress("DEPRECATION") // Android 8–9 只有 DATA
    private fun moveByRename(photo: PhotoItem, targetPath: String): Boolean {
        val dataPath = contentResolver.query(photo.uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: return false
        val source = File(dataPath)
        if (!source.isFile) return false
        val dir = File(Environment.getExternalStorageDirectory(), targetPath)
        if (!dir.isDirectory && !dir.mkdirs()) return false
        // 目标有同名文件时和 Android 10+ 媒体库一样另起「名字 (1).jpg」，绝不覆盖
        val target = File(dir, freeFileName(source.name) { File(dir, it).exists() })
        if (!source.renameTo(target)) return false
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DATA, target.path)
            put(MediaStore.MediaColumns.DISPLAY_NAME, target.name)
        }
        val updated = try {
            contentResolver.update(photo.uri, values, null, null) > 0
        } catch (e: Exception) {
            Log.w(TAG, "移动照片 ${photo.id} 后更新媒体库失败，文件挪回原处", e)
            false
        }
        if (updated) return true
        // 媒体库没跟上：把文件挪回去，别让照片在图库里「消失」
        if (!target.renameTo(source)) Log.e(TAG, "照片 ${photo.id} 挪回失败，文件留在 ${target.path}")
        return false
    }

    private companion object {
        const val TAG = "MediaStoreAlbum"
    }
}

/**
 * [name] 在目标文件夹里已被占用（[taken]）时，依次试「名字 (1).扩展名」「名字 (2).扩展名」…，
 * 和 Android 10+ 媒体库改 RELATIVE_PATH 遇到同名时的命名一致。
 */
internal fun freeFileName(name: String, taken: (String) -> Boolean): String {
    if (!taken(name)) return name
    val dot = name.lastIndexOf('.')
    val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    var n = 1
    while (true) {
        val candidate = "$base ($n)$ext"
        if (!taken(candidate)) return candidate
        n++
    }
}

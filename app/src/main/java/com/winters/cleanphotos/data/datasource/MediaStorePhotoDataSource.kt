package com.winters.cleanphotos.data.datasource

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.winters.cleanphotos.core.permission.PermissionChecker
import com.winters.cleanphotos.domain.model.DayKey
import com.winters.cleanphotos.domain.model.MediaType
import com.winters.cleanphotos.domain.model.PhotoItem
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaStorePhotoDataSource @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val contentResolver: ContentResolver get() = context.contentResolver

    fun queryAllPhotos(): List<PhotoItem> {
        try {
            val images = query(MediaType.Image, trashedOnly = false)
            // 没有视频权限时不查视频表：SecurityException 会让整次照片查询失败
            val videos = if (PermissionChecker.canReadVideos(context)) {
                query(MediaType.Video, trashedOnly = false)
            } else {
                emptyList()
            }
            return (images + videos).sortedByDescending { it.dateTaken ?: 0L }
        } catch (e: SecurityException) {
            Log.e(TAG, "查询照片缺少权限", e)
            throw e
        } catch (e: IllegalStateException) {
            Log.e(TAG, "MediaStore 查询失败", e)
            throw e
        }
    }

    /**
     * 查询已移动到系统垃圾桶的照片和视频。
     *
     * 仅 Android 11（API 30）及以上支持 IS_TRASHED 字段，
     * 以下版本无法查询系统垃圾桶，返回空列表。
     * 两张表的 _ID 都是 Files 表主键，合并后失效清理才不会把系统垃圾桶里的视频当成已删除。
     */
    fun queryTrashedPhotos(): List<PhotoItem> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "当前系统版本不支持垃圾桶查询（需要 Android 11+）")
            return emptyList()
        }

        try {
            val images = query(MediaType.Image, trashedOnly = true)
            val videos = if (PermissionChecker.canReadVideos(context)) {
                query(MediaType.Video, trashedOnly = true)
            } else {
                emptyList()
            }
            return (images + videos).sortedByDescending { it.dateTaken ?: 0L }
        } catch (e: SecurityException) {
            Log.e(TAG, "查询垃圾桶照片缺少权限", e)
            throw e
        } catch (e: IllegalStateException) {
            Log.e(TAG, "垃圾桶 MediaStore 查询失败", e)
            throw e
        }
    }

    private fun query(mediaType: MediaType, trashedOnly: Boolean): List<PhotoItem> {
        val video = mediaType == MediaType.Video
        val collection = if (video) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = projectionFor(video)
        // Android 8–9 没有 RELATIVE_PATH，用 DATA 相对外部存储根目录来推
        val storageRoot = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Environment.getExternalStorageDirectory().path
        } else {
            ""
        }
        val items = mutableListOf<PhotoItem>()
        val cursor = if (trashedOnly) {
            // Android 11+ 默认过滤掉垃圾桶中的媒体，必须通过 QUERY_ARG_MATCH_TRASHED 才返回
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { "系统垃圾桶查询需要 Android 11+" }
            val queryArgs = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "$DATE_TAKEN DESC")
            }
            contentResolver.query(collection, projection, queryArgs, null)
        } else {
            // DATE_TAKEN 倒序先排好大部分。DATE_TAKEN 为空的少数仍按 DATE_ADDED 归位，查完再排一次。
            contentResolver.query(
                collection,
                projection,
                null,
                null,
                "$DATE_TAKEN DESC",
            )
        }
        cursor?.use { readRows(it, collection, mediaType, storageRoot, items) }
        return items
    }

    private fun readRows(
        cursor: Cursor,
        collection: Uri,
        mediaType: MediaType,
        storageRoot: String,
        out: MutableList<PhotoItem>,
    ) {
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val dateTakenColumn = cursor.getColumnIndexOrThrow(DATE_TAKEN)
        val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
        val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
        val bucketColumn = cursor.getColumnIndexOrThrow(BUCKET_DISPLAY_NAME)
        // 视频的 ORIENTATION 从 Android 10 起才放进投影；列不在就不对调。列名与图片相同。
        val orientationColumn = cursor.getColumnIndex(MediaStore.Images.Media.ORIENTATION)
        val isFavoriteColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            cursor.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE)
        } else {
            -1
        }
        val durationColumn = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
        val relativePathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
        } else {
            -1
        }
        val dataColumn = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
        } else {
            -1
        }

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val dateTaken = cursor.getLong(dateTakenColumn)
            val dateAdded = cursor.getLong(dateAddedColumn)
            val size = cursor.getLong(sizeColumn)
            val rotated = orientationColumn >= 0 && cursor.getInt(orientationColumn) % 180 != 0
            val width = cursor.getInt(if (rotated) heightColumn else widthColumn)
            val height = cursor.getInt(if (rotated) widthColumn else heightColumn)
            val effectiveDate = if (dateTaken > 0) dateTaken else dateAdded * 1000
            val relativePath = if (relativePathColumn >= 0) {
                normalizeRelativePath(cursor.getString(relativePathColumn))
            } else {
                relativePathFromData(cursor.getString(dataColumn), storageRoot)
            }
            val durationMs = if (mediaType == MediaType.Video && durationColumn >= 0 && !cursor.isNull(durationColumn)) {
                cursor.getLong(durationColumn)
            } else {
                null
            }
            out.add(
                PhotoItem(
                    id = id,
                    uri = Uri.withAppendedPath(collection, id.toString()),
                    displayName = cursor.getString(displayNameColumn),
                    dateTaken = effectiveDate,
                    dateGroup = dayKeyOf(effectiveDate),
                    size = if (size > 0) size else null,
                    width = if (width > 0) width else null,
                    height = if (height > 0) height else null,
                    isFavorite = isFavoriteColumn >= 0 && cursor.getInt(isFavoriteColumn) == 1,
                    mediaType = mediaType,
                    durationMs = durationMs,
                    relativePath = relativePath,
                    bucketName = cursor.getString(bucketColumn)?.takeIf { it.isNotEmpty() },
                )
            )
        }
    }

    private fun projectionFor(video: Boolean): Array<String> {
        // DATE_TAKEN / BUCKET_DISPLAY_NAME 在 API 29 才升到 MediaColumns；这里用图片表上的同名常量，
        // 视频表从 API 26 起就是这两个列名，避免 lint 把 MediaColumns 的新字段当成 API 29。
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            BUCKET_DISPLAY_NAME,
        )
        if (video) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                projection.add(MediaStore.MediaColumns.ORIENTATION)
            }
            projection.add(MediaStore.Video.Media.DURATION)
        } else {
            projection.add(MediaStore.Images.Media.ORIENTATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.MediaColumns.RELATIVE_PATH)
        } else {
            projection.add(MediaStore.MediaColumns.DATA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            projection.add(MediaStore.MediaColumns.IS_FAVORITE)
        }
        return projection.toTypedArray()
    }

    /** 时区每次现取：跨时区后刷新，分组与 DayDisplay / LocalDate.now() 用同一个时区。 */
    private fun dayKeyOf(timestamp: Long): String =
        if (timestamp <= 0L) DayKey.UNKNOWN
        else DayKey.of(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate())

    companion object {
        private const val TAG = "MediaStorePhotoDS"
        private const val DATE_TAKEN = MediaStore.Images.Media.DATE_TAKEN
        private const val BUCKET_DISPLAY_NAME = MediaStore.Images.Media.BUCKET_DISPLAY_NAME
    }
}

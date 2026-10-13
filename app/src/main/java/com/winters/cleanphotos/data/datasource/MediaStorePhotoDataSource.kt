package com.winters.cleanphotos.data.datasource

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.winters.cleanphotos.domain.model.DayKey
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
        val photos = mutableListOf<PhotoItem>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.ORIENTATION
        )
        // Android 11 / API 30 及以上支持 IS_FAVORITE 字段
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            projection.add(MediaStore.Images.Media.IS_FAVORITE)
        }

        // 不依赖 SQL 排序，查询后在内存中用 effectiveDate 排序
        // 因为 DATE_TAKEN 为空时需要 fallback 到 DATE_ADDED
        val sortOrder = null

        try {
            contentResolver.query(
                collection,
                projection.toTypedArray(),
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val orientationColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)
                // IS_FAVORITE 列不一定存在（Android 11 以下），使用 getColumnIndex 安全读取
                val isFavoriteColumn = cursor.getColumnIndex(MediaStore.Images.Media.IS_FAVORITE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val displayName = cursor.getString(displayNameColumn)
                    val dateTaken = cursor.getLong(dateTakenColumn)
                    val dateAdded = cursor.getLong(dateAddedColumn)
                    val size = cursor.getLong(sizeColumn)
                    // WIDTH / HEIGHT 是存储的像素尺寸，不含 EXIF 旋转；转 90° / 270° 的要对调才是显示时的宽高
                    val rotated = cursor.getInt(orientationColumn) % 180 != 0
                    val width = cursor.getInt(if (rotated) heightColumn else widthColumn)
                    val height = cursor.getInt(if (rotated) widthColumn else heightColumn)
                    // 仅在列存在时读取系统收藏状态，不支持时默认 false（不伪造收藏）
                    val isFavorite = if (isFavoriteColumn >= 0) cursor.getInt(isFavoriteColumn) == 1 else false

                    val uri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )

                    val effectiveDate = if (dateTaken > 0) dateTaken else dateAdded * 1000
                    val dateGroup = dayKeyOf(effectiveDate)

                    photos.add(
                        PhotoItem(
                            id = id,
                            uri = uri,
                            displayName = displayName,
                            dateTaken = effectiveDate,
                            dateGroup = dateGroup,
                            size = if (size > 0) size else null,
                            width = if (width > 0) width else null,
                            height = if (height > 0) height else null,
                            isFavorite = isFavorite
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "查询照片缺少权限", e)
            throw e
        } catch (e: IllegalStateException) {
            Log.e(TAG, "MediaStore 查询失败", e)
            throw e
        }

        // 按 effectiveDate 倒序排列，确保 DATE_TAKEN 为空的照片也能正确排序
        return photos.sortedByDescending { it.dateTaken ?: 0L }
    }

    companion object {
        private const val TAG = "MediaStorePhotoDS"
    }

    /**
     * 查询已移动到垃圾桶的照片。
     *
     * 仅 Android 11（API 30）及以上支持 IS_TRASHED 字段，
     * 以下版本无法查询系统垃圾桶，返回空列表。
     */
    fun queryTrashedPhotos(): List<PhotoItem> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "当前系统版本不支持垃圾桶查询（需要 Android 11+）")
            return emptyList()
        }

        val photos = mutableListOf<PhotoItem>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.ORIENTATION,
            MediaStore.Images.Media.IS_FAVORITE
        )

        try {
            // Android 11+ 默认过滤掉垃圾桶中的媒体，必须通过 QUERY_ARG_MATCH_TRASHED
            // 告知 MediaStore 返回已放入垃圾桶的条目
            val queryArgs = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            }
            contentResolver.query(
                collection,
                projection.toTypedArray(),
                queryArgs,
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val orientationColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)
                val isFavoriteColumn = cursor.getColumnIndex(MediaStore.Images.Media.IS_FAVORITE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val displayName = cursor.getString(displayNameColumn)
                    val dateTaken = cursor.getLong(dateTakenColumn)
                    val dateAdded = cursor.getLong(dateAddedColumn)
                    val size = cursor.getLong(sizeColumn)
                    // WIDTH / HEIGHT 是存储的像素尺寸，不含 EXIF 旋转；转 90° / 270° 的要对调才是显示时的宽高
                    val rotated = cursor.getInt(orientationColumn) % 180 != 0
                    val width = cursor.getInt(if (rotated) heightColumn else widthColumn)
                    val height = cursor.getInt(if (rotated) widthColumn else heightColumn)
                    val isFavorite = if (isFavoriteColumn >= 0) cursor.getInt(isFavoriteColumn) == 1 else false

                    val uri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )

                    val effectiveDate = if (dateTaken > 0) dateTaken else dateAdded * 1000
                    val dateGroup = dayKeyOf(effectiveDate)

                    photos.add(
                        PhotoItem(
                            id = id,
                            uri = uri,
                            displayName = displayName,
                            dateTaken = effectiveDate,
                            dateGroup = dateGroup,
                            size = if (size > 0) size else null,
                            width = if (width > 0) width else null,
                            height = if (height > 0) height else null,
                            isFavorite = isFavorite
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "查询垃圾桶照片缺少权限", e)
            throw e
        } catch (e: IllegalStateException) {
            Log.e(TAG, "垃圾桶 MediaStore 查询失败", e)
            throw e
        }

        return photos.sortedByDescending { it.dateTaken ?: 0L }
    }

    /** 时区每次现取：跨时区后刷新，分组与 DayDisplay / LocalDate.now() 用同一个时区。 */
    private fun dayKeyOf(timestamp: Long): String =
        if (timestamp <= 0L) DayKey.UNKNOWN
        else DayKey.of(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate())
}

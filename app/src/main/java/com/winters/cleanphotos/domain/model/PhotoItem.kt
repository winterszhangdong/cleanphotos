package com.winters.cleanphotos.domain.model

import android.net.Uri

data class PhotoItem(
    val id: Long,
    val uri: Uri,
    val displayName: String?,
    val dateTaken: Long?,
    val dateGroup: String,
    val size: Long?,
    val width: Int?,
    val height: Int?,
    val isFavorite: Boolean = false,
    val isTrashed: Boolean = false,
    val mediaType: MediaType = MediaType.Image,
    /** 视频时长（ms）；图片为 null。 */
    val durationMs: Long? = null,
    /** 相对存储根目录的文件夹，如 `Pictures/Screenshots/`（与 MediaStore RELATIVE_PATH 同格式，以 `/` 结尾）。 */
    val relativePath: String? = null,
    /** 所在文件夹名（MediaStore BUCKET_DISPLAY_NAME）。 */
    val bucketName: String? = null,
)

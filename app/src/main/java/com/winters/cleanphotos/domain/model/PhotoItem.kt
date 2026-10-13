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
    val isTrashed: Boolean = false
)

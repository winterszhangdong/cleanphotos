package com.winters.cleanphotos.domain.model

data class PhotoAction(
    val photo: PhotoItem,
    val actionType: PhotoActionType,
    val previousIndex: Int,
    val timestamp: Long
)

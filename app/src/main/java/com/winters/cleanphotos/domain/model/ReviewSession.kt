package com.winters.cleanphotos.domain.model

data class ReviewSession(
    val photos: List<PhotoItem>,
    val currentIndex: Int,
    val totalCount: Int,
    val lastAction: PhotoAction?
)

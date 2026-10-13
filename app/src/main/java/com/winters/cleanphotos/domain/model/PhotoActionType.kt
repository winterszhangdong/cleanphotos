package com.winters.cleanphotos.domain.model

enum class PhotoActionType {
    /** 收藏照片（AllPhotos scope 上滑） */
    Favorite,
    /** 取消收藏（Favorites scope 上滑） */
    Unfavorite,
    /** 移入垃圾桶 */
    MoveToTrash
}

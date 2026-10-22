package com.winters.cleanphotos.domain.model

/** 媒体类型。照片和视频共用 [PhotoItem]，id 都是 MediaStore Files 表的 _ID，不会重复。 */
enum class MediaType {
    Image,
    Video
}

package com.winters.cleanphotos.domain.repository

import com.winters.cleanphotos.domain.model.MotionPhotoVideo
import com.winters.cleanphotos.domain.model.PhotoItem

/** 按照片懒识别内嵌视频。结果按 id + size 缓存在内存；识别不到返回 null。 */
interface MotionPhotoRepository {
    suspend fun locate(photo: PhotoItem): MotionPhotoVideo?
}

package com.winters.cleanphotos.domain.repository

import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.SimilarHashProgress
import com.winters.cleanphotos.domain.model.SimilarSharpnessProgress
import kotlinx.coroutines.flow.Flow

/** 给指定照片算 64 位 dHash 和清晰度。单张失败不出现在结果里，也不让整批失败。 */
interface SimilarPhotoRepository {
    fun hashPhotos(photos: List<PhotoItem>): Flow<SimilarHashProgress>

    fun measureSharpness(photos: List<PhotoItem>): Flow<SimilarSharpnessProgress>
}

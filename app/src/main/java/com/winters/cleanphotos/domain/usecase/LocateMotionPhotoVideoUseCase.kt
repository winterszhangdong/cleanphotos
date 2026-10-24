package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.MediaType
import com.winters.cleanphotos.domain.model.MotionPhotoVideo
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PlaybackTarget
import com.winters.cleanphotos.domain.repository.MotionPhotoRepository
import javax.inject.Inject

class LocateMotionPhotoVideoUseCase @Inject constructor(
    private val repository: MotionPhotoRepository,
) {
    suspend operator fun invoke(photo: PhotoItem): MotionPhotoVideo? = repository.locate(photo)

    /** 视频直接给地址；图片走识别。读文件失败由调用方处理。 */
    suspend fun playbackTarget(photo: PhotoItem): PlaybackTarget? = when (photo.mediaType) {
        MediaType.Video -> PlaybackTarget.Video(photo.uri)
        MediaType.Image -> invoke(photo)?.let { PlaybackTarget.MotionPhoto(photo.uri, it) }
    }
}

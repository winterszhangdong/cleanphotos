package com.winters.cleanphotos.domain.model

import android.net.Uri

/** 当前页可以播的内容。普通静态图没有目标。 */
sealed interface PlaybackTarget {
    val uri: Uri

    data class Video(override val uri: Uri) : PlaybackTarget

    data class MotionPhoto(
        override val uri: Uri,
        val video: MotionPhotoVideo,
    ) : PlaybackTarget
}

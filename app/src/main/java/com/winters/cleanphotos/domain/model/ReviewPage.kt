package com.winters.cleanphotos.domain.model

/**
 * 清理页翻页序列里的一页：照片，或两天之间的小结插页（决定 2）。
 * key 在整个会话内唯一且稳定，UI 用它做 Compose key，避免同一张图在不同页角色之间重建请求。
 */
sealed interface ReviewPage {
    val key: String

    data class Photo(val photo: PhotoItem) : ReviewPage {
        override val key: String get() = keyOf(photo.id)
    }

    data class Summary(val dayKey: String) : ReviewPage {
        override val key: String get() = summaryKeyOf(dayKey)
    }

    companion object {
        fun keyOf(photoId: Long): String = "p$photoId"
        fun summaryKeyOf(dayKey: String): String = "s$dayKey"
    }
}

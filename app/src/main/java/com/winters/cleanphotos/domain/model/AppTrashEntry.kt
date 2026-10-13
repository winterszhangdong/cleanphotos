package com.winters.cleanphotos.domain.model

/**
 * App 内垃圾桶条目。
 *
 * 与系统 MediaStore 垃圾桶不同，这是 App 自己维护的软删除记录。
 * 照片文件不会被删除，只是从 App 首页/清理流中隐藏。
 * 只有"清空垃圾桶"时才真正发起系统永久删除。
 *
 * @property photoId 照片在 MediaStore 中的 ID
 * @property movedToTrashAt 移入垃圾桶的时间戳（毫秒）
 * @property wasFavorited 移入垃圾桶时该照片是否在 App 内收藏中
 * @property favoritedAt 原收藏时间戳，未收藏时为 null
 */
data class AppTrashEntry(
    val photoId: Long,
    val movedToTrashAt: Long,
    val wasFavorited: Boolean = false,
    val favoritedAt: Long? = null
)

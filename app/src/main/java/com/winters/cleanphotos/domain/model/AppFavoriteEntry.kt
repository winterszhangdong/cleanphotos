package com.winters.cleanphotos.domain.model

/**
 * App 内收藏条目。
 *
 * 与系统 MediaStore IS_FAVORITE 不同，这是 App 自己维护的收藏登记。
 * 收藏/取消收藏不再触发系统授权，仅写入本地 DataStore。
 *
 * @property photoId 照片在 MediaStore 中的 ID
 * @property favoritedAt 收藏时间戳（毫秒）
 */
data class AppFavoriteEntry(
    val photoId: Long,
    val favoritedAt: Long
)

package com.winters.cleanphotos.data.datasource

import android.content.IntentSender
import com.winters.cleanphotos.domain.model.PhotoItem

/**
 * Android 16 且 targetSdk 36 时，createDeleteRequest / createWriteRequest / createFavoriteRequest
 * 每次最多 2000 个 URI，超出直接抛 IllegalArgumentException。
 */
internal const val MEDIA_STORE_REQUEST_LIMIT = 2000

/** 这一次系统确认的照片，以及还没交给系统的后续照片。 */
internal data class MediaStoreAuthBatch(
    val current: List<PhotoItem>,
    val remaining: List<PhotoItem>,
    val intentSender: IntentSender,
)

internal data class MediaStoreBatch<T>(
    val current: List<T>,
    val remaining: List<T>,
)

/** 只取出下一批。调用方必须在这一批有结果之后再处理 [MediaStoreBatch.remaining]，不能丢掉。 */
internal fun <T> firstMediaStoreBatch(
    items: List<T>,
    limit: Int = MEDIA_STORE_REQUEST_LIMIT,
): MediaStoreBatch<T> {
    require(limit > 0) { "系统授权批次上限必须为正：$limit" }
    if (items.size <= limit) return MediaStoreBatch(items, emptyList())
    return MediaStoreBatch(items.take(limit), items.drop(limit))
}

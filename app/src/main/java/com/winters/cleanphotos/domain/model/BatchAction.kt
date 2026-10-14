package com.winters.cleanphotos.domain.model

/** 照片页多选的批量动作。 */
enum class BatchActionType { Favorite, Unfavorite, MoveToTrash }

/**
 * 一次批量动作的结果：[photos] 是这次真正改变了状态的照片（撤回只撤它们，撤回胶囊的张数也是它们的数量）。
 */
data class BatchAction(val type: BatchActionType, val photos: List<PhotoItem>) {
    val count: Int get() = photos.size
}

package com.winters.cleanphotos.feature.allphotos

import com.winters.cleanphotos.R
import com.winters.cleanphotos.core.text.UiText
import com.winters.cleanphotos.domain.model.BatchAction
import com.winters.cleanphotos.domain.model.BatchActionType

/** 照片页撤回胶囊：seq 每次批量动作递增，UI 用它重启 4 秒倒计时。 */
data class BatchUndo(val seq: Long, val action: BatchAction, val title: UiText)

/**
 * 照片页多选状态。选中集合非空即为多选模式；取消到一张不剩就退出。
 */
data class PhotoSelectionState(
    val selectedIds: Set<Long> = emptySet(),
    /** 选中的全部已收藏：「收藏」按钮变成空心琥珀「取消收藏」。 */
    val allSelectedFavorite: Boolean = false,
    val isProcessing: Boolean = false,
    val undo: BatchUndo? = null,
) {
    val isSelecting: Boolean get() = selectedIds.isNotEmpty()
    val count: Int get() = selectedIds.size
}

/** 撤回胶囊主文案（只写实际改变的张数，不写副文案）。 */
fun batchUndoTitle(action: BatchAction): UiText = when (action.type) {
    BatchActionType.Favorite -> UiText.Plural(R.plurals.batch_undo_favorite, action.count)
    BatchActionType.Unfavorite -> UiText.Plural(R.plurals.batch_undo_unfavorite, action.count)
    BatchActionType.MoveToTrash -> UiText.Plural(R.plurals.batch_undo_trash, action.count)
}

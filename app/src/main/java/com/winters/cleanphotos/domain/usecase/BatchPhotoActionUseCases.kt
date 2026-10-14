package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.BatchAction
import com.winters.cleanphotos.domain.model.BatchActionType
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.repository.ReviewRepository
import com.winters.cleanphotos.domain.repository.SeenStateRepository
import javax.inject.Inject

/**
 * 多选「收藏」按钮的规则（interaction.md · 照片页多选）：
 * 选中的全部已收藏 → 取消收藏全部；否则 → 只收藏其中还没收藏的，已收藏的不动。
 */
class ResolveBatchFavoriteUseCase @Inject constructor() {
    operator fun invoke(selected: List<PhotoItem>): BatchAction {
        require(selected.isNotEmpty()) { "多选为空时不能收藏" }
        return if (selected.all { it.isFavorite }) {
            BatchAction(BatchActionType.Unfavorite, selected)
        } else {
            BatchAction(BatchActionType.Favorite, selected.filterNot { it.isFavorite })
        }
    }
}

/** 批量收藏 / 取消收藏。选中的所有照片都记为看过（包括没改变收藏状态的）。 */
class BatchFavoritePhotosUseCase @Inject constructor(
    private val resolve: ResolveBatchFavoriteUseCase,
    private val reviewRepository: ReviewRepository,
    private val seenStateRepository: SeenStateRepository,
) {
    suspend operator fun invoke(selected: List<PhotoItem>): Pair<BatchAction, PhotoOperationResult> {
        val action = resolve(selected)
        val result = when (action.type) {
            BatchActionType.Favorite -> reviewRepository.favoritePhotos(action.photos)
            BatchActionType.Unfavorite -> reviewRepository.removeFavorites(action.photos)
            BatchActionType.MoveToTrash -> error("不会出现")
        }
        if (result is PhotoOperationResult.Success) seenStateRepository.markSeen(selected.map { it.id }.toSet())
        return action to result
    }
}

/** 批量移入 App 内垃圾桶：选中的全部移走（含已收藏的，收藏状态快照进垃圾桶），全部记为看过。 */
class BatchMovePhotosToTrashUseCase @Inject constructor(
    private val reviewRepository: ReviewRepository,
    private val seenStateRepository: SeenStateRepository,
) {
    suspend operator fun invoke(selected: List<PhotoItem>): Pair<BatchAction, PhotoOperationResult> {
        require(selected.isNotEmpty()) { "多选为空时不能移入垃圾桶" }
        val result = reviewRepository.movePhotosToTrash(selected)
        if (result is PhotoOperationResult.Success) seenStateRepository.markSeen(selected.map { it.id }.toSet())
        return BatchAction(BatchActionType.MoveToTrash, selected) to result
    }
}

/** 撤回一次批量动作：只撤 [BatchAction.photos]。「看过」不回滚（与单张撤回一致）。 */
class UndoBatchActionUseCase @Inject constructor(
    private val reviewRepository: ReviewRepository,
) {
    suspend operator fun invoke(action: BatchAction): PhotoOperationResult = when (action.type) {
        BatchActionType.Favorite -> reviewRepository.removeFavorites(action.photos)
        BatchActionType.Unfavorite -> reviewRepository.favoritePhotos(action.photos)
        BatchActionType.MoveToTrash -> reviewRepository.restorePhotosFromTrash(action.photos)
    }
}

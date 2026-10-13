package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.PhotoAction
import com.winters.cleanphotos.domain.model.PhotoActionType
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import javax.inject.Inject

/**
 * 撤回照片操作。
 *
 * 撤回逻辑：
 * - Favorite（收藏）→ 取消收藏（removeFavorite）
 * - Unfavorite（取消收藏）→ 恢复收藏（favorite）
 * - MoveToTrash（移入垃圾桶）→ 从垃圾桶恢复
 */
class UndoPhotoActionUseCase @Inject constructor(
    private val favoritePhotoUseCase: FavoritePhotoUseCase,
    private val removeFavoritePhotoUseCase: RemoveFavoritePhotoUseCase,
    private val restorePhotoFromTrashUseCase: RestorePhotoFromTrashUseCase
) {
    suspend operator fun invoke(action: PhotoAction): PhotoOperationResult {
        return when (action.actionType) {
            PhotoActionType.Favorite -> removeFavoritePhotoUseCase(action.photo)
            PhotoActionType.Unfavorite -> favoritePhotoUseCase(action.photo)
            PhotoActionType.MoveToTrash -> restorePhotoFromTrashUseCase(action.photo)
        }
    }
}

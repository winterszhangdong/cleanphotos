package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.ReviewRules
import com.winters.cleanphotos.domain.model.DayCellState
import com.winters.cleanphotos.domain.model.ReviewLedger
import com.winters.cleanphotos.domain.model.ReviewOutcome
import com.winters.cleanphotos.domain.model.SessionDayProgress
import javax.inject.Inject

/**
 * 清理页顶部进度条：「当前照片所在那一天」在本次会话中的进度，一格一张，按会话顺序。
 * 收藏 / 取消收藏 / 移走 / 看过保留 / 当前 / 未处理；超过 40 张改为连续比例条。
 */
class BuildSessionDayProgressUseCase @Inject constructor() {
    operator fun invoke(ledger: ReviewLedger, currentPhotoId: Long): SessionDayProgress =
        invoke(ledger, ledger.photo(currentPhotoId).dateGroup, currentPhotoId)

    /** 小结插页用：currentPhotoId 为 null 时这一天没有「当前」格。 */
    operator fun invoke(ledger: ReviewLedger, dayKey: String, currentPhotoId: Long?): SessionDayProgress {
        val photos = ledger.photosOfDay(dayKey)
        val cells = photos.map { photo ->
            when {
                photo.id == currentPhotoId -> DayCellState.Current
                else -> when (ledger.outcomes[photo.id]) {
                    ReviewOutcome.Favorited -> DayCellState.Favorited
                    ReviewOutcome.Unfavorited -> DayCellState.Unfavorited
                    ReviewOutcome.Trashed -> DayCellState.Trashed
                    null -> if (photo.id in ledger.viewedIds) DayCellState.Kept else DayCellState.Pending
                }
            }
        }
        return SessionDayProgress(
            dayKey = dayKey,
            cells = cells,
            useContinuousBar = photos.size > ReviewRules.PROGRESS_CONTINUOUS_THRESHOLD,
        )
    }
}

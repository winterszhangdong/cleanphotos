package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.ReviewRules
import com.winters.cleanphotos.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** 决定 10：累计成功上滑 / 下滑不足 20 次时显示方向提示小点。 */
class ObserveShowDirectionHintsUseCase @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
) {
    operator fun invoke(): Flow<Boolean> = userPreferencesRepository.observeSwipeActionCount()
        .map { it < ReviewRules.DIRECTION_HINT_HIDE_AFTER }
}

/** 每次收藏 / 取消收藏 / 移到垃圾桶成功后调用一次（撤回不回退计数）。 */
class RecordSwipeActionUseCase @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
) {
    suspend operator fun invoke() = userPreferencesRepository.incrementSwipeActionCount()
}

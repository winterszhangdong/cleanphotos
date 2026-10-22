package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.TrashReminder
import com.winters.cleanphotos.domain.model.TrashReminderRules
import com.winters.cleanphotos.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/**
 * 当前是否该开始一次垃圾桶提醒。
 * 调用方在胶囊真正出现之后才会写入 shownAt；写入后这条流会变成 null，
 * 已经出现的那一次要由页面自己留着，不能跟着消失。
 */
class ObserveTrashReminderUseCase @Inject constructor(
    private val observeTrashSummary: ObserveTrashSummaryUseCase,
    private val observeTrashMovedAt: ObserveTrashMovedAtUseCase,
    private val userPreferencesRepository: UserPreferencesRepository,
) {
    operator fun invoke(): Flow<TrashReminder?> = combine(
        observeTrashSummary(),
        observeTrashMovedAt(),
        userPreferencesRepository.observeTrashReminderShownAt(),
    ) { summary, movedAt, shownAt ->
        val reason = TrashReminderRules.evaluate(
            count = summary.count,
            totalBytes = summary.totalBytes,
            earliestMovedAt = movedAt.values.minOrNull(),
            lastShownAt = shownAt,
            now = System.currentTimeMillis(),
        )
        reason?.let { TrashReminder(it, summary.totalBytes) }
    }
}

/** 胶囊出现的那一刻记下时间，之后 7 天内不再开始新的一次。 */
class MarkTrashReminderShownUseCase @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
) {
    suspend operator fun invoke(at: Long) {
        userPreferencesRepository.setTrashReminderShownAt(at)
    }
}

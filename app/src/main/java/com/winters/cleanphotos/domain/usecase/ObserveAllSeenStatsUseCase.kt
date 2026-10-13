package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.AllSeenStats
import com.winters.cleanphotos.domain.repository.PhotoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/** 决定 11：「全部看过了」= 收藏 N / 垃圾桶 N 张 / 待释放 X GB，只用现有数据。 */
class ObserveAllSeenStatsUseCase @Inject constructor(
    private val photoRepository: PhotoRepository,
    private val observeTrashSummary: ObserveTrashSummaryUseCase,
) {
    operator fun invoke(): Flow<AllSeenStats> =
        combine(photoRepository.observeFavoritedPhotos(), observeTrashSummary()) { favorites, trash ->
            AllSeenStats(favoriteCount = favorites.size, trashCount = trash.count, trashBytes = trash.totalBytes)
        }
}

package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.SimilarScan
import com.winters.cleanphotos.domain.repository.SimilarPhotoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/**
 * 全部照片（已排除收藏和垃圾桶）里找相似组。
 * 只给时间窗内有邻居的图片算哈希；算完一次性出组。照片列表变了就取消上一轮。
 */
class ObserveSimilarGroupsUseCase @Inject constructor(
    private val observeAllPhotosUseCase: ObserveAllPhotosUseCase,
    private val similarPhotoRepository: SimilarPhotoRepository,
    private val groupSimilarPhotosUseCase: GroupSimilarPhotosUseCase,
) {
    operator fun invoke(): Flow<SimilarScan> = observeAllPhotosUseCase().flatMapLatest { photos ->
        flow {
            val candidates = groupSimilarPhotosUseCase.hashCandidates(photos)
            if (candidates.isEmpty()) {
                emit(SimilarScan.Ready(emptyList()))
                return@flow
            }
            similarPhotoRepository.hashPhotos(candidates).collect { tick ->
                if (tick.finished) {
                    emit(SimilarScan.Ready(groupSimilarPhotosUseCase(photos, tick.hashes)))
                } else {
                    emit(SimilarScan.Comparing(tick.done, tick.total))
                }
            }
        }
    }
}

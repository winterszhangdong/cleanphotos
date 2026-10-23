package com.winters.cleanphotos.domain.usecase

import android.util.Log
import com.winters.cleanphotos.domain.model.SimilarGroup
import com.winters.cleanphotos.domain.model.SimilarScan
import com.winters.cleanphotos.domain.repository.SimilarPhotoRepository
import com.winters.cleanphotos.domain.repository.SimilarResolvedRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/**
 * 全部照片（已排除收藏和垃圾桶）里找相似组。
 * 先给时间窗内有邻居的图片算哈希并分组，再只给组内照片算清晰度，然后一次性出组。
 * 已处理记录是某组 id 的超集时，这组不出现。记录变化只重跑分组和过滤，不重新算哈希。
 */
class ObserveSimilarGroupsUseCase @Inject constructor(
    private val observeAllPhotosUseCase: ObserveAllPhotosUseCase,
    private val similarPhotoRepository: SimilarPhotoRepository,
    private val groupSimilarPhotosUseCase: GroupSimilarPhotosUseCase,
    private val similarResolvedRepository: SimilarResolvedRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<SimilarScan> = observeAllPhotosUseCase().flatMapLatest { photos ->
        flow {
            val candidates = groupSimilarPhotosUseCase.hashCandidates(photos)
            if (candidates.isEmpty()) {
                logScan(0, 0, 0, 0, 0)
                emit(SimilarScan.Ready(emptyList()))
                return@flow
            }
            val hashStart = System.nanoTime()
            var hashes = emptyMap<Long, Long>()
            similarPhotoRepository.hashPhotos(candidates).collect { tick ->
                if (tick.finished) hashes = tick.hashes
                else emit(SimilarScan.Comparing(tick.done, tick.total))
            }
            val hashMs = elapsedMs(hashStart)
            val members = groupSimilarPhotosUseCase(photos, hashes).flatMap { it.photos }
            val scores = HashMap<Long, Double>()
            val sharpStart = System.nanoTime()
            if (members.isNotEmpty()) {
                val base = candidates.size
                similarPhotoRepository.measureSharpness(members).collect { tick ->
                    if (tick.finished) scores.putAll(tick.scores)
                    else emit(SimilarScan.Comparing(base + tick.done, base + tick.total))
                }
            }
            val sharpMs = elapsedMs(sharpStart)
            val grouped = groupSimilarPhotosUseCase(photos, hashes, scores)
            logScan(candidates.size, hashMs, members.size, sharpMs, grouped.size)
            similarResolvedRepository.observeResolved().collect { resolved ->
                emit(SimilarScan.Ready(hideResolved(grouped, resolved)))
            }
        }
    }

    private companion object {
        fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000L

        fun logScan(hashCount: Int, hashMs: Long, sharpCount: Int, sharpMs: Long, groups: Int) {
            Log.i("SimilarScan", "hash ${hashCount}张 ${hashMs}ms 清晰度 ${sharpCount}张 ${sharpMs}ms 组 $groups")
        }

        fun hideResolved(groups: List<SimilarGroup>, resolved: List<Set<Long>>): List<SimilarGroup> {
            if (resolved.isEmpty()) return groups
            return groups.filterNot { group ->
                val ids = group.photos.mapTo(HashSet()) { it.id }
                resolved.any { it.containsAll(ids) }
            }
        }
    }
}

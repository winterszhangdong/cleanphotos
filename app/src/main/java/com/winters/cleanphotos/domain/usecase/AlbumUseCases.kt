package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.AlbumRules
import com.winters.cleanphotos.domain.model.FiledAlbumCount
import com.winters.cleanphotos.domain.model.FiledDay
import com.winters.cleanphotos.domain.model.FilingOutcome
import com.winters.cleanphotos.domain.model.FilingRecord
import com.winters.cleanphotos.domain.model.PhotoAlbum
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.repository.AlbumRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * 托盘里的底片袋：图库（不含 App 垃圾桶）按文件夹分组，只留 DCIM/、Pictures/ 下的。
 * 排序：最近装过的在前（按最后一次装袋时间），没装过的按最新一张的拍摄时间。
 */
class BuildAlbumsUseCase @Inject constructor() {
    operator fun invoke(library: List<PhotoItem>, log: List<FilingRecord>): List<PhotoAlbum> {
        val lastUsed = HashMap<String, Long>()
        for (r in log) lastUsed.merge(r.relativePath, r.filedAt, ::maxOf)
        return library
            .filter { it.relativePath?.let(AlbumRules::isAlbumPath) == true }
            .groupBy { it.relativePath!! }
            .map { (path, photos) ->
                val recent = photos.sortedByDescending { it.dateTaken ?: 0L }.take(AlbumRules.PEEK_COUNT)
                PhotoAlbum(path, AlbumRules.nameOf(path), photos.size, recent)
            }
            .sortedWith(
                compareByDescending<PhotoAlbum> { lastUsed[it.relativePath] ?: Long.MIN_VALUE }
                    .thenByDescending { it.recent.first().dateTaken ?: 0L },
            )
    }
}

/** 收藏页「已冲洗」：按天合并装袋记录，最近装过的天在前；只算还在图库里的照片。 */
class BuildFiledDaysUseCase @Inject constructor() {
    operator fun invoke(log: List<FilingRecord>, library: Map<Long, PhotoItem>): List<FiledDay> =
        log.filter { it.photoId in library }
            .groupBy { it.dayKey }
            .map { (day, records) ->
                FiledDay(
                    dayKey = day,
                    albums = records.groupingBy { it.albumName }.eachCount()
                        .map { (name, n) -> FiledAlbumCount(name, n) }
                        .sortedByDescending { it.count },
                    photos = records.mapNotNull { library[it.photoId] }.sortedBy { it.dateTaken ?: 0L },
                    lastFiledAt = records.maxOf { it.filedAt },
                )
            }
            .sortedByDescending { it.lastFiledAt }
}

class ObserveFilingLogUseCase @Inject constructor(private val repository: AlbumRepository) {
    operator fun invoke(): Flow<List<FilingRecord>> = repository.observeFilingLog()
}

/** 装袋（或撤回装袋）前的授权检查，见 [AlbumRepository.prepareMove]。 */
class PrepareAlbumMoveUseCase @Inject constructor(private val repository: AlbumRepository) {
    suspend operator fun invoke(photos: List<PhotoItem>): PhotoOperationResult = repository.prepareMove(photos)
}

class FilePhotosToAlbumUseCase @Inject constructor(private val repository: AlbumRepository) {
    suspend operator fun invoke(photos: List<PhotoItem>, relativePath: String): FilingOutcome {
        require(photos.isNotEmpty()) { "没有圈选时不能装袋" }
        require(AlbumRules.isAlbumPath(relativePath)) { "不是可以装照片的文件夹：$relativePath" }
        return repository.fileToAlbum(photos, relativePath)
    }
}

class UndoFilingUseCase @Inject constructor(private val repository: AlbumRepository) {
    suspend operator fun invoke(outcome: FilingOutcome): List<PhotoItem> = repository.undoFiling(outcome)
}

class RequestSystemFavoriteUseCase @Inject constructor(private val repository: AlbumRepository) {
    suspend operator fun invoke(photos: List<PhotoItem>): PhotoOperationResult? = repository.systemFavoriteRequest(photos)
}

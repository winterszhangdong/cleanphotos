package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.repository.PhotoRepository
import com.winters.cleanphotos.domain.repository.SeenStateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/** 月份条与小结需要的相册全量状态：全部照片（可见 + 收藏 + 垃圾桶）、处理过的 id、看过的 id。 */
data class LibraryDayState(
    val libraryPhotos: List<PhotoItem>,
    val processedIds: Set<Long>,
    val seenIds: Set<Long>,
)

class ObserveLibraryDayStateUseCase @Inject constructor(
    private val photoRepository: PhotoRepository,
    private val seenStateRepository: SeenStateRepository,
) {
    operator fun invoke(): Flow<LibraryDayState> = combine(
        photoRepository.observeAllPhotos(),
        photoRepository.observeFavoritedPhotos(),
        photoRepository.observeTrashedPhotos(),
        seenStateRepository.observeSeenIds(),
    ) { visible, favorites, trashed, seen ->
        val processed = favorites + trashed
        LibraryDayState(
            libraryPhotos = (visible + processed).distinctBy { it.id },
            processedIds = processed.map { it.id }.toSet(),
            seenIds = seen,
        )
    }
}

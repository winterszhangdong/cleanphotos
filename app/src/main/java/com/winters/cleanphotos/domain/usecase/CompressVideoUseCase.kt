package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.CompressFailure
import com.winters.cleanphotos.domain.model.CompressResult
import com.winters.cleanphotos.domain.model.CompressVideoPlan
import com.winters.cleanphotos.domain.model.MediaType
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.repository.PhotoRepository
import com.winters.cleanphotos.domain.repository.ReviewRepository
import com.winters.cleanphotos.domain.repository.SeenStateRepository
import com.winters.cleanphotos.domain.repository.TranscodeProduct
import com.winters.cleanphotos.domain.repository.VideoTranscoder
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * 压一条普通视频。成功后：原片若是收藏则新文件也收藏，新 id 记为看过，原片走移入垃圾桶。
 * 失败、取消、时长不符时不收藏、不移走原片。
 */
class CompressVideoUseCase @Inject constructor(
    private val transcoder: VideoTranscoder,
    private val reviewRepository: ReviewRepository,
    private val seenStateRepository: SeenStateRepository,
    private val photoRepository: PhotoRepository,
) {
    suspend operator fun invoke(photo: PhotoItem, onProgress: (Int) -> Unit = {}): CompressResult {
        if (photo.mediaType != MediaType.Video || photo.isTrashed) {
            return CompressResult.Failure(CompressFailure.Undecodable)
        }
        when (val product = transcoder.execute(photo, onProgress)) {
            is TranscodeProduct.Rejected -> return CompressResult.Failure(product.failure)
            TranscodeProduct.Cancelled -> return CompressResult.Failure(CompressFailure.Cancelled)
            is TranscodeProduct.Encoded -> {
                if (!CompressVideoPlan.durationAcceptable(product.originalDurationMs, product.outputDurationMs)) {
                    transcoder.discard(product.token)
                    return CompressResult.Failure(CompressFailure.DurationMismatch)
                }
                val created = try {
                    transcoder.publish(product.token)
                } catch (e: CancellationException) {
                    transcoder.discard(product.token)
                    throw e
                } catch (e: Exception) {
                    transcoder.discard(product.token)
                    throw e
                }
                if (photo.isFavorite) {
                    val favored = reviewRepository.favoritePhoto(created)
                    if (favored !is PhotoOperationResult.Success) {
                        transcoder.removePublished(created)
                        return CompressResult.Failure(CompressFailure.Failed)
                    }
                }
                val trashed = reviewRepository.movePhotoToTrash(photo)
                if (trashed !is PhotoOperationResult.Success) {
                    if (photo.isFavorite) reviewRepository.removeFavorite(created)
                    transcoder.removePublished(created)
                    return CompressResult.Failure(CompressFailure.Failed)
                }
                seenStateRepository.markSeen(created.id)
                photoRepository.refreshPhotos()
                return CompressResult.Success(created.copy(isFavorite = photo.isFavorite))
            }
        }
    }
}

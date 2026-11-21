package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.TestPhotoData
import com.winters.cleanphotos.domain.model.CompressFailure
import com.winters.cleanphotos.domain.model.CompressResult
import com.winters.cleanphotos.domain.model.MediaType
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationFailureReason
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.model.SwipeSideEffects
import com.winters.cleanphotos.domain.repository.ReviewRepository
import com.winters.cleanphotos.domain.repository.TranscodeProduct
import com.winters.cleanphotos.domain.repository.VideoTranscoder
import com.winters.cleanphotos.fake.FakePhotoRepository
import com.winters.cleanphotos.fake.FakeReviewRepository
import com.winters.cleanphotos.fake.FakeSeenStateRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressVideoUseCaseTest {
    private val review = RecordingReviewRepository()
    private val seen = FakeSeenStateRepository()
    private val photos = FakePhotoRepository()
    private val transcoder = RecordingTranscoder()
    private val useCase = CompressVideoUseCase(transcoder, review, seen, photos)

    @Test
    fun `成功后收藏和看过迁到新文件，原片进入垃圾桶`() = runTest {
        val original = video(id = 7, favorite = true)
        val created = video(id = 70, favorite = false)
        transcoder.product = TranscodeProduct.Encoded("tok", 10_000, 10_000)
        transcoder.published = created

        val result = useCase(original)

        val success = result as CompressResult.Success
        assertEquals(70L, success.created.id)
        assertTrue(success.created.isFavorite)
        assertEquals(listOf("execute:7", "publish:tok"), transcoder.calls)
        assertEquals(listOf(70L), review.favored)
        assertEquals(listOf(7L), review.trashed)
        assertTrue(70L in seen.seenIds.value)
        assertFalse(transcoder.calls.any { it.startsWith("discard") })
    }

    @Test
    fun `未收藏的成功只记看过并移走原片`() = runTest {
        val original = video(id = 3, favorite = false)
        transcoder.product = TranscodeProduct.Encoded("tok", 10_000, 10_000)
        transcoder.published = video(id = 30)

        val result = useCase(original)

        assertTrue(result is CompressResult.Success)
        assertTrue(review.favored.isEmpty())
        assertEquals(listOf(3L), review.trashed)
        assertTrue(30L in seen.seenIds.value)
    }

    @Test
    fun `拒绝或取消时原片不动且没有新收藏`() = runTest {
        transcoder.product = TranscodeProduct.Rejected(CompressFailure.Undecodable)
        val rejected = useCase(video(id = 1, favorite = true))
        assertEquals(CompressFailure.Undecodable, (rejected as CompressResult.Failure).reason)

        transcoder.product = TranscodeProduct.Cancelled
        val cancelled = useCase(video(id = 1, favorite = true))
        assertEquals(CompressFailure.Cancelled, (cancelled as CompressResult.Failure).reason)

        assertTrue(transcoder.calls.none { it.startsWith("publish") || it.startsWith("discard") })
        assertTrue(review.favored.isEmpty())
        assertTrue(review.trashed.isEmpty())
        assertTrue(seen.seenIds.value.isEmpty())
    }

    @Test
    fun `时长不符时丢掉成品，原片不动且没有新收藏`() = runTest {
        transcoder.product = TranscodeProduct.Encoded("tok", 10_501, 10_000)
        transcoder.published = video(id = 9)
        val result = useCase(video(id = 1, favorite = true))
        assertEquals(CompressFailure.DurationMismatch, (result as CompressResult.Failure).reason)
        assertEquals(listOf("execute:1", "discard:tok"), transcoder.calls)
        assertTrue(review.favored.isEmpty())
        assertTrue(review.trashed.isEmpty())
        assertTrue(seen.seenIds.value.isEmpty())
    }

    @Test
    fun `收藏或移入垃圾桶失败时删掉已入库的成品，原片不动`() = runTest {
        val created = video(id = 70, favorite = false)
        transcoder.product = TranscodeProduct.Encoded("tok", 10_000, 10_000)
        transcoder.published = created
        review.favoriteResult = PhotoOperationResult.Failure(PhotoOperationFailureReason.MediaStoreFailed)

        val favored = useCase(video(id = 7, favorite = true))
        assertEquals(CompressFailure.Failed, (favored as CompressResult.Failure).reason)
        assertEquals(listOf(70L), transcoder.removed)
        assertTrue(review.trashed.isEmpty())
        assertTrue(seen.seenIds.value.isEmpty())

        review.favoriteResult = null
        review.trashResult = PhotoOperationResult.Failure(PhotoOperationFailureReason.MediaStoreFailed)
        val trashed = useCase(video(id = 8, favorite = false))
        assertEquals(CompressFailure.Failed, (trashed as CompressResult.Failure).reason)
        assertEquals(listOf(70L, 70L), transcoder.removed)
        assertTrue(review.trashed.isEmpty())
    }

    @Test
    fun `不是普通视频时不调用转码`() = runTest {
        val image = TestPhotoData.createTestPhoto(id = 4)
        val result = useCase(image)
        assertEquals(CompressFailure.Undecodable, (result as CompressResult.Failure).reason)
        assertTrue(transcoder.calls.isEmpty())
        assertTrue(review.trashed.isEmpty())
    }

    private fun video(id: Long, favorite: Boolean = false) = TestPhotoData.createTestPhoto(id = id, isFavorite = favorite).copy(
        mediaType = MediaType.Video,
        displayName = "clip.mp4",
        durationMs = 10_000,
    )

    private class RecordingReviewRepository(
        private val fake: FakeReviewRepository = FakeReviewRepository(),
    ) : ReviewRepository by fake {
        val favored = mutableListOf<Long>()
        val trashed = mutableListOf<Long>()
        var favoriteResult: PhotoOperationResult? = null
        var trashResult: PhotoOperationResult? = null
        override suspend fun favoritePhoto(photo: PhotoItem, effects: SwipeSideEffects): PhotoOperationResult {
            favoriteResult?.let { return it }
            favored += photo.id
            return fake.favoritePhoto(photo, effects)
        }
        override suspend fun movePhotoToTrash(photo: PhotoItem, effects: SwipeSideEffects): PhotoOperationResult {
            trashResult?.let { return it }
            trashed += photo.id
            return fake.movePhotoToTrash(photo, effects)
        }
    }

    private class RecordingTranscoder : VideoTranscoder {
        val calls = mutableListOf<String>()
        val removed = mutableListOf<Long>()
        var product: TranscodeProduct = TranscodeProduct.Cancelled
        var published: PhotoItem? = null
        override suspend fun execute(photo: PhotoItem, onProgress: (Int) -> Unit): TranscodeProduct {
            calls += "execute:${photo.id}"
            return product
        }
        override suspend fun publish(token: String): PhotoItem {
            calls += "publish:$token"
            return published ?: error("没有成品")
        }
        override suspend fun discard(token: String) {
            calls += "discard:$token"
        }
        override suspend fun removePublished(photo: PhotoItem) {
            removed += photo.id
        }
    }
}

package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.TestPhotoData.createTestPhoto
import com.winters.cleanphotos.domain.model.BatchActionType
import com.winters.cleanphotos.fake.FakeReviewRepository
import com.winters.cleanphotos.fake.FakeSeenStateRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BatchPhotoActionUseCasesTest {
    private val review = FakeReviewRepository()
    private val seen = FakeSeenStateRepository()
    private val favorite = BatchFavoritePhotosUseCase(ResolveBatchFavoriteUseCase(), review, seen)
    private val trash = BatchMovePhotosToTrashUseCase(review, seen)
    private val undo = UndoBatchActionUseCase(review)

    @Test
    fun `混合选择_只收藏还没收藏的_已收藏的不动`() = runTest {
        val selected = listOf(createTestPhoto(1, isFavorite = true), createTestPhoto(2), createTestPhoto(3, isFavorite = true), createTestPhoto(4))
        val (action, _) = favorite(selected)
        assertEquals(BatchActionType.Favorite, action.type)
        assertEquals(listOf(2L, 4L), action.photos.map { it.id })
        assertEquals(2, action.count)
        assertEquals(listOf("favorite" to listOf(2L, 4L)), review.batchCalls)
    }

    @Test
    fun `全部已收藏_取消收藏全部`() = runTest {
        val selected = listOf(createTestPhoto(1, isFavorite = true), createTestPhoto(2, isFavorite = true))
        val (action, _) = favorite(selected)
        assertEquals(BatchActionType.Unfavorite, action.type)
        assertEquals(2, action.count)
        assertEquals(listOf("unfavorite" to listOf(1L, 2L)), review.batchCalls)
    }

    @Test
    fun `批量收藏_选中的全部记为看过且只写一次`() = runTest {
        favorite(listOf(createTestPhoto(1, isFavorite = true), createTestPhoto(2)))
        assertEquals(setOf(1L, 2L), seen.seenIds.value)
        assertEquals(1, seen.batchMarkCalls)
    }

    @Test
    fun `移入垃圾桶_含已收藏的全部移走并记为看过`() = runTest {
        val (action, _) = trash(listOf(createTestPhoto(1, isFavorite = true), createTestPhoto(2)))
        assertEquals(BatchActionType.MoveToTrash, action.type)
        assertEquals(2, action.count)
        assertEquals(listOf("trash" to listOf(1L, 2L)), review.batchCalls)
        assertEquals(setOf(1L, 2L), seen.seenIds.value)
    }

    @Test
    fun `撤回只撤实际改变的那批`() = runTest {
        val (fav, _) = favorite(listOf(createTestPhoto(1, isFavorite = true), createTestPhoto(2)))
        undo(fav)
        val (unfav, _) = favorite(listOf(createTestPhoto(5, isFavorite = true)))
        undo(unfav)
        val (moved, _) = trash(listOf(createTestPhoto(7), createTestPhoto(8, isFavorite = true)))
        undo(moved)
        assertEquals(
            listOf(
                "favorite" to listOf(2L), "unfavorite" to listOf(2L),
                "unfavorite" to listOf(5L), "favorite" to listOf(5L),
                "trash" to listOf(7L, 8L), "restore" to listOf(7L, 8L),
            ),
            review.batchCalls,
        )
    }
}

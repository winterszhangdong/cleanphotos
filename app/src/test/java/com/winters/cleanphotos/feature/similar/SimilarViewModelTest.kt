package com.winters.cleanphotos.feature.similar

import com.winters.cleanphotos.TestPhotoData
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationFailureReason
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.model.SimilarHashProgress
import com.winters.cleanphotos.domain.repository.SimilarPhotoRepository
import com.winters.cleanphotos.domain.usecase.BatchMovePhotosToTrashUseCase
import com.winters.cleanphotos.domain.usecase.GroupSimilarPhotosUseCase
import com.winters.cleanphotos.domain.usecase.ObserveAllPhotosUseCase
import com.winters.cleanphotos.domain.usecase.ObserveSimilarGroupsUseCase
import com.winters.cleanphotos.domain.usecase.UndoBatchActionUseCase
import com.winters.cleanphotos.fake.FakePhotoRepository
import com.winters.cleanphotos.fake.FakeReviewRepository
import com.winters.cleanphotos.fake.FakeSeenStateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SimilarViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val photos = FakePhotoRepository()
    private val review = FakeReviewRepository()
    private val seen = FakeSeenStateRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `移走其余张并可以撤回，图库去掉它们后组消失`() = runTest(dispatcher) {
        val keep = shot(1, 1_000, width = 100, height = 100)
        val other = shot(2, 2_000, width = 10, height = 10)
        val third = shot(3, 3_000, width = 10, height = 10)
        photos.setPhotos(listOf(keep, other, third))
        val vm = viewModel()
        advanceUntilIdle()
        val group = vm.uiState.value.groups.single()
        assertEquals(1L, group.keepId)

        vm.onKeep(group.key, 2L)
        assertEquals(2L, vm.uiState.value.groups.single().keepId)

        vm.onMoveRest(group.key)
        advanceUntilIdle()
        assertEquals(listOf(1L, 3L), review.batchCalls.last().second)
        assertEquals(1L, vm.uiState.value.undo?.seq)

        photos.setPhotos(listOf(other))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.groups.isEmpty())

        vm.onUndo()
        advanceUntilIdle()
        assertNull(vm.uiState.value.undo)
        assertEquals(listOf(1L, 3L), review.batchCalls.last().second)
        assertEquals("restore", review.batchCalls.last().first)

        photos.setPhotos(listOf(keep, other, third))
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 3L), vm.uiState.value.groups.single().photos.map { it.id })
        // 撤回不记用户改过的保留项：组还在，仍留下用户点过的那张
        assertEquals(2L, vm.uiState.value.groups.single().keepId)
    }

    @Test
    fun `移走失败不出现撤回`() = runTest(dispatcher) {
        photos.setPhotos(listOf(shot(1, 1_000), shot(2, 2_000)))
        review.batchMoveResult = PhotoOperationResult.Failure(PhotoOperationFailureReason.MediaStoreFailed)
        val vm = viewModel()
        advanceUntilIdle()
        val key = vm.uiState.value.groups.single().key
        vm.onMoveRest(key)
        advanceUntilIdle()
        assertNull(vm.uiState.value.undo)
        assertEquals(false, vm.uiState.value.moving)
        assertEquals(1, vm.uiState.value.groups.size)
    }

    @Test
    fun `撤回过期后胶囊消失，过期的序号不会清掉新的一次`() = runTest(dispatcher) {
        photos.setPhotos(listOf(shot(1, 1_000), shot(2, 2_000)))
        val vm = viewModel()
        advanceUntilIdle()
        vm.onMoveRest(vm.uiState.value.groups.single().key)
        advanceUntilIdle()
        val seq = vm.uiState.value.undo!!.seq
        vm.onUndoExpired(seq - 1)
        assertEquals(seq, vm.uiState.value.undo?.seq)
        vm.onUndoExpired(seq)
        assertNull(vm.uiState.value.undo)
    }

    private fun viewModel(): SimilarViewModel {
        val observe = ObserveSimilarGroupsUseCase(
            ObserveAllPhotosUseCase(photos),
            object : SimilarPhotoRepository {
                override fun hashPhotos(photos: List<PhotoItem>): Flow<SimilarHashProgress> = flow {
                    emit(SimilarHashProgress(photos.size, photos.size, photos.associate { it.id to 0L }, finished = true))
                }
            },
            GroupSimilarPhotosUseCase(),
        )
        return SimilarViewModel(
            observeSimilarGroupsUseCase = observe,
            batchMovePhotosToTrashUseCase = BatchMovePhotosToTrashUseCase(review, seen),
            undoBatchActionUseCase = UndoBatchActionUseCase(review),
        )
    }

    private fun shot(id: Long, taken: Long, width: Int = 10, height: Int = 10) =
        TestPhotoData.createTestPhoto(id).copy(dateTaken = taken, width = width, height = height)
}

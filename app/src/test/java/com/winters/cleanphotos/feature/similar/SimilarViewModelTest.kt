package com.winters.cleanphotos.feature.similar

import com.winters.cleanphotos.R
import com.winters.cleanphotos.TestPhotoData
import com.winters.cleanphotos.core.text.UiText
import com.winters.cleanphotos.domain.model.MotionPhotoVideo
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationFailureReason
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.model.SimilarHashProgress
import com.winters.cleanphotos.domain.model.SimilarMark
import com.winters.cleanphotos.domain.model.SimilarSharpnessProgress
import com.winters.cleanphotos.domain.repository.MotionPhotoRepository
import com.winters.cleanphotos.domain.repository.SimilarPhotoRepository
import com.winters.cleanphotos.core.coroutines.UptimeClock
import com.winters.cleanphotos.domain.usecase.BatchMovePhotosToTrashUseCase
import com.winters.cleanphotos.domain.usecase.EnsurePhotosLoadedUseCase
import com.winters.cleanphotos.domain.usecase.GroupSimilarPhotosUseCase
import com.winters.cleanphotos.domain.usecase.LocateMotionPhotoVideoUseCase
import com.winters.cleanphotos.domain.usecase.ObserveAllPhotosUseCase
import com.winters.cleanphotos.domain.usecase.ObserveSimilarGroupsUseCase
import com.winters.cleanphotos.domain.usecase.UndoBatchActionUseCase
import com.winters.cleanphotos.fake.FakePhotoRepository
import com.winters.cleanphotos.fake.FakeReviewRepository
import com.winters.cleanphotos.fake.FakeSeenStateRepository
import com.winters.cleanphotos.fake.FakeSimilarResolvedRepository
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SimilarViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val photos = FakePhotoRepository()
    private val review = FakeReviewRepository()
    private val seen = FakeSeenStateRepository()
    private val resolved = FakeSimilarResolvedRepository()
    private val motion = FakeMotionPhotos()
    private var now = 0L

    private class FakeMotionPhotos : MotionPhotoRepository {
        val motionIds = mutableSetOf<Long>()
        var hang: CompletableDeferred<Unit>? = null
        var error: Exception? = null
        var calls = 0
        override suspend fun locate(photo: PhotoItem): MotionPhotoVideo? {
            calls++
            hang?.await()
            error?.let { throw it }
            return if (photo.id in motionIds) MotionPhotoVideo(0, 8) else null
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `默认推荐留下，其余移走`() = runTest(dispatcher) {
        photos.setPhotos(listOf(shot(1, 1_000, width = 100), shot(2, 2_000)))
        val vm = viewModel()
        vm.start()
        advanceUntilIdle()
        val group = vm.uiState.value.groups.single()
        assertEquals(1L, group.recommendedId)
        val marks = vm.uiState.value.marks.getValue(group.key)
        assertEquals(SimilarMark.Keep, marks[1L])
        assertEquals(SimilarMark.Remove, marks[2L])
        assertEquals(1, vm.uiState.value.removableCount)
    }

    @Test
    fun `可以改标记，同一组还在时扫描不盖掉`() = runTest(dispatcher) {
        val first = shot(1, 1_000, width = 100)
        val second = shot(2, 2_000)
        photos.setPhotos(listOf(first, second))
        val vm = viewModel()
        vm.start()
        advanceUntilIdle()
        val key = vm.uiState.value.groups.single().key
        vm.onMark(key, 2L, SimilarMark.Keep)
        vm.onMark(key, 1L, SimilarMark.Remove)
        photos.setPhotos(listOf(shot(1, 1_000, width = 10), shot(2, 2_000, width = 400)))
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(2L, state.groups.single().recommendedId)
        assertEquals(SimilarMark.Keep, state.marks.getValue(key)[2L])
        assertEquals(SimilarMark.Remove, state.marks.getValue(key)[1L])
    }

    @Test
    fun `提交移走并记下仍留下的，撤回两步`() = runTest(dispatcher) {
        photos.setPhotos(
            listOf(shot(1, 1_000, width = 100), shot(2, 2_000), shot(3, 3_000), shot(4, 4_000)),
        )
        val vm = viewModel()
        vm.start()
        advanceUntilIdle()
        val key = vm.uiState.value.groups.single().key
        vm.onMark(key, 2L, SimilarMark.Keep)
        vm.onCommit(key)
        advanceUntilIdle()
        assertEquals(listOf(3L, 4L), calls("trash"))
        assertTrue(review.batchCalls.none { it.first == "favorite" })
        assertEquals(setOf(1L, 2L), resolved.added.single())
        assertTrue(vm.uiState.value.undo != null)

        vm.onUndo()
        advanceUntilIdle()
        assertNull(vm.uiState.value.undo)
        assertEquals(setOf(1L, 2L), resolved.removed.single())
        assertEquals(listOf(3L, 4L), calls("restore"))
        assertTrue(review.batchCalls.none { it.first == "unfavorite" })
    }

    @Test
    fun `都留下只写已处理记录，并给出可撤回胶囊`() = runTest(dispatcher) {
        photos.setPhotos(listOf(shot(1, 1_000, width = 100), shot(2, 2_000)))
        val vm = viewModel()
        vm.start()
        advanceUntilIdle()
        val key = vm.uiState.value.groups.single().key
        vm.onMark(key, 2L, SimilarMark.Keep)
        vm.onCommit(key)
        advanceUntilIdle()
        assertTrue(review.batchCalls.isEmpty())
        assertEquals(setOf(1L, 2L), resolved.added.single())
        val undo = vm.uiState.value.undo
        assertTrue(undo != null)
        assertEquals(UiText.Res(R.string.similar_undo_kept), undo!!.title)
        assertNull(undo.trashed)
    }

    @Test
    fun `全部移走不写已处理记录`() = runTest(dispatcher) {
        photos.setPhotos(listOf(shot(1, 1_000, width = 100), shot(2, 2_000)))
        val vm = viewModel()
        vm.start()
        advanceUntilIdle()
        val key = vm.uiState.value.groups.single().key
        vm.onMark(key, 1L, SimilarMark.Remove)
        vm.onCommit(key)
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), calls("trash"))
        assertTrue(resolved.added.isEmpty())
        assertTrue(vm.uiState.value.undo != null)
    }

    @Test
    fun `移走失败不出现撤回`() = runTest(dispatcher) {
        photos.setPhotos(listOf(shot(1, 1_000, width = 100), shot(2, 2_000)))
        review.batchMoveResult = PhotoOperationResult.Failure(PhotoOperationFailureReason.MediaStoreFailed)
        val vm = viewModel()
        vm.start()
        advanceUntilIdle()
        val key = vm.uiState.value.groups.single().key
        vm.onCommit(key)
        advanceUntilIdle()
        assertNull(vm.uiState.value.undo)
        assertEquals(false, vm.uiState.value.moving)
        assertTrue(resolved.added.isEmpty())
        assertTrue(review.batchCalls.none { it.first == "favorite" })
    }

    @Test
    fun `撤回过期后胶囊消失，过期的序号不会清掉新的一次`() = runTest(dispatcher) {
        photos.setPhotos(listOf(shot(1, 1_000, width = 100), shot(2, 2_000)))
        val vm = viewModel()
        vm.start()
        advanceUntilIdle()
        vm.onCommit(vm.uiState.value.groups.single().key)
        advanceUntilIdle()
        val seq = vm.uiState.value.undo!!.seq
        vm.onUndoExpired(seq - 1)
        assertEquals(seq, vm.uiState.value.undo?.seq)
        vm.onUndoExpired(seq)
        assertNull(vm.uiState.value.undo)
    }

    @Test
    fun `start 只比对一次，再调不重新哈希`() = runTest(dispatcher) {
        photos.setPhotos(listOf(shot(1, 1_000), shot(2, 2_000)))
        var hashes = 0
        val vm = viewModel(onHash = { hashes++ })
        advanceUntilIdle()
        assertEquals(0, hashes)
        vm.start()
        advanceUntilIdle()
        assertEquals(1, hashes)
        vm.start()
        advanceUntilIdle()
        assertEquals(1, hashes)
        assertEquals(1, vm.uiState.value.groups.size)
    }

    @Test
    fun `出过结果后图库再变，后台重跑不回到比对中`() = runTest(dispatcher) {
        photos.setPhotos(listOf(shot(1, 1_000), shot(2, 2_000)))
        val vm = viewModel()
        val phases = mutableListOf<SimilarPhase>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { phases += it.phase } }
        vm.start()
        advanceUntilIdle()
        assertTrue(SimilarPhase.Comparing in phases)
        phases.clear()
        photos.setPhotos(listOf(shot(1, 1_000), shot(2, 2_000), shot(3, 3_000)))
        advanceUntilIdle()
        assertTrue(phases.all { it == SimilarPhase.Ready })
        assertEquals(3, vm.uiState.value.groups.single().photos.size)
    }

    @Test
    fun `冷启动先加载照片表再扫描_再 start 不重扫`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        photos.beforeEnsure = { gate.await() }
        photos.onEnsureLoaded = { photos.setPhotos(listOf(shot(1, 1_000), shot(2, 2_000))) }
        var hashes = 0
        val vm = viewModel(onHash = { hashes++ })
        vm.start()
        advanceUntilIdle()
        assertEquals(SimilarPhase.Loading, vm.uiState.value.phase)
        assertEquals(0, hashes)
        assertEquals(1, photos.ensureLoadedCalls)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, hashes)
        assertEquals(SimilarPhase.Ready, vm.uiState.value.phase)
        assertEquals(1, vm.uiState.value.groups.size)
        vm.start()
        advanceUntilIdle()
        assertEquals(1, photos.ensureLoadedCalls)
        assertEquals(1, hashes)
    }

    @Test
    fun `照片表为空时扫描结束后才可以离开_比对中不离开`() = runTest(dispatcher) {
        val vm = viewModel()
        assertFalse(similarCompareShouldLeave(vm.uiState.value.phase, hasGroup = false))
        vm.start()
        advanceUntilIdle()
        assertEquals(1, photos.ensureLoadedCalls)
        assertEquals(SimilarPhase.Ready, vm.uiState.value.phase)
        assertTrue(vm.uiState.value.groups.isEmpty())
        assertTrue(similarCompareShouldLeave(vm.uiState.value.phase, hasGroup = false))
        assertFalse(similarCompareShouldLeave(SimilarPhase.Comparing, hasGroup = false))
        assertFalse(similarCompareShouldLeave(SimilarPhase.Ready, hasGroup = true))
        assertFalse(similarCompareShouldLeave(SimilarPhase.Failed, hasGroup = false))
    }

    @Test
    fun `照片表加载失败不变成空结果_再次开始能扫出来`() = runTest(dispatcher) {
        photos.beforeEnsure = { throw IllegalStateException("Volume external_primary not found") }
        val vm = viewModel()
        vm.start()
        advanceUntilIdle()
        assertEquals(SimilarPhase.Failed, vm.uiState.value.phase)
        assertTrue(vm.uiState.value.groups.isEmpty())
        assertFalse(similarCompareShouldLeave(vm.uiState.value.phase, hasGroup = false))
        assertEquals(1, photos.ensureLoadedCalls)
        photos.beforeEnsure = {}
        photos.onEnsureLoaded = { photos.setPhotos(listOf(shot(1, 1_000), shot(2, 2_000))) }
        vm.start()
        advanceUntilIdle()
        assertEquals(SimilarPhase.Ready, vm.uiState.value.phase)
        assertEquals(1, vm.uiState.value.groups.size)
        assertEquals(2, photos.ensureLoadedCalls)
    }

    @Test
    fun `加载被取消时不当成失败也不当成扫完`() = runTest(dispatcher) {
        photos.beforeEnsure = { throw CancellationException("stop") }
        val vm = viewModel()
        vm.start()
        advanceUntilIdle()
        assertEquals(SimilarPhase.Loading, vm.uiState.value.phase)
        assertTrue(vm.uiState.value.groups.isEmpty())
        assertFalse(similarCompareShouldLeave(vm.uiState.value.phase, hasGroup = false))
        val calls = photos.ensureLoadedCalls
        vm.start()
        advanceUntilIdle()
        assertEquals(calls, photos.ensureLoadedCalls)
        assertEquals(SimilarPhase.Loading, vm.uiState.value.phase)
    }

    @Test
    fun `过了截止时间再撤回不会恢复`() = runTest(dispatcher) {
        photos.setPhotos(listOf(shot(1, 1_000, width = 100), shot(2, 2_000)))
        val vm = viewModel()
        vm.start()
        advanceUntilIdle()
        vm.onCommit(vm.uiState.value.groups.single().key)
        advanceUntilIdle()
        val deadline = vm.uiState.value.undo!!.deadlineUptimeMillis
        now = deadline
        vm.onUndo()
        advanceUntilIdle()
        assertNull(vm.uiState.value.undo)
        assertTrue(review.batchCalls.none { it.first == "restore" })
    }

    @Test
    fun `对比页动态照片标在当前张上，换走就清掉`() = runTest(dispatcher) {
        motion.motionIds += 1L
        val vm = viewModel()
        vm.watchComparePhoto(shot(1, 1_000))
        advanceUntilIdle()
        assertEquals(1L, vm.uiState.value.compareMotionId)
        vm.watchComparePhoto(shot(2, 2_000))
        assertNull(vm.uiState.value.compareMotionId)
        advanceUntilIdle()
        assertNull(vm.uiState.value.compareMotionId)
        val calls = motion.calls
        vm.watchComparePhoto(shot(1, 1_000))
        assertEquals(1L, vm.uiState.value.compareMotionId)
        advanceUntilIdle()
        assertEquals(calls, motion.calls)
    }

    @Test
    fun `上一张还在识别时换走，结果不会写到新的一张`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        motion.hang = gate
        motion.motionIds += 1L
        val vm = viewModel()
        vm.watchComparePhoto(shot(1, 1_000))
        advanceUntilIdle()
        vm.watchComparePhoto(shot(2, 2_000))
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(vm.uiState.value.compareMotionId)
    }

    @Test
    fun `动态照片识别失败不当成动态照片`() = runTest(dispatcher) {
        motion.error = IOException("read")
        val vm = viewModel()
        vm.watchComparePhoto(shot(1, 1_000))
        advanceUntilIdle()
        assertNull(vm.uiState.value.compareMotionId)
    }

    private fun calls(kind: String): List<Long> =
        review.batchCalls.filter { it.first == kind }.map { it.second }.flatten()

    private fun viewModel(onHash: () -> Unit = {}): SimilarViewModel {
        val observe = ObserveSimilarGroupsUseCase(
            ObserveAllPhotosUseCase(photos),
            object : SimilarPhotoRepository {
                override fun hashPhotos(photos: List<PhotoItem>): Flow<SimilarHashProgress> = flow {
                    onHash()
                    emit(SimilarHashProgress(0, photos.size, emptyMap(), finished = false))
                    emit(SimilarHashProgress(photos.size, photos.size, photos.associate { it.id to 0L }, finished = true))
                }

                override fun measureSharpness(photos: List<PhotoItem>): Flow<SimilarSharpnessProgress> = flow {
                    emit(SimilarSharpnessProgress(photos.size, photos.size, emptyMap(), finished = true))
                }
            },
            GroupSimilarPhotosUseCase(),
            resolved,
        )
        return SimilarViewModel(
            observeSimilarGroupsUseCase = observe,
            batchMovePhotosToTrashUseCase = BatchMovePhotosToTrashUseCase(review, seen),
            undoBatchActionUseCase = UndoBatchActionUseCase(review),
            similarResolvedRepository = resolved,
            ensurePhotosLoadedUseCase = EnsurePhotosLoadedUseCase(photos),
            locateMotionPhotoVideoUseCase = LocateMotionPhotoVideoUseCase(motion),
            uptimeClock = UptimeClock { now },
        )
    }

    private fun shot(id: Long, taken: Long, width: Int = 10, height: Int = 10) =
        TestPhotoData.createTestPhoto(id).copy(dateTaken = taken, width = width, height = height)
}

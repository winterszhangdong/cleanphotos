package com.winters.cleanphotos.feature.review

import com.winters.cleanphotos.R
import com.winters.cleanphotos.core.text.UiText
import android.content.IntentSender
import android.net.Uri
import app.cash.turbine.test
import com.winters.cleanphotos.TestPhotoData
import com.winters.cleanphotos.domain.model.MediaType
import com.winters.cleanphotos.domain.model.MotionPhotoVideo
import com.winters.cleanphotos.domain.model.PhotoActionType
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PlaybackTarget
import com.winters.cleanphotos.domain.model.PhotoOperationFailureReason
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.model.ReviewEffect
import com.winters.cleanphotos.domain.model.ReviewPage
import com.winters.cleanphotos.domain.model.SystemMediaActionRequest
import com.winters.cleanphotos.domain.model.SystemMediaActionType
import com.winters.cleanphotos.domain.model.SwipeSideEffects
import com.winters.cleanphotos.domain.repository.ReviewRepository
import com.winters.cleanphotos.domain.usecase.BuildDaySummaryUseCase
import com.winters.cleanphotos.domain.usecase.BuildMonthProgressUseCase
import com.winters.cleanphotos.domain.usecase.BuildReviewPagesUseCase
import com.winters.cleanphotos.domain.usecase.BuildSessionDayProgressUseCase
import com.winters.cleanphotos.domain.repository.MotionPhotoRepository
import com.winters.cleanphotos.domain.usecase.FavoritePhotoUseCase
import com.winters.cleanphotos.domain.usecase.LocateMotionPhotoVideoUseCase
import com.winters.cleanphotos.domain.usecase.FilterPhotosUseCase
import com.winters.cleanphotos.domain.usecase.MarkPhotoSeenUseCase
import com.winters.cleanphotos.domain.usecase.MovePhotoToTrashUseCase
import com.winters.cleanphotos.domain.usecase.ObserveLibraryDayStateUseCase
import com.winters.cleanphotos.domain.usecase.ObserveShowDirectionHintsUseCase
import com.winters.cleanphotos.domain.usecase.ObserveTrashSummaryUseCase
import com.winters.cleanphotos.domain.usecase.ObserveTutorialStateUseCase
import com.winters.cleanphotos.domain.usecase.RemoveFavoritePhotoUseCase
import com.winters.cleanphotos.domain.usecase.RestorePhotoFromTrashUseCase
import com.winters.cleanphotos.domain.usecase.SetTutorialSeenUseCase
import com.winters.cleanphotos.domain.usecase.ShouldInsertDaySummaryUseCase
import com.winters.cleanphotos.domain.usecase.SortPhotosUseCase
import com.winters.cleanphotos.domain.usecase.StartReviewSessionUseCase
import com.winters.cleanphotos.domain.usecase.UndoPhotoActionUseCase
import com.winters.cleanphotos.fake.FakePhotoRepository
import com.winters.cleanphotos.fake.FakeReviewRepository
import com.winters.cleanphotos.fake.FakeSeenStateRepository
import com.winters.cleanphotos.core.coroutines.UptimeClock
import com.winters.cleanphotos.fake.FakeUserPreferencesRepository
import androidx.lifecycle.ViewModelStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ReviewViewModel：无处理锁、立即切页、串行执行、失败插回、撤回、教学。
 * 测试照片 id 越大拍摄越晚，NewestFirst 下顺序为 5,4,3,2,1。
 */
class ReviewViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var photoRepository: FakePhotoRepository
    private lateinit var reviewRepository: CountingReviewRepository
    private lateinit var preferences: FakeUserPreferencesRepository
    private lateinit var seen: FakeSeenStateRepository
    private var savedState = androidx.lifecycle.SavedStateHandle()
    private val motionPhotos = FakeMotionPhotos()
    private var now = 0L

    private class FakeMotionPhotos : MotionPhotoRepository {
        var located: MotionPhotoVideo? = null
        var error: Exception? = null
        override suspend fun locate(photo: PhotoItem): MotionPhotoVideo? {
            error?.let { throw it }
            return located
        }
    }

    /** 记录每次实际执行到仓库的操作，用于验证连续快滑不丢操作。 */
    private class CountingReviewRepository(val fake: FakeReviewRepository = FakeReviewRepository()) : ReviewRepository by fake {
        val calls = mutableListOf<Pair<String, Long>>()
        val persisted = mutableMapOf<Long, String>()
        var holdNextFavorite: CompletableDeferred<PhotoOperationResult>? = null
        var holdNextUnfavorite: CompletableDeferred<PhotoOperationResult>? = null
        var holdNextTrash: CompletableDeferred<Unit>? = null
        override suspend fun favoritePhoto(photo: PhotoItem, effects: SwipeSideEffects): PhotoOperationResult {
            calls += "fav" to photo.id
            val held = holdNextFavorite
            val result = if (held != null) {
                holdNextFavorite = null
                held.await()
            } else {
                fake.favoritePhoto(photo, effects)
            }
            if (result is PhotoOperationResult.Success) persisted[photo.id] = "fav"
            return result
        }
        override suspend fun removeFavorite(photo: PhotoItem, effects: SwipeSideEffects): PhotoOperationResult {
            calls += "unfav" to photo.id
            val held = holdNextUnfavorite
            val result = if (held != null) {
                holdNextUnfavorite = null
                held.await()
            } else {
                fake.removeFavorite(photo, effects)
            }
            if (result is PhotoOperationResult.Success) persisted[photo.id] = "library"
            return result
        }
        /** 模拟 DataStore 写入抛异常（存储满）。 */
        var throwOnTrash: Boolean = false
        override suspend fun movePhotoToTrash(photo: PhotoItem, effects: SwipeSideEffects): PhotoOperationResult {
            calls += "trash" to photo.id
            val held = holdNextTrash
            if (held != null) {
                holdNextTrash = null
                held.await()
            }
            if (throwOnTrash) throw java.io.IOException("No space left on device")
            val result = fake.movePhotoToTrash(photo, effects)
            if (result is PhotoOperationResult.Success) persisted[photo.id] = "trash"
            return result
        }
        override suspend fun restorePhotoFromTrash(photo: PhotoItem): PhotoOperationResult {
            calls += "restore" to photo.id
            val result = fake.restorePhotoFromTrash(photo)
            if (result is PhotoOperationResult.Success) persisted[photo.id] = "library"
            return result
        }
    }

    @Before
    fun setUp() {
        photoRepository = FakePhotoRepository()
        reviewRepository = CountingReviewRepository()
        preferences = FakeUserPreferencesRepository(initialSeen = false)
        seen = FakeSeenStateRepository()
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { mockk<Uri>(relaxed = true) }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Uri::class)
    }

    private fun createViewModel(compute: CoroutineDispatcher = testDispatcher): ReviewViewModel {
        val favorite = FavoritePhotoUseCase(reviewRepository)
        val removeFavorite = RemoveFavoritePhotoUseCase(reviewRepository)
        val restore = RestorePhotoFromTrashUseCase(reviewRepository)
        return ReviewViewModel(
            startReviewSessionUseCase = StartReviewSessionUseCase(photoRepository, SortPhotosUseCase(), FilterPhotosUseCase(seen)),
            favoritePhotoUseCase = favorite,
            removeFavoritePhotoUseCase = removeFavorite,
            movePhotoToTrashUseCase = MovePhotoToTrashUseCase(reviewRepository),
            undoPhotoActionUseCase = UndoPhotoActionUseCase(favorite, removeFavorite, restore),
            observeTutorialStateUseCase = ObserveTutorialStateUseCase(preferences),
            setTutorialSeenUseCase = SetTutorialSeenUseCase(preferences),
            markPhotoSeenUseCase = MarkPhotoSeenUseCase(seen),
            buildReviewPagesUseCase = BuildReviewPagesUseCase(ShouldInsertDaySummaryUseCase()),
            buildSessionDayProgressUseCase = BuildSessionDayProgressUseCase(),
            buildDaySummaryUseCase = BuildDaySummaryUseCase(BuildMonthProgressUseCase()),
            observeLibraryDayStateUseCase = ObserveLibraryDayStateUseCase(photoRepository, seen),
            observeShowDirectionHintsUseCase = ObserveShowDirectionHintsUseCase(preferences),
            observeTrashSummaryUseCase = ObserveTrashSummaryUseCase(photoRepository),
            locateMotionPhotoVideoUseCase = LocateMotionPhotoVideoUseCase(motionPhotos),
            operationQueue = ReviewOperationQueue(CoroutineScope(SupervisorJob() + testDispatcher)),
            uptimeClock = UptimeClock { now },
            computeDispatcher = compute,
            savedStateHandle = savedState,
        )
    }

    private fun start(count: Int = 5, startId: Long = 3L): ReviewViewModel {
        photoRepository.setPhotos(TestPhotoData.createTestPhotos(count))
        return createViewModel().also { it.initialize(startId, "NewestFirst", "AllPhotos", "AllPhotos") }
    }

    private fun photoKeys(state: ReviewUiState) = state.pages.filterIsInstance<ReviewPage.Photo>().map { it.photo.id }

    @Test
    fun `initialize 定位到入口照片并生成完整序列`() = runTest(testDispatcher) {
        val vm = start()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertFalse(s.isLoading)
        assertEquals("p3", s.currentKey)
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), photoKeys(s))
        assertNotNull("当前页应有进度条", s.progress["p3"])
        assertTrue("入口照片记为看过", 3L in seen.seenIds.value)
    }

    @Test
    fun `未看过教学时显示_看过不显示`() = runTest(testDispatcher) {
        val vm = start()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showTutorial)

        preferences = FakeUserPreferencesRepository(initialSeen = true)
        val vm2 = start()
        advanceUntilIdle()
        assertFalse(vm2.uiState.value.showTutorial)
    }

    @Test
    fun `上滑收藏_立即返回下一页并移出序列_关闭教学_出撤回条`() = runTest(testDispatcher) {
        val vm = start()
        advanceUntilIdle()
        val next = vm.onSwipeUp(3L)
        // 不等队列执行，状态已切换
        assertEquals("p2", next)
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(listOf(5L, 4L, 2L, 1L), photoKeys(s))
        assertEquals("p2", s.currentKey)
        assertEquals(UiText.Res(R.string.undo_favorited), s.undo?.title)
        assertEquals(PhotoActionType.Favorite, s.undo?.type)
        assertFalse(s.showTutorial)
        assertEquals(listOf("fav" to 3L), reviewRepository.calls)
    }

    @Test
    fun `按旧序列翻回刚滑走的照片_不认它当当前页`() = runTest(testDispatcher) {
        val vm = start()
        advanceUntilIdle()
        assertEquals("p2", vm.onSwipeDown(3L))
        // 新序列到达前，清理页按旧序列把刚移走的 p3 当成上一页翻过去
        vm.onPageSettled("p3")
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals("p2", s.currentKey)
        assertTrue(s.pages.any { it.key == s.currentKey })
    }

    @Test
    fun `连续快滑_每次都立即切页且全部按顺序执行不丢`() = runTest(testDispatcher) {
        val vm = start(count = 5, startId = 5L)
        advanceUntilIdle()
        // 不推进调度器：模拟 UI 在 ViewModel 处理之前就连续触发
        assertEquals("p4", vm.onSwipeDown(5L))
        assertEquals("p3", vm.onSwipeUp(4L))
        assertEquals("p2", vm.onSwipeDown(3L))
        assertEquals("p1", vm.onSwipeDown(2L))
        // 同一张重复触发不会产生第二个操作
        assertNull(vm.onSwipeDown(2L))
        advanceUntilIdle()
        assertEquals(
            listOf("trash" to 5L, "fav" to 4L, "trash" to 3L, "trash" to 2L),
            reviewRepository.calls,
        )
        assertEquals(listOf(1L), photoKeys(vm.uiState.value))
        assertEquals("p1", vm.uiState.value.currentKey)
    }

    @Test
    fun `最后一张处理完_会话结束`() = runTest(testDispatcher) {
        val vm = start(count = 1, startId = 1L)
        advanceUntilIdle()
        assertNull(vm.onSwipeDown(1L))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isCompleted)
        assertEquals(0L, vm.uiState.value.restoreEpoch)
    }

    @Test
    fun `收藏失败_照片插回原位并提示_撤回条消失`() = runTest(testDispatcher) {
        reviewRepository.fake.favoriteResult = PhotoOperationResult.Failure(PhotoOperationFailureReason.PermissionDenied)
        val vm = start()
        advanceUntilIdle()
        vm.onSwipeUp(3L)
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), photoKeys(s))
        assertNotNull(s.errorMessage)
        assertNull(s.undo)
    }

    @Test
    fun `移到垃圾桶需要系统授权_发出效果_拒绝后插回且不提示`() = runTest(testDispatcher) {
        val request = SystemMediaActionRequest(
            photos = listOf(TestPhotoData.createTestPhoto(id = 3L)),
            actionType = SystemMediaActionType.MoveToTrash,
            requestToken = "t",
            intentSender = mockk<IntentSender>(),
        )
        reviewRepository.fake.moveTrashResult = PhotoOperationResult.NeedSystemApproval(request)
        val vm = start()
        advanceUntilIdle()
        vm.effects.test {
            vm.onSwipeDown(3L)
            advanceUntilIdle()
            val effect = awaitItem()
            assertTrue(effect is ReviewEffect.LaunchSystemMediaAction)
            assertEquals("t", (effect as ReviewEffect.LaunchSystemMediaAction).request.requestToken)
        }
        // 授权前：照片已从序列移出（UI 已经切到下一张）
        assertFalse(3L in photoKeys(vm.uiState.value))
        vm.onSystemMediaActionResult(false)
        advanceUntilIdle()
        assertTrue(3L in photoKeys(vm.uiState.value))
    }

    @Test
    fun `撤回移到垃圾桶_照片回到原位并切回_调用恢复`() = runTest(testDispatcher) {
        val vm = start()
        advanceUntilIdle()
        vm.onSwipeDown(3L)
        advanceUntilIdle()
        assertEquals("p3", vm.onUndo())
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), photoKeys(s))
        assertEquals("p3", s.currentKey)
        assertNull(s.undo)
        assertEquals(listOf("trash" to 3L, "restore" to 3L), reviewRepository.calls)
    }

    @Test
    fun `撤回条到时只清掉同一次操作`() = runTest(testDispatcher) {
        val vm = start()
        advanceUntilIdle()
        vm.onSwipeDown(3L)
        advanceUntilIdle()
        val first = vm.uiState.value.undo!!.seq
        vm.onSwipeDown(2L)
        vm.onUndoExpired(first)
        advanceUntilIdle()
        assertNotNull("后一次操作的撤回条仍在", vm.uiState.value.undo)
    }

    @Test
    fun `左右翻页落定_离开的照片记为看过_关闭教学`() = runTest(testDispatcher) {
        val vm = start()
        advanceUntilIdle()
        vm.onPageSettled("p2")
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals("p2", s.currentKey)
        assertTrue(2L in seen.seenIds.value)
        assertFalse(s.showTutorial)
    }

    // —— 从旧版恢复的用例（功能仍在，按新接口改写）——

    @Test
    fun `撤回收藏_照片回到原位_调用取消收藏`() = runTest(testDispatcher) {
        val vm = start()
        advanceUntilIdle()
        vm.onSwipeUp(3L)
        advanceUntilIdle()
        assertEquals("p3", vm.onUndo())
        advanceUntilIdle()
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), photoKeys(vm.uiState.value))
        assertEquals("p3", vm.uiState.value.currentKey)
        assertEquals(listOf("fav" to 3L, "unfav" to 3L), reviewRepository.calls)
        assertNull(vm.uiState.value.undo)
    }

    @Test
    fun `无操作时撤回不做任何事`() = runTest(testDispatcher) {
        val vm = start()
        advanceUntilIdle()
        assertNull(vm.onUndo())
        advanceUntilIdle()
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), photoKeys(vm.uiState.value))
        assertEquals("p3", vm.uiState.value.currentKey)
        assertTrue(reviewRepository.calls.isEmpty())
    }

    @Test
    fun `移到垃圾桶失败_照片插回原位并提示`() = runTest(testDispatcher) {
        reviewRepository.fake.moveTrashResult = PhotoOperationResult.Failure(PhotoOperationFailureReason.MediaStoreFailed)
        val vm = start(count = 3, startId = 2L)
        advanceUntilIdle()
        vm.onSwipeDown(2L)
        advanceUntilIdle()
        assertEquals(listOf(3L, 2L, 1L), photoKeys(vm.uiState.value))
        assertEquals(UiText.Res(R.string.fail_trash), vm.uiState.value.errorMessage)
    }

    @Test
    fun `处理完最后一张之前_会话未结束`() = runTest(testDispatcher) {
        val vm = start(count = 2, startId = 2L)
        advanceUntilIdle()
        vm.onSwipeDown(2L)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isCompleted)
        vm.onSwipeDown(1L)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isCompleted)
    }

    @Test
    fun `dismissError 清除错误信息`() = runTest(testDispatcher) {
        reviewRepository.fake.favoriteResult = PhotoOperationResult.Failure(PhotoOperationFailureReason.PermissionDenied)
        val vm = start()
        advanceUntilIdle()
        vm.onSwipeUp(3L)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.errorMessage)
        vm.dismissError()
        advanceUntilIdle()
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `initialize 二次调用被跳过_旋转屏幕不重建会话`() = runTest(testDispatcher) {
        val vm = start()
        advanceUntilIdle()
        vm.onSwipeDown(3L)
        advanceUntilIdle()
        vm.initialize(5L, "NewestFirst", "AllPhotos", "AllPhotos")
        advanceUntilIdle()
        assertEquals(listOf(5L, 4L, 2L, 1L), photoKeys(vm.uiState.value))
        assertEquals("p2", vm.uiState.value.currentKey)
    }

    @Test
    fun `从中间照片进入_左右翻页双向都能到达首尾`() = runTest(testDispatcher) {
        val vm = start()
        advanceUntilIdle()
        for (k in listOf("p2", "p1", "p2", "p3", "p4", "p5")) {
            vm.onPageSettled(k)
            advanceUntilIdle()
            assertEquals(k, vm.uiState.value.currentKey)
        }
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), photoKeys(vm.uiState.value))
    }

    @Test
    fun `写入抛异常_照片插回原位并提示_队列继续执行后面的操作`() = runTest(testDispatcher) {
        reviewRepository.throwOnTrash = true
        val vm = start(count = 3, startId = 2L)
        advanceUntilIdle()
        vm.onSwipeDown(2L)
        advanceUntilIdle()
        assertEquals(listOf(3L, 2L, 1L), photoKeys(vm.uiState.value))
        assertEquals(UiText.Res(R.string.fail_trash), vm.uiState.value.errorMessage)

        reviewRepository.throwOnTrash = false
        vm.onSwipeUp(vm.uiState.value.currentKey!!.removePrefix("p").toLong())
        advanceUntilIdle()
        assertEquals("异常之后的操作照常落盘", "fav", reviewRepository.calls.last().first)
    }

    @Test
    fun `会话没能建立_标记加载失败让页面返回入口`() = runTest(testDispatcher) {
        val vm = start(count = 3, startId = 99L)
        advanceUntilIdle()
        val s = vm.uiState.value
        assertTrue(s.loadFailed)
        assertFalse(s.isLoading)
        assertNotNull(s.errorMessage)
        assertFalse("没有会话不算处理完", s.isCompleted)
    }

    @Test
    fun `滑动的附带写入随操作一起交给仓库_失败时下一张单独记看过`() = runTest(testDispatcher) {
        val vm = start()
        advanceUntilIdle()
        vm.onSwipeUp(3L)
        advanceUntilIdle()
        assertEquals(listOf(SwipeSideEffects(markSeenId = 2L, countSwipe = true)), reviewRepository.fake.effectsLog)
        assertFalse("成功路径不再单独写看过（已合进仓库那次写入）", 2L in seen.seenIds.value)

        reviewRepository.fake.moveTrashResult = PhotoOperationResult.Failure(PhotoOperationFailureReason.MediaStoreFailed)
        vm.onSwipeDown(2L)
        advanceUntilIdle()
        assertTrue("失败时下一张照样记为看过", 1L in seen.seenIds.value)
    }

    @Test
    fun `进程重建_从上次正在看的照片接着看_不回到入口`() = runTest(testDispatcher) {
        val vm = start()
        advanceUntilIdle()
        vm.onPageSettled("p2")
        advanceUntilIdle()
        assertEquals(2L, savedState.get<Long>(ReviewViewModel.KEY_CURRENT_PHOTO))

        // 模拟进程回收：同一份 SavedStateHandle、新的 ViewModel、入口参数仍是 3
        val restored = createViewModel().also { it.initialize(3L, "NewestFirst", "AllPhotos", "AllPhotos") }
        advanceUntilIdle()
        assertEquals("p2", restored.uiState.value.currentKey)
    }

    @Test
    fun `当前页是视频时直接给出播放地址，普通照片没有播放目标`() = runTest(testDispatcher) {
        val photos = TestPhotoData.createTestPhotos(5).map {
            if (it.id == 3L) it.copy(mediaType = MediaType.Video) else it
        }
        photoRepository.setPhotos(photos)
        val vm = createViewModel().also { it.initialize(3L, "NewestFirst", "AllPhotos", "AllPhotos") }
        advanceUntilIdle()
        val playback = vm.uiState.value.playback
        assertTrue(playback is PlaybackTarget.Video)
        assertEquals(photos.first { it.id == 3L }.uri, playback!!.uri)

        vm.onPageSettled("p2")
        advanceUntilIdle()
        assertNull(vm.uiState.value.playback)
    }

    @Test
    fun `当前页是动态照片时给出内嵌视频范围`() = runTest(testDispatcher) {
        motionPhotos.located = MotionPhotoVideo(12, 34)
        val vm = start()
        advanceUntilIdle()
        val playback = vm.uiState.value.playback as PlaybackTarget.MotionPhoto
        assertEquals(12L, playback.video.offset)
        assertEquals(34L, playback.video.length)
    }

    @Test
    fun `动态照片读失败时当成静态图`() = runTest(testDispatcher) {
        motionPhotos.error = java.io.IOException("read")
        val vm = start()
        advanceUntilIdle()
        assertNull(vm.uiState.value.playback)
    }

    @Test
    fun `静音只在这次进入清理页里保持`() = runTest(testDispatcher) {
        val vm = start()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.playbackMuted)
        vm.onPlaybackMuted(false)
        vm.onPageSettled("p2")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.playbackMuted)
    }

    @Test
    fun `过了截止时间再撤回不会执行`() = runTest(testDispatcher) {
        val vm = start()
        advanceUntilIdle()
        vm.onSwipeDown(3L)
        advanceUntilIdle()
        val deadline = vm.uiState.value.undo!!.deadlineUptimeMillis
        now = deadline
        assertNull(vm.onUndo())
        advanceUntilIdle()
        assertNull(vm.uiState.value.undo)
        assertEquals(listOf("trash" to 3L), reviewRepository.calls)
        assertEquals("trash", reviewRepository.persisted[3L])
    }

    @Test
    fun `离开清理页后已入队的写入仍按顺序落盘`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        reviewRepository.holdNextTrash = gate
        val vm = start(count = 5, startId = 5L)
        advanceUntilIdle()
        val store = ViewModelStore()
        store.put("review", vm)
        assertEquals("p4", vm.onSwipeDown(5L))
        assertEquals("p3", vm.onSwipeUp(4L))
        advanceUntilIdle()
        assertEquals(listOf("trash" to 5L), reviewRepository.calls)
        store.clear()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("trash" to 5L, "fav" to 4L), reviewRepository.calls)
        assertEquals("trash", reviewRepository.persisted[5L])
        assertEquals("fav", reviewRepository.persisted[4L])
    }

    @Test
    fun `最后一张还在落盘时不算完成_离开后仍然写入`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        reviewRepository.holdNextTrash = gate
        val vm = start(count = 1, startId = 1L)
        advanceUntilIdle()
        val store = ViewModelStore()
        store.put("review", vm)
        assertNull(vm.onSwipeDown(1L))
        advanceUntilIdle()
        assertNull(vm.uiState.value.currentKey)
        assertFalse(vm.uiState.value.isCompleted)
        store.clear()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("trash" to 1L), reviewRepository.calls)
        assertEquals("trash", reviewRepository.persisted[1L])
    }

    @Test
    fun `系统授权还在等时离开_不堵住后面已经接纳的写入`() = runTest(testDispatcher) {
        val request = SystemMediaActionRequest(
            photos = listOf(TestPhotoData.createTestPhoto(id = 5L)),
            actionType = SystemMediaActionType.MoveToTrash,
            requestToken = "t",
            intentSender = mockk<IntentSender>(),
        )
        reviewRepository.fake.moveTrashResult = PhotoOperationResult.NeedSystemApproval(request)
        val vm = start(count = 5, startId = 5L)
        advanceUntilIdle()
        val store = ViewModelStore()
        store.put("review", vm)
        vm.onSwipeDown(5L)
        vm.onSwipeUp(4L)
        advanceUntilIdle()
        store.clear()
        advanceUntilIdle()
        assertEquals(listOf("trash" to 5L, "fav" to 4L), reviewRepository.calls)
        assertEquals("fav", reviewRepository.persisted[4L])
        assertFalse(reviewRepository.persisted[5L] == "trash")
    }

    @Test
    fun `旧收藏失败不会清掉撤回后重做的移走`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<PhotoOperationResult>()
        reviewRepository.holdNextFavorite = gate
        val vm = start()
        advanceUntilIdle()
        vm.onSwipeUp(3L)
        advanceUntilIdle()
        vm.onUndo()
        vm.onSwipeDown(3L)
        gate.complete(PhotoOperationResult.Failure(PhotoOperationFailureReason.MediaStoreFailed))
        advanceUntilIdle()
        val s = vm.uiState.value
        assertFalse(3L in photoKeys(s))
        assertEquals(PhotoActionType.MoveToTrash, s.undo?.type)
        assertEquals(3L, s.undo?.photo?.id)
        assertEquals(listOf("fav" to 3L, "trash" to 3L), reviewRepository.calls)
        assertEquals("trash", reviewRepository.persisted[3L])
    }

    @Test
    fun `旧撤回失败不会清掉随后做成的移走`() = runTest(testDispatcher) {
        val vm = start()
        advanceUntilIdle()
        vm.onSwipeUp(3L)
        advanceUntilIdle()
        val gate = CompletableDeferred<PhotoOperationResult>()
        reviewRepository.holdNextUnfavorite = gate
        vm.onUndo()
        advanceUntilIdle()
        vm.onSwipeDown(3L)
        gate.complete(PhotoOperationResult.Failure(PhotoOperationFailureReason.MediaStoreFailed))
        advanceUntilIdle()
        val s = vm.uiState.value
        assertFalse(3L in photoKeys(s))
        assertEquals(PhotoActionType.MoveToTrash, s.undo?.type)
        assertEquals(listOf("fav" to 3L, "unfav" to 3L, "trash" to 3L), reviewRepository.calls)
        assertEquals("trash", reviewRepository.persisted[3L])
    }

    @Test
    fun `单张会话写入失败后回到这张_不判完成`() = runTest(testDispatcher) {
        reviewRepository.fake.moveTrashResult = PhotoOperationResult.Failure(PhotoOperationFailureReason.MediaStoreFailed)
        val vm = start(count = 1, startId = 1L)
        advanceUntilIdle()
        assertNull(vm.onSwipeDown(1L))
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals("p1", s.currentKey)
        assertEquals(listOf(1L), photoKeys(s))
        assertFalse(s.isCompleted)
        assertNotNull(s.errorMessage)
        assertNull(s.undo)
        assertEquals(1L, s.restoreEpoch)
    }

    @Test
    fun `末张写入失败后回到这张_不判完成`() = runTest(testDispatcher) {
        val vm = start(count = 2, startId = 2L)
        advanceUntilIdle()
        vm.onSwipeDown(2L)
        advanceUntilIdle()
        reviewRepository.fake.moveTrashResult = PhotoOperationResult.Failure(PhotoOperationFailureReason.MediaStoreFailed)
        assertNull(vm.onSwipeDown(1L))
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals("p1", s.currentKey)
        assertTrue(1L in photoKeys(s))
        assertFalse(s.isCompleted)
        assertNotNull(s.errorMessage)
        assertEquals(1L, s.restoreEpoch)
    }

    @Test
    fun `空页被合并时成功滑走末张不反弹`() = runTest(testDispatcher) {
        val gate = HoldDispatcher(testDispatcher)
        photoRepository.setPhotos(TestPhotoData.createTestPhotos(1))
        val vm = createViewModel(compute = gate).also { it.initialize(1L, "NewestFirst", "AllPhotos", "AllPhotos") }
        advanceUntilIdle()
        val before = vm.uiState.value
        assertEquals("p1", before.currentKey)
        assertEquals(0L, before.restoreEpoch)
        val stage = ReviewStageState(CoroutineScope(SupervisorJob() + testDispatcher))
        stage.syncFromViewModel(before.pages, before.currentKey, before.restoreEpoch)

        gate.hold = true
        assertNull(vm.onSwipeDown(1L))
        stage.sessionEndedLocally = true
        stage.currentKey = null
        advanceUntilIdle()
        val held = vm.uiState.value
        assertTrue(before.pages === held.pages)
        assertEquals("p1", held.currentKey)
        assertEquals(0L, held.restoreEpoch)
        assertNull(held.errorMessage)
        stage.syncFromViewModel(held.pages, held.currentKey, held.restoreEpoch)
        assertNull(stage.currentKey)
        assertTrue(stage.sessionEndedLocally)

        gate.release()
        advanceUntilIdle()
        val after = vm.uiState.value
        assertNull(after.currentKey)
        assertEquals(0L, after.restoreEpoch)
        assertTrue(after.isCompleted)
        assertNull(after.errorMessage)
        assertTrue(photoKeys(after).isEmpty())
        stage.syncFromViewModel(after.pages, after.currentKey, after.restoreEpoch)
        assertNull(stage.currentKey)
        assertTrue(stage.sessionEndedLocally)
    }

    @Test
    fun `空页被计算线程合并掉时舞台仍回到失败的那张`() = runTest(testDispatcher) {
        val gate = HoldDispatcher(testDispatcher)
        photoRepository.setPhotos(TestPhotoData.createTestPhotos(1))
        val vm = createViewModel(compute = gate).also { it.initialize(1L, "NewestFirst", "AllPhotos", "AllPhotos") }
        advanceUntilIdle()
        val before = vm.uiState.value
        assertEquals("p1", before.currentKey)
        assertEquals(0L, before.restoreEpoch)
        val stage = ReviewStageState(CoroutineScope(SupervisorJob() + testDispatcher))
        stage.syncFromViewModel(before.pages, before.currentKey, before.restoreEpoch)
        assertEquals("p1", stage.currentKey)

        gate.hold = true
        reviewRepository.fake.moveTrashResult = PhotoOperationResult.Failure(PhotoOperationFailureReason.MediaStoreFailed)
        assertNull(vm.onSwipeDown(1L))
        stage.sessionEndedLocally = true
        stage.currentKey = null
        advanceUntilIdle()
        val held = vm.uiState.value
        assertTrue(before.pages === held.pages)
        assertEquals("p1", held.currentKey)
        assertEquals(0L, held.restoreEpoch)
        assertNull(held.errorMessage)
        stage.syncFromViewModel(held.pages, held.currentKey, held.restoreEpoch)
        assertNull(stage.currentKey)
        assertTrue(stage.sessionEndedLocally)

        gate.release()
        advanceUntilIdle()
        val after = vm.uiState.value
        assertEquals("p1", after.currentKey)
        assertEquals(before.restoreEpoch + 1, after.restoreEpoch)
        assertTrue(before.pages === after.pages)
        assertNotNull(after.errorMessage)
        assertFalse(after.isCompleted)
        assertTrue(1L in photoKeys(after))
        stage.syncFromViewModel(after.pages, after.currentKey, after.restoreEpoch)
        assertEquals("p1", stage.currentKey)
        assertEquals("p1", stage.currentPage?.key)
        assertFalse(stage.sessionEndedLocally)
    }
}

/** 计算线程先按住。StateFlow 只交出最后一次 Core，空页那一帧不会建成界面状态。 */
private class HoldDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
    var hold = false
    private val pending = ArrayDeque<Runnable>()

    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
        if (hold) {
            synchronized(pending) { pending.addLast(block) }
        } else {
            delegate.dispatch(context, block)
        }
    }

    fun release() {
        val blocks = synchronized(pending) {
            hold = false
            val copy = pending.toList()
            pending.clear()
            copy
        }
        blocks.forEach { delegate.dispatch(EmptyCoroutineContext, it) }
    }
}

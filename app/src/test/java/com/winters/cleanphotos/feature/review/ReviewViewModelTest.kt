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
import com.winters.cleanphotos.fake.FakeUserPreferencesRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
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
        override suspend fun favoritePhoto(photo: PhotoItem, effects: SwipeSideEffects): PhotoOperationResult {
            calls += "fav" to photo.id; return fake.favoritePhoto(photo, effects)
        }
        override suspend fun removeFavorite(photo: PhotoItem, effects: SwipeSideEffects): PhotoOperationResult {
            calls += "unfav" to photo.id; return fake.removeFavorite(photo, effects)
        }
        /** 模拟 DataStore 写入抛异常（存储满）。 */
        var throwOnTrash: Boolean = false
        override suspend fun movePhotoToTrash(photo: PhotoItem, effects: SwipeSideEffects): PhotoOperationResult {
            calls += "trash" to photo.id
            if (throwOnTrash) throw java.io.IOException("No space left on device")
            return fake.movePhotoToTrash(photo, effects)
        }
        override suspend fun restorePhotoFromTrash(photo: PhotoItem): PhotoOperationResult {
            calls += "restore" to photo.id; return fake.restorePhotoFromTrash(photo)
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

    private fun createViewModel(): ReviewViewModel {
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
            computeDispatcher = testDispatcher,
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
}

package com.winters.cleanphotos.feature.review

import com.winters.cleanphotos.core.text.UiText
import com.winters.cleanphotos.R
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.winters.cleanphotos.core.coroutines.DefaultDispatcher
import com.winters.cleanphotos.core.util.DayDisplay
import com.winters.cleanphotos.core.util.FileSizeFormatter
import com.winters.cleanphotos.domain.model.PhotoAction
import com.winters.cleanphotos.domain.model.PhotoActionType
import com.winters.cleanphotos.domain.model.PhotoFilterMode
import com.winters.cleanphotos.domain.model.MediaType
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationFailureReason
import com.winters.cleanphotos.domain.model.PlaybackTarget
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.model.PhotoSortOrder
import com.winters.cleanphotos.domain.model.ReviewEffect
import com.winters.cleanphotos.domain.model.ReviewLedger
import com.winters.cleanphotos.domain.model.ReviewOutcome
import com.winters.cleanphotos.domain.model.ReviewPage
import com.winters.cleanphotos.domain.model.ReviewScope
import com.winters.cleanphotos.domain.model.SwipeSideEffects
import com.winters.cleanphotos.domain.usecase.BuildDaySummaryUseCase
import com.winters.cleanphotos.domain.usecase.BuildReviewPagesUseCase
import com.winters.cleanphotos.domain.usecase.BuildSessionDayProgressUseCase
import com.winters.cleanphotos.domain.usecase.FavoritePhotoUseCase
import com.winters.cleanphotos.domain.usecase.LocateMotionPhotoVideoUseCase
import com.winters.cleanphotos.domain.usecase.LibraryDayState
import com.winters.cleanphotos.domain.usecase.MarkPhotoSeenUseCase
import com.winters.cleanphotos.domain.usecase.MovePhotoToTrashUseCase
import com.winters.cleanphotos.domain.usecase.ObserveLibraryDayStateUseCase
import com.winters.cleanphotos.domain.usecase.ObserveShowDirectionHintsUseCase
import com.winters.cleanphotos.domain.model.TrashSummary
import com.winters.cleanphotos.domain.usecase.ObserveTrashSummaryUseCase
import com.winters.cleanphotos.domain.usecase.ObserveTutorialStateUseCase
import com.winters.cleanphotos.domain.usecase.RemoveFavoritePhotoUseCase
import com.winters.cleanphotos.domain.usecase.SetTutorialSeenUseCase
import com.winters.cleanphotos.domain.usecase.StartReviewSessionUseCase
import com.winters.cleanphotos.domain.usecase.UndoPhotoActionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * 清理页 ViewModel（interaction.md「快速连续滑动」）。
 *
 * - 不用全局处理锁：每次上下滑在调用的瞬间就更新会话（照片移出序列、记账、切到下一页），
 *   并把对应的 UseCase 调用排进串行队列；UI 不等结果，立刻可以接着滑。
 * - 同一张照片只处理一次（photoId 去重）。
 * - 操作失败：照片按会话顺序插回原位并提示，队列里后面的操作照常执行。
 * - 小结插页由 [BuildReviewPagesUseCase] 预判放进序列，UI 拖动时就能看到。
 */
@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val startReviewSessionUseCase: StartReviewSessionUseCase,
    private val favoritePhotoUseCase: FavoritePhotoUseCase,
    private val removeFavoritePhotoUseCase: RemoveFavoritePhotoUseCase,
    private val movePhotoToTrashUseCase: MovePhotoToTrashUseCase,
    private val undoPhotoActionUseCase: UndoPhotoActionUseCase,
    private val observeTutorialStateUseCase: ObserveTutorialStateUseCase,
    private val setTutorialSeenUseCase: SetTutorialSeenUseCase,
    private val markPhotoSeenUseCase: MarkPhotoSeenUseCase,
    private val buildReviewPagesUseCase: BuildReviewPagesUseCase,
    private val buildSessionDayProgressUseCase: BuildSessionDayProgressUseCase,
    private val buildDaySummaryUseCase: BuildDaySummaryUseCase,
    private val observeLibraryDayStateUseCase: ObserveLibraryDayStateUseCase,
    private val observeShowDirectionHintsUseCase: ObserveShowDirectionHintsUseCase,
    private val observeTrashSummaryUseCase: ObserveTrashSummaryUseCase,
    private val locateMotionPhotoVideoUseCase: LocateMotionPhotoVideoUseCase,
    @DefaultDispatcher computeDispatcher: CoroutineDispatcher,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** 会话核心状态，所有修改都在主线程同步完成。 */
    private data class Core(
        val loading: Boolean = true,
        val scope: ReviewScope = ReviewScope.AllPhotos,
        val ledger: ReviewLedger? = null,
        val removed: Set<Long> = emptySet(),
        val currentKey: String? = null,
        val activeSummaryDay: String? = null,
        val undo: UndoInfo? = null,
        val error: UiText? = null,
        val tutorialDismissed: Boolean = false,
        /** 会话没能建立（照片表加载失败 / 起点照片已不存在）：页面提示后返回入口。 */
        val loadFailed: Boolean = false,
    )

    private val core = MutableStateFlow(Core())

    private val _effects = Channel<ReviewEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    /** 串行执行的 UseCase 队列。 */
    private val operations = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var pendingApproval: CompletableDeferred<Boolean>? = null
    private var undoSeq = 0L
    private var initialized = false

    /** 上一次发出的界面状态，只在下面的 combine 里读写（单协程顺序执行）。 */
    private var lastUi: ReviewUiState? = null

    /** 当前页的播放目标。识别在 IO，不放进 buildUiState（那条在计算线程上）。 */
    private val playbackTarget = MutableStateFlow<PlaybackTarget?>(null)
    private val playbackMuted = MutableStateFlow(true)
    private var playbackJob: Job? = null
    private var prefetchJob: Job? = null

    val uiState: StateFlow<ReviewUiState> = combine(
        combine(
            core,
            observeLibraryDayStateUseCase().onStart { emit(EMPTY_LIBRARY) }.catch { emit(EMPTY_LIBRARY) },
            observeShowDirectionHintsUseCase().onStart { emit(false) }.catch { emit(false) },
            observeTutorialStateUseCase().onStart { emit(true) }.catch { emit(true) },
            observeTrashSummaryUseCase().catch { emit(TrashSummary(0, 0L)) },
        ) { c, library, hints, tutorialSeen, trash ->
            reuseUnchanged(buildUiState(c, library, hints, tutorialSeen, trash.count))
        }
            // 序列、进度、小结和上游的全库 combine 都不在主线程算：一次滑动会连锁触发好几轮，正好压在离场动画里。
            // UI 不等这里——上下滑 / 翻页由 act() 同步返回的 key 在同一帧切换
            .flowOn(computeDispatcher),
        playbackTarget,
        playbackMuted,
    ) { base, target, muted ->
        base.copy(playback = target, playbackMuted = muted)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ReviewUiState())

    init {
        viewModelScope.launch {
            for (op in operations) {
                // 兜底：单个操作异常不能结束整个队列（否则之后的滑动都不再落盘）
                try {
                    op()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "清理操作执行失败", e)
                }
            }
        }
        // 记住正在看的照片：进程被回收后回到清理页从这张接着看，而不是回到入口那张（小结页不覆盖）
        viewModelScope.launch {
            core.map { photoIdOf(it.currentKey) }.distinctUntilChanged().collect { id ->
                if (id != null) savedStateHandle[KEY_CURRENT_PHOTO] = id
            }
        }
        viewModelScope.launch {
            core.map { it.currentKey }.distinctUntilChanged().collect { resolvePlayback(it) }
        }
    }

    /** 这次进入清理页记住静音，不写 DataStore。 */
    fun onPlaybackMuted(muted: Boolean) {
        playbackMuted.value = muted
    }

    private fun resolvePlayback(key: String?) {
        playbackJob?.cancel()
        val photo = photoOf(key)
        if (photo == null) {
            playbackTarget.value = null
            return
        }
        if (photo.mediaType == MediaType.Video) {
            playbackTarget.value = PlaybackTarget.Video(photo.uri)
            prefetchNext(photo.id)
            return
        }
        playbackTarget.value = null
        playbackJob = viewModelScope.launch {
            val target = locateOrNull(photo)
            if (core.value.currentKey == key) playbackTarget.value = target
        }
        prefetchNext(photo.id)
    }

    /** 下一张如果是图片，提前识别。失败只记日志，不发布到界面。 */
    private fun prefetchNext(photoId: Long) {
        val next = (neighborsOf(core.value, photoId)?.next as? ReviewPage.Photo)?.photo ?: return
        if (next.mediaType != MediaType.Image) return
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            locateOrNull(next)
        }
    }

    private suspend fun locateOrNull(photo: PhotoItem): PlaybackTarget? = try {
        locateMotionPhotoVideoUseCase.playbackTarget(photo)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "动态照片识别失败 id=${photo.id}", e)
        null
    }

    private fun photoOf(key: String?): PhotoItem? {
        val id = photoIdOf(key) ?: return null
        val ledger = core.value.ledger ?: return null
        val index = ledger.indexOrNull(id) ?: return null
        return ledger.orderedPhotos[index]
    }

    /** 携带入口页的排序、过滤和 scope。旋转屏幕时 ViewModel 存活，不重复初始化。 */
    fun initialize(photoId: Long, sortOrder: String, filterMode: String, scope: String, unseenOnly: Boolean = false) {
        if (initialized) return
        initialized = true
        val reviewScope = ReviewScope.valueOf(scope)
        core.update { it.copy(scope = reviewScope) }
        viewModelScope.launch {
            try {
                // 进程重建：从上次正在看的照片开始；它已被处理 / 不在列表里时由 UseCase 就近选一张
                val startId = savedStateHandle.get<Long>(KEY_CURRENT_PHOTO) ?: photoId
                val order = PhotoSortOrder.valueOf(sortOrder)
                val session = startReviewSessionUseCase(
                    startId,
                    order,
                    PhotoFilterMode.valueOf(filterMode),
                    reviewScope,
                    unseenOnly,
                )
                val start = session.photos[session.currentIndex]
                core.update {
                    it.copy(
                        loading = false,
                        ledger = ReviewLedger(session.photos, sortOrder = order),
                        currentKey = ReviewPage.keyOf(start.id),
                    )
                }
                runCatchingNonCancel { markPhotoSeenUseCase(start.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "清理会话启动失败", e)
                core.update { it.copy(loading = false, loadFailed = true, error = UiText.Res(R.string.error_load_photos)) }
            }
        }
    }

    /** 上滑：AllPhotos 收藏 / Favorites 取消收藏。返回切到的页 key（null 表示会话结束）。 */
    fun onSwipeUp(photoId: Long): String? {
        val type = when (core.value.scope) {
            ReviewScope.AllPhotos -> PhotoActionType.Favorite
            ReviewScope.Favorites -> PhotoActionType.Unfavorite
        }
        return act(photoId, type)
    }

    /** 下滑：移到垃圾桶。 */
    fun onSwipeDown(photoId: Long): String? = act(photoId, PhotoActionType.MoveToTrash)

    /** 左右翻页落定（或点小结「下一天」）。 */
    fun onPageSettled(key: String) {
        val c = core.value
        val ledger = c.ledger ?: return
        if (key == c.currentKey) return
        var newLedger = ledger
        photoIdOf(c.currentKey)?.let { prev -> if (prev !in c.removed) newLedger = newLedger.markViewed(prev) }
        val summaryDay = summaryDayOf(key)
        if (summaryDay != null) newLedger = newLedger.markSummarized(summaryDay)
        core.value = c.copy(ledger = newLedger, currentKey = key, activeSummaryDay = summaryDay)
        photoIdOf(key)?.let { id -> viewModelScope.launch { runCatchingNonCancel { markPhotoSeenUseCase(id) } } }
        dismissTutorial()
    }

    /** 撤回最近一次操作：照片插回原位并切回它。返回切到的页 key。 */
    fun onUndo(): String? {
        val c = core.value
        val ledger = c.ledger ?: return null
        val undo = c.undo ?: return null
        val photo = undo.photo
        if (photo.id !in c.removed) return null
        val previousOutcome = ledger.outcomes[photo.id]
        var newLedger = ledger.undoOutcome(photo.id)
        if (c.activeSummaryDay == photo.dateGroup) {
            newLedger = newLedger.copy(summarizedDays = newLedger.summarizedDays - photo.dateGroup)
        }
        val key = ReviewPage.keyOf(photo.id)
        core.value = c.copy(
            ledger = newLedger, removed = c.removed - photo.id, currentKey = key,
            activeSummaryDay = null, undo = null,
        )
        enqueue {
            val result = safeOperation { undoPhotoActionUseCase(PhotoAction(photo, undo.type, 0, System.currentTimeMillis())) }
            if (!awaitSuccess(result)) {
                core.update { s ->
                    val l = s.ledger ?: return@update s
                    s.copy(
                        ledger = previousOutcome?.let { l.recordOutcome(photo.id, it) } ?: l,
                        removed = s.removed + photo.id,
                        currentKey = if (s.currentKey == key) nextKeyAfterRemoval(s, photo.id) else s.currentKey,
                        error = UiText.Res(R.string.error_undo),
                    )
                }
            }
        }
        return key
    }

    /** 撤回条 4 秒到时。 */
    fun onUndoExpired(seq: Long) {
        core.update { if (it.undo?.seq == seq) it.copy(undo = null) else it }
    }

    fun onSystemMediaActionResult(authorized: Boolean) {
        pendingApproval?.complete(authorized)
        pendingApproval = null
    }

    fun dismissError() = core.update { it.copy(error = null) }

    /** 点「知道了」或完成任意一次滑动后永久关闭教学。 */
    fun dismissTutorial() {
        if (core.value.tutorialDismissed) return
        core.update { it.copy(tutorialDismissed = true) }
        viewModelScope.launch { runCatchingNonCancel { setTutorialSeenUseCase() } }
    }

    // —— 内部 ——

    private fun act(photoId: Long, type: PhotoActionType): String? {
        val c = core.value
        val ledger = c.ledger ?: return null
        if (photoId in c.removed) return null
        // 只取相邻两页，不在主线程重建整条序列（几千张时每次滑动都是 O(n) + 小结判断）
        val neighbors = neighborsOf(c, photoId) ?: return null
        val photo = ledger.photo(photoId)
        val next = neighbors.next ?: neighbors.previous
        var newLedger = ledger.recordOutcome(photoId, outcomeOf(type))
        val summaryDay = (next as? ReviewPage.Summary)?.dayKey
        if (summaryDay != null) newLedger = newLedger.markSummarized(summaryDay)
        val undo = UndoInfo(++undoSeq, photo, type, undoTitle(type), undoSubtitle(ledger, photo, type))
        core.value = c.copy(
            ledger = newLedger, removed = c.removed + photoId, currentKey = next?.key,
            activeSummaryDay = summaryDay, undo = undo,
        )
        val nextPhotoId = (next as? ReviewPage.Photo)?.photo?.id
        dismissTutorial()
        enqueue {
            // 操作 + 下一张看过 + 滑动计数在仓库里合成一次 DataStore 写入（一次滑动只重写一次文件）
            val effects = SwipeSideEffects(markSeenId = nextPhotoId, countSwipe = true)
            val result = safeOperation {
                when (type) {
                    PhotoActionType.Favorite -> favoritePhotoUseCase(photo, effects)
                    PhotoActionType.Unfavorite -> removeFavoritePhotoUseCase(photo, effects)
                    PhotoActionType.MoveToTrash -> movePhotoToTrashUseCase(photo, effects)
                }
            }
            if (!awaitSuccess(result)) {
                // 操作没写成，附带写入也没落盘：下一张照样记为看过（它已经显示出来了），计数不加
                nextPhotoId?.let { id -> runCatchingNonCancel { markPhotoSeenUseCase(id) } }
                // 插回原位（序列按会话初始顺序生成，移出 removed 即回到原位置）
                core.update { s ->
                    val l = s.ledger ?: return@update s
                    s.copy(
                        ledger = l.undoOutcome(photoId),
                        removed = s.removed - photoId,
                        undo = if (s.undo?.photo?.id == photoId) null else s.undo,
                        error = failureMessage(result, type),
                    )
                }
            }
        }
        return next?.key
    }

    private fun enqueue(op: suspend () -> Unit) {
        operations.trySend(op)
    }

    /** 写入失败（存储满等）按普通失败处理：照片插回原位并提示，而不是让异常打断队列或闪退。 */
    private suspend fun safeOperation(block: suspend () -> PhotoOperationResult): PhotoOperationResult = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "清理操作写入失败", e)
        PhotoOperationResult.Failure(PhotoOperationFailureReason.MediaStoreFailed)
    }

    /** 尽力而为的附带写入（看过 / 计数）：失败忽略，但不吞掉取消。 */
    private suspend fun runCatchingNonCancel(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "附带写入失败", e)
        }
    }

    /** NeedSystemApproval 时等用户在系统页的结果；取消算失败但不提示。 */
    private suspend fun awaitSuccess(result: PhotoOperationResult): Boolean = when (result) {
        is PhotoOperationResult.Success -> true
        is PhotoOperationResult.Failure -> false
        is PhotoOperationResult.NeedSystemApproval -> {
            val deferred = CompletableDeferred<Boolean>()
            pendingApproval = deferred
            _effects.send(ReviewEffect.LaunchSystemMediaAction(result.request))
            deferred.await()
        }
    }

    private fun nextKeyAfterRemoval(c: Core, photoId: Long): String? {
        val n = neighborsOf(c, photoId) ?: return null
        return (n.next ?: n.previous)?.key
    }

    private fun neighborsOf(c: Core, photoId: Long): BuildReviewPagesUseCase.Neighbors? {
        val ledger = c.ledger ?: return null
        return buildReviewPagesUseCase.neighborsOf(ledger, c.removed, photoIdOf(c.currentKey), c.activeSummaryDay, photoId)
    }

    private fun pagesOf(c: Core): List<ReviewPage> {
        val ledger = c.ledger ?: return emptyList()
        return buildReviewPagesUseCase(ledger, c.removed, photoIdOf(c.currentKey), c.activeSummaryDay)
    }

    private fun buildUiState(
        c: Core,
        library: LibraryDayState,
        hints: Boolean,
        tutorialSeen: Boolean,
        trashCount: Int,
    ): ReviewUiState {
        val ledger = c.ledger
        val pages = pagesOf(c)
        val progress = HashMap<String, com.winters.cleanphotos.domain.model.SessionDayProgress>()
        val summaries = HashMap<String, com.winters.cleanphotos.domain.model.DaySummary>()
        if (ledger != null) {
            val i = pages.indexOfFirst { it.key == c.currentKey }
            if (i >= 0) {
                for (j in (i - 1).coerceAtLeast(0)..(i + 1).coerceAtMost(pages.lastIndex)) {
                    val page = pages[j]
                    progress[page.key] = when (page) {
                        is ReviewPage.Photo -> buildSessionDayProgressUseCase(ledger, page.photo.id)
                        is ReviewPage.Summary -> buildSessionDayProgressUseCase(ledger, page.dayKey, null)
                    }
                }
            }
            pages.filterIsInstance<ReviewPage.Summary>().forEach { s ->
                summaries[s.dayKey] = buildDaySummaryUseCase(
                    ledger, s.dayKey, library.libraryPhotos, library.processedIds, library.seenIds, LocalDate.now(),
                )
            }
        }
        return ReviewUiState(
            isLoading = c.loading,
            scope = c.scope,
            pages = pages,
            currentKey = c.currentKey,
            progress = progress,
            summaries = summaries,
            showDirectionHints = hints,
            showTutorial = !tutorialSeen && !c.tutorialDismissed && !c.loading,
            undo = c.undo,
            trashCount = trashCount,
            errorMessage = c.error,
            loadFailed = c.loadFailed,
            isCompleted = !c.loading && ledger != null && c.currentKey == null,
        )
    }

    /**
     * 内容没变的部分沿用上一份实例：垃圾桶计数、看过状态这类变化不该让清理页按「序列变了」整台重组。
     */
    private fun reuseUnchanged(next: ReviewUiState): ReviewUiState {
        val prev = lastUi
        val result = if (prev == null) next else next.copy(
            pages = if (next.pages == prev.pages) prev.pages else next.pages,
            progress = if (next.progress == prev.progress) prev.progress else next.progress,
            summaries = if (next.summaries == prev.summaries) prev.summaries else next.summaries,
        )
        lastUi = result
        return result
    }

    private fun undoTitle(type: PhotoActionType) = when (type) {
        PhotoActionType.Favorite -> UiText.Res(R.string.undo_favorited)
        PhotoActionType.Unfavorite -> UiText.Res(R.string.undo_unfavorited)
        PhotoActionType.MoveToTrash -> UiText.Res(R.string.undo_trashed)
    }

    /** 移走：「可释放 2.4 MB」；收藏 / 取消收藏：「9月21日 · 第 3 张」（这一天会话里的第几张）。 */
    private fun undoSubtitle(ledger: ReviewLedger, photo: PhotoItem, type: PhotoActionType): UiText = when (type) {
        PhotoActionType.MoveToTrash -> UiText.Res(R.string.undo_sub_freeable, UiText.Bytes(photo.size ?: 0L))
        else -> {
            val n = ledger.photosOfDay(photo.dateGroup).indexOfFirst { it.id == photo.id } + 1
            UiText.Res(R.string.undo_sub_position, UiText.Date(DayDisplay.reviewDayOrdinal(photo)), n)
        }
    }

    private fun failureMessage(result: PhotoOperationResult, type: PhotoActionType): UiText? {
        val reason = (result as? PhotoOperationResult.Failure)?.reason ?: return null
        val id = when (reason) {
            PhotoOperationFailureReason.SystemFavoriteUnsupported -> R.string.fail_favorite_unsupported
            PhotoOperationFailureReason.PermissionDenied -> when (type) {
                PhotoActionType.Favorite -> R.string.fail_permission_favorite
                PhotoActionType.Unfavorite -> R.string.fail_permission_unfavorite
                PhotoActionType.MoveToTrash -> R.string.fail_permission_trash
            }
            PhotoOperationFailureReason.UserCancelled -> return null
            PhotoOperationFailureReason.MediaStoreFailed -> when (type) {
                PhotoActionType.Favorite -> R.string.fail_favorite
                PhotoActionType.Unfavorite -> R.string.fail_unfavorite
                PhotoActionType.MoveToTrash -> R.string.fail_trash
            }
            PhotoOperationFailureReason.WritePermissionRequired -> R.string.fail_permission
        }
        return UiText.Res(id)
    }

    companion object {
        private const val TAG = "ReviewViewModel"
        internal const val KEY_CURRENT_PHOTO = "review_current_photo"
        private val EMPTY_LIBRARY = LibraryDayState(emptyList(), emptySet(), emptySet())

        fun photoIdOf(key: String?): Long? =
            key?.takeIf { it.startsWith("p") }?.substring(1)?.toLongOrNull()

        fun summaryDayOf(key: String?): String? = key?.takeIf { it.startsWith("s") }?.substring(1)

        private fun outcomeOf(type: PhotoActionType) = when (type) {
            PhotoActionType.Favorite -> ReviewOutcome.Favorited
            PhotoActionType.Unfavorite -> ReviewOutcome.Unfavorited
            PhotoActionType.MoveToTrash -> ReviewOutcome.Trashed
        }
    }
}

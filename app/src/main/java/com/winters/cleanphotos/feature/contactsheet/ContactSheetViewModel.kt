package com.winters.cleanphotos.feature.contactsheet

import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.winters.cleanphotos.R
import com.winters.cleanphotos.core.coroutines.DefaultDispatcher
import com.winters.cleanphotos.core.design.FilingMotion
import com.winters.cleanphotos.core.text.UiText
import com.winters.cleanphotos.domain.model.AlbumRules
import com.winters.cleanphotos.domain.model.BatchAction
import com.winters.cleanphotos.domain.model.DayKey
import com.winters.cleanphotos.domain.model.FilingOutcome
import com.winters.cleanphotos.domain.model.PhotoAlbum
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationFailureReason
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.model.SystemMediaActionType
import com.winters.cleanphotos.domain.repository.PhotoRepository
import com.winters.cleanphotos.domain.usecase.BatchFavoritePhotosUseCase
import com.winters.cleanphotos.domain.usecase.BuildAlbumsUseCase
import com.winters.cleanphotos.domain.usecase.FilePhotosToAlbumUseCase
import com.winters.cleanphotos.domain.usecase.ObserveFilingLogUseCase
import com.winters.cleanphotos.domain.usecase.PrepareAlbumMoveUseCase
import com.winters.cleanphotos.domain.usecase.RequestSystemFavoriteUseCase
import com.winters.cleanphotos.domain.usecase.UndoBatchActionUseCase
import com.winters.cleanphotos.domain.usecase.UndoFilingUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 印样表：圈选这一天的收藏，装进底片袋（系统相册文件夹）或取消收藏。
 * 装袋前先拿写权限（Android 11+ 系统整批确认一次；8–10 要存储写权限），授权通过后才移动并播飞入。
 */
@HiltViewModel
class ContactSheetViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val photoRepository: PhotoRepository,
    private val buildAlbums: BuildAlbumsUseCase,
    private val observeFilingLog: ObserveFilingLogUseCase,
    private val prepareMove: PrepareAlbumMoveUseCase,
    private val filePhotos: FilePhotosToAlbumUseCase,
    private val undoFiling: UndoFilingUseCase,
    private val requestSystemFavorite: RequestSystemFavoriteUseCase,
    private val batchFavorite: BatchFavoritePhotosUseCase,
    private val undoBatch: UndoBatchActionUseCase,
    @DefaultDispatcher private val computeDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val dayKey: String = requireNotNull(savedStateHandle["dayKey"]) { "印样表缺少 dayKey" }

    private val _uiState = MutableStateFlow(
        ContactSheetUiState(
            dayKey = dayKey,
            date = DayKey.parse(dayKey),
            systemFavoriteSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
        ),
    )
    val uiState: StateFlow<ContactSheetUiState> = _uiState.asStateFlow()

    private val _effects = Channel<ContactSheetEffect>(Channel.BUFFERED)
    val effects: Flow<ContactSheetEffect> = _effects.receiveAsFlow()

    /** 等系统授权 / 写权限回来再做的那一步。 */
    private sealed interface Pending {
        /** 真要换文件夹的照片：已经在目标文件夹的不移动，也不用为它们弹系统授权。 */
        val toMove: List<PhotoItem>
        data class File(val photos: List<PhotoItem>, val relativePath: String, val created: Boolean) : Pending {
            override val toMove: List<PhotoItem>
                get() = photos.filterNot { it.relativePath.equals(relativePath, ignoreCase = true) }
        }
        data class Undo(val outcome: FilingOutcome) : Pending {
            override val toMove: List<PhotoItem>
                get() = outcome.moved.filterNot { it.fromPath.equals(outcome.relativePath, ignoreCase = true) }.map { it.photo }
        }
    }

    private var pending: Pending? = null
    private var awaiting: SystemMediaActionType? = null
    private var seq = 0L

    /** 撤回能撤的最后一次动作。 */
    private var lastFiling: FilingOutcome? = null
    private var lastUnfavorite: BatchAction? = null

    /** 本次进入的战果（小结用）：path → 张数；新建的袋子；取消收藏张数。 */
    private val filed = LinkedHashMap<String, Int>()
    private val createdPaths = HashSet<String>()
    private var unfavorited = 0
    private var acted = false

    private var dayOrder: List<Pair<String, List<PhotoItem>>> = emptyList()

    init {
        viewModelScope.launch {
            photoRepository.ensurePhotosLoaded()
        }
        viewModelScope.launch {
            combine(
                photoRepository.observeFavoritedPhotos(),
                photoRepository.observeAllPhotos(),
                observeFilingLog(),
            ) { favorites, others, log ->
                val days = favorites.groupBy { it.dateGroup }
                    .map { (key, photos) -> key to photos.sortedBy { it.dateTaken ?: 0L } }
                    .sortedByDescending { (key, _) -> DayKey.parse(key)?.toEpochDay() ?: Long.MIN_VALUE }
                Snapshot(days, buildAlbums(favorites + others, log))
            }
                .flowOn(computeDispatcher)
                .collect { snap ->
                    dayOrder = snap.days
                    val photos = snap.days.firstOrNull { it.first == dayKey }?.second.orEmpty()
                    val ids = photos.mapTo(HashSet()) { it.id }
                    _uiState.update {
                        it.copy(
                            photos = photos,
                            circled = it.circled.filterTo(HashSet()) { id -> id in ids },
                            albums = snap.albums,
                            loaded = true,
                        ).withSummary()
                    }
                }
        }
    }

    private class Snapshot(val days: List<Pair<String, List<PhotoItem>>>, val albums: List<PhotoAlbum>)

    fun onToggle(id: Long) = _uiState.update {
        if (it.busy) it else it.copy(circled = if (id in it.circled) it.circled - id else it.circled + id)
    }

    /** 一笔划过：把经过的照片都圈上（不擦）。 */
    fun onCircle(id: Long) = _uiState.update {
        if (it.busy || id in it.circled) it else it.copy(circled = it.circled + id)
    }

    /** 「全圈」；已经全圈时清掉。 */
    fun onCircleAll() = _uiState.update {
        if (it.busy) return@update it
        val all = it.photos.mapTo(HashSet()) { p -> p.id }
        it.copy(circled = if (it.circled.containsAll(all)) emptySet() else all)
    }

    fun onSystemFavoriteToggle() = _uiState.update { it.copy(systemFavorite = !it.systemFavorite) }

    fun onFile(target: SheetTarget) {
        val state = _uiState.value
        if (state.busy) return
        val photos = state.photos.filter { it.id in state.circled }
        if (photos.isEmpty()) return
        val (path, created) = when (target) {
            is SheetTarget.Existing -> target.album.relativePath to false
            is SheetTarget.New -> {
                val existing = AlbumRules.existingFor(target.name, state.albums)
                (existing?.relativePath ?: AlbumRules.pathFor(target.name)) to (existing == null)
            }
        }
        start(Pending.File(photos, path, created))
    }

    fun onUnfavorite() {
        val state = _uiState.value
        if (state.busy) return
        val photos = state.photos.filter { it.id in state.circled }
        if (photos.isEmpty()) return
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            val (action, result) = batchFavorite(photos)
            if (result is PhotoOperationResult.Success) {
                acted = true
                unfavorited += action.count
                lastUnfavorite = action
                lastFiling = null
                _uiState.update {
                    it.copy(
                        busy = false,
                        circled = emptySet(),
                        undo = SheetUndo(++seq, photos.first(), UiText.Plural(R.plurals.batch_undo_unfavorite, action.count), favorite = true),
                    ).withSummary()
                }
            } else {
                _uiState.update { it.copy(busy = false, message = SheetMessage(++seq, UiText.Res(R.string.fail_unfavorite))) }
            }
        }
    }

    fun onUndo() {
        val state = _uiState.value
        if (state.busy || state.undo == null) return
        val filing = lastFiling
        val unfav = lastUnfavorite
        _uiState.update { it.copy(undo = null) }
        when {
            filing != null -> start(Pending.Undo(filing))
            unfav != null -> {
                lastUnfavorite = null
                _uiState.update { it.copy(busy = true) }
                viewModelScope.launch {
                    undoBatch(unfav)
                    unfavorited -= unfav.count
                    _uiState.update { it.copy(busy = false).withSummary() }
                }
            }
        }
    }

    fun onUndoExpired(expiredSeq: Long) = _uiState.update {
        if (it.undo?.seq == expiredSeq) it.copy(undo = null) else it
    }

    fun onSystemMediaActionResult(approved: Boolean) {
        val type = awaiting ?: return
        awaiting = null
        // 系统收藏：系统确认后自己写入，结果不影响 App 里的任何状态
        if (type == SystemMediaActionType.SystemFavorite) return
        if (approved) execute() else cancel()
    }

    fun onWritePermissionResult(granted: Boolean) {
        if (granted) prepare() else cancel()
    }

    private fun start(p: Pending) {
        pending = p
        _uiState.update { it.copy(busy = true) }
        prepare()
    }

    private fun prepare() {
        val p = pending ?: return
        val toMove = p.toMove
        if (toMove.isEmpty()) {
            execute()
            return
        }
        viewModelScope.launch {
            when (val r = prepareMove(toMove)) {
                PhotoOperationResult.Success -> execute()
                is PhotoOperationResult.NeedSystemApproval -> {
                    awaiting = r.request.actionType
                    _effects.send(ContactSheetEffect.LaunchSystemAction(r.request))
                }
                is PhotoOperationResult.Failure -> {
                    check(r.reason == PhotoOperationFailureReason.WritePermissionRequired) { "未预期的授权结果：${r.reason}" }
                    _effects.send(ContactSheetEffect.RequestWritePermission)
                }
            }
        }
    }

    private fun cancel() {
        pending = null
        _uiState.update { it.copy(busy = false) }
        _effects.trySend(ContactSheetEffect.Bounce)
    }

    private fun execute() {
        val p = pending ?: return
        pending = null
        viewModelScope.launch {
            when (p) {
                is Pending.File -> file(p)
                is Pending.Undo -> undo(p.outcome)
            }
        }
    }

    private suspend fun file(p: Pending.File) {
        val outcome = filePhotos(p.photos, p.relativePath)
        val failedIds = outcome.failed.mapTo(HashSet()) { it.id }
        val moved = outcome.moved.map { it.photo }
        if (moved.isNotEmpty()) {
            acted = true
            filed.merge(p.relativePath, moved.size, Int::plus)
            if (p.created) createdPaths += p.relativePath
            lastFiling = outcome
            lastUnfavorite = null
            _effects.send(ContactSheetEffect.Flight(++seq, moved, p.relativePath))
        } else {
            _effects.send(ContactSheetEffect.Bounce)
        }
        val name = AlbumRules.nameOf(p.relativePath)
        _uiState.update { s ->
            s.copy(
                busy = false,
                circled = s.circled.filterTo(HashSet()) { it in failedIds },
                undo = if (moved.isEmpty()) s.undo else SheetUndo(++seq, moved.first(), UiText.Plural(R.plurals.sheet_undo_filed, moved.size, listOf(moved.size, name)), favorite = false),
                message = if (failedIds.isEmpty()) s.message else SheetMessage(++seq, UiText.Plural(R.plurals.sheet_filing_failed, failedIds.size)),
            ).withSummary()
        }
        if (moved.isNotEmpty() && _uiState.value.systemFavorite) {
            // 等飞入、合盖播完再弹系统确认，别把动画盖住
            delay(FilingMotion.TOTAL.toLong())
            requestSystemFavorite(moved)?.let { r ->
                check(r is PhotoOperationResult.NeedSystemApproval) { "系统收藏请求异常：$r" }
                awaiting = r.request.actionType
                _effects.send(ContactSheetEffect.LaunchSystemAction(r.request))
            }
        }
    }

    private suspend fun undo(outcome: FilingOutcome) {
        val failed = undoFiling(outcome)
        lastFiling = null
        val back = outcome.moved.size - failed.size
        filed.merge(outcome.relativePath, -back, Int::plus)
        if (filed[outcome.relativePath] == 0) {
            filed.remove(outcome.relativePath)
            createdPaths -= outcome.relativePath
        }
        _uiState.update {
            it.copy(
                busy = false,
                message = if (failed.isEmpty()) it.message else SheetMessage(++seq, UiText.Plural(R.plurals.sheet_undo_failed, failed.size)),
            ).withSummary()
        }
    }

    /** 这一天已经没有待整理、且本次进入做过动作：出小结。撤回让照片回来时小结收起。 */
    private fun ContactSheetUiState.withSummary(): ContactSheetUiState {
        if (!loaded || photos.isNotEmpty() || !acted) return copy(summary = null)
        val albums = filed.map { (path, n) ->
            val album = this.albums.firstOrNull { it.relativePath == path }
            SummaryAlbum(path, AlbumRules.nameOf(path), n, album?.count ?: n, album?.recent?.firstOrNull(), path in createdPaths)
        }
        return copy(summary = SheetSummary(albums, unfavorited, nextDay()))
    }

    /** 下一天：收藏页顺序（新 → 旧）里这一天之后最近的一天；没有更旧的就回到最新的一天。 */
    private fun nextDay(): NextDay? {
        val others = dayOrder.filter { it.first != dayKey }
        if (others.isEmpty()) return null
        val here = DayKey.parse(dayKey)?.toEpochDay()
        val older = here?.let { h -> others.firstOrNull { (k, _) -> (DayKey.parse(k)?.toEpochDay() ?: Long.MIN_VALUE) < h } }
        val (key, photos) = older ?: others.first()
        return NextDay(key, DayKey.parse(key), photos.size, photos.first())
    }
}

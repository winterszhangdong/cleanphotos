package com.winters.cleanphotos.feature.allphotos

import kotlinx.coroutines.CancellationException
import android.util.Log
import com.winters.cleanphotos.core.text.UiText
import com.winters.cleanphotos.R
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.winters.cleanphotos.core.permission.PermissionChecker
import com.winters.cleanphotos.domain.model.PhotoFilterMode
import com.winters.cleanphotos.domain.model.PhotoFilterRules
import com.winters.cleanphotos.domain.model.PhotoSortOrder
import com.winters.cleanphotos.domain.model.ScopeLayout
import com.winters.cleanphotos.domain.repository.PhotoRepository
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.usecase.BatchFavoritePhotosUseCase
import com.winters.cleanphotos.domain.usecase.BatchMovePhotosToTrashUseCase
import com.winters.cleanphotos.domain.usecase.BuildPhotoSectionsUseCase
import com.winters.cleanphotos.domain.usecase.UndoBatchActionUseCase
import com.winters.cleanphotos.domain.usecase.FilterPhotosUseCase
import com.winters.cleanphotos.domain.usecase.ObserveAllPhotosUseCase
import com.winters.cleanphotos.domain.usecase.ObserveAllSeenStatsUseCase
import com.winters.cleanphotos.domain.usecase.ObserveSeenIdsUseCase
import com.winters.cleanphotos.domain.usecase.SortPhotosUseCase
import com.winters.cleanphotos.core.coroutines.DefaultDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * 照片页。数据链：ObserveAllPhotos + ObserveSeenIds + ObserveAllSeenStats → 排序 → 范围 → 未看叠加 → BuildPhotoSections。
 * 「看过」状态只经由 UseCase 读取。范围和「只看没看过」都不持久化。
 */
@HiltViewModel
class AllPhotosViewModel @Inject constructor(
    application: Application,
    private val photoRepository: PhotoRepository,
    private val observeAllPhotosUseCase: ObserveAllPhotosUseCase,
    private val sortPhotosUseCase: SortPhotosUseCase,
    private val filterPhotosUseCase: FilterPhotosUseCase,
    private val buildPhotoSectionsUseCase: BuildPhotoSectionsUseCase,
    private val observeSeenIdsUseCase: ObserveSeenIdsUseCase,
    private val observeAllSeenStatsUseCase: ObserveAllSeenStatsUseCase,
    private val batchFavoritePhotosUseCase: BatchFavoritePhotosUseCase,
    private val batchMovePhotosToTrashUseCase: BatchMovePhotosToTrashUseCase,
    private val undoBatchActionUseCase: UndoBatchActionUseCase,
    @DefaultDispatcher private val computeDispatcher: CoroutineDispatcher,
) : AndroidViewModel(application) {

    private val _selection = MutableStateFlow(PhotoSelectionState())
    val selection: StateFlow<PhotoSelectionState> = _selection.asStateFlow()

    /** 当前范围叠上「只看没看过」之后的完整列表（按显示顺序）。全选、批量操作、选中集合都用它。 */
    private var selectablePhotos: List<PhotoItem> = emptyList()
    private var selectableById: Map<Long, PhotoItem> = emptyMap()
    /** 屏幕上露出的格子，按显示顺序。拖选取这个区间，不包含按天被收起的照片。 */
    private var gridPhotos: List<PhotoItem> = emptyList()
    /** 长按开始拖选那一刻已选中的集合；拖选结果 = 它 ∪ 起点到当前格的区间。 */
    private var dragBase: Set<Long> = emptySet()
    /** 本次拖选的起点；长按没生效（处理中 / 不在列表）时为 null，拖动回调直接忽略。 */
    private var dragAnchor: Long? = null
    private var undoSeq = 0L

    /** 排序、范围、「只看没看过」不持久化，进入页面时为默认值。 */
    private val _sortOrder = MutableStateFlow(PhotoSortOrder.NewestFirst)
    private val _scope = MutableStateFlow(LibraryScope.All)
    private val _unseenOnly = MutableStateFlow(false)

    private val initialPermission = PermissionChecker.hasPhotoPermission(application)
    private val enteredWithPhotoPermission = initialPermission
    private val _uiState = MutableStateFlow(
        AllPhotosUiState(hasPermission = initialPermission, isLoading = initialPermission),
    )
    val uiState: StateFlow<AllPhotosUiState> = _uiState.asStateFlow()

    init {
        observePhotos()
        if (initialPermission) refreshPhotos()
    }

    private fun observePhotos() {
        viewModelScope.launch {
            combine(
                combine(
                    observeAllPhotosUseCase(),
                    _sortOrder,
                    _scope,
                    _unseenOnly,
                    observeSeenIdsUseCase(),
                ) { allPhotos, sortOrder, scope, unseenOnly, seenIds ->
                    PhotoFrame(allPhotos, sortOrder, scope, unseenOnly, seenIds)
                },
                observeAllSeenStatsUseCase(),
            ) { frame, stats ->
                val allPhotos = frame.allPhotos
                val sortOrder = frame.sortOrder
                val scope = frame.scope
                val unseenOnly = frame.unseenOnly
                val seenIds = frame.seenIds
                // shortcut: 今天只在照片列表 / 看过状态变化时重算，跨过午夜不会自动刷新；要精确就加一个按日期变化的 Flow
                val today = LocalDate.now()
                // 相似不吃范围过滤，网格仍按「全部」+ 菜单排序留着。大文件只在这个范围按大小排，不排整库两次。
                val dateSorted = if (scope == LibraryScope.LargeFiles) null else sortPhotosUseCase(allPhotos, sortOrder)
                val scoped = when (scope) {
                    LibraryScope.Similar -> dateSorted!!
                    LibraryScope.LargeFiles -> sortPhotosUseCase(
                        allPhotos.filter { PhotoFilterRules.matches(it, PhotoFilterMode.LargeFiles, today) },
                        PhotoSortOrder.LargestFirst,
                    )
                    else -> filterPhotosUseCase(dateSorted!!, scope.toFilterMode(), seenIds, unseenOnly = false, today)
                }
                val applyUnseen = unseenOnly && scope != LibraryScope.Similar
                val visible = if (applyUnseen) scoped.filter { it.id !in seenIds } else scoped
                val onThisDayCount = if (scope == LibraryScope.OnThisDay) {
                    scoped.size
                } else {
                    allPhotos.count { PhotoFilterRules.matches(it, PhotoFilterMode.OnThisDay, today) }
                }
                val layout = if (scope == LibraryScope.Similar) ScopeLayout.Day else scope.layout
                val sections = buildPhotoSectionsUseCase(visible, dayPool = scoped, seenIds = seenIds, layout = layout)
                Listed(
                    state = AllPhotosUiState(
                        totalCount = allPhotos.size,
                        onThisDayCount = onThisDayCount,
                        scopeCount = scoped.size,
                        visibleCount = visible.size,
                        visibleBytes = visible.sumOf { it.size ?: 0L },
                        sortOrder = sortOrder,
                        scope = scope,
                        unseenOnly = unseenOnly,
                        sections = sections,
                        seenIds = seenIds,
                        allSeenStats = stats,
                        hangPhotos = dateSorted?.take(HANG_PHOTO_COUNT).orEmpty(),
                    ),
                    selectable = visible,
                    selectableById = visible.associateBy { it.id },
                    gridPhotos = sections.flatMap { it.photos },
                )
            }
                // 排序 / 分组整个相册不在主线程：本页留在返回栈里时，清理页每次滑动都会让这里重算一遍
                .flowOn(computeDispatcher)
                .collect { listed ->
                    selectablePhotos = listed.selectable
                    selectableById = listed.selectableById
                    gridPhotos = listed.gridPhotos
                    // 列表变了（照片被移走、范围切换）：选中集合只保留还在完整列表里的；一张不剩即退出多选
                    updateSelection(_selection.value.selectedIds)
                    val now = _uiState.value
                    _uiState.value = listed.state.copy(
                        isLoading = now.isLoading,
                        isInitialLoad = now.isInitialLoad,
                        hasPermission = now.hasPermission,
                        errorMessage = now.errorMessage,
                    )
                }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        if (granted && !_uiState.value.hasPermission) {
            _uiState.value = _uiState.value.copy(hasPermission = true)
            refreshPhotos()
        }
    }

    /**
     * 冷启动进入本页时已经有照片权限，才补一次视频权限。
     * 同一次进程里后来才授权照片的，不接着再弹（首次弹窗已经带了视频权限）。
     */
    fun consumeMissingVideoPermissionRequest(): Array<String>? {
        if (!enteredWithPhotoPermission) return null
        return PermissionChecker.consumeMissingVideoPermissionRequest(getApplication())
    }

    /** 补授权视频后重新读图库，否则这次进程里视频不会出现。 */
    fun onVideoPermissionGranted() {
        if (_uiState.value.hasPermission) refreshPhotos()
    }

    /** 从系统设置回来时重新检查（拒绝且不再询问 → 去系统设置开启）。 */
    fun recheckPermission() {
        onPermissionResult(PermissionChecker.hasPhotoPermission(getApplication()))
    }

    fun onSortOrderChanged(order: PhotoSortOrder) {
        _sortOrder.value = order
    }

    fun onScopeChanged(scope: LibraryScope) {
        if (scope == LibraryScope.Similar) updateSelection(emptySet())
        _scope.value = scope
    }

    fun onShowUnseenChanged(enabled: Boolean) {
        _unseenOnly.value = enabled
    }

    // ---------------------------------------------------------------- 多选

    /**
     * 长按某一格：进入多选并选中它（已在多选中则只是加选），同时作为拖选起点。
     * 相似范围不能多选。返回是否开始了拖选。
     */
    fun onLongPressPhoto(photoId: Long): Boolean {
        if (_scope.value == LibraryScope.Similar || gridPhotos.none { it.id == photoId } || _selection.value.isProcessing) {
            dragAnchor = null
            return false
        }
        dragBase = _selection.value.selectedIds
        dragAnchor = photoId
        updateSelection(dragBase + photoId)
        return true
    }

    /** 长按后不松手拖动：选中 = 长按前已选 ∪ 起点到当前格（按列表顺序）。 */
    fun onDragSelect(anchorId: Long, currentId: Long) {
        if (_scope.value == LibraryScope.Similar || anchorId != dragAnchor || _selection.value.isProcessing) return
        val from = gridPhotos.indexOfFirst { it.id == anchorId }
        val to = gridPhotos.indexOfFirst { it.id == currentId }
        if (from < 0 || to < 0) return
        val range = gridPhotos.subList(minOf(from, to), maxOf(from, to) + 1).map { it.id }
        updateSelection(dragBase + range)
    }

    /** 多选中点一格：切换选中；取消到一张不剩退出多选。 */
    fun onToggleSelection(photoId: Long) {
        val current = _selection.value.selectedIds
        updateSelection(if (photoId in current) current - photoId else current + photoId)
    }

    /** 组标题「全选」：这一组已全选 → 全部取消，否则补齐。按完整列表，不只是露出的格子。 */
    fun onToggleDay(dayKey: String) {
        val ids = _uiState.value.sections.firstOrNull { it.dayKey == dayKey }?.selectableIds ?: return
        if (ids.isEmpty()) return
        val current = _selection.value.selectedIds
        updateSelection(if (current.containsAll(ids)) current - ids.toSet() else current + ids)
    }

    /** 返回键 / 顶栏 ✕。 */
    fun onExitSelection() {
        updateSelection(emptySet())
    }

    fun onBatchFavorite() = runBatch { batchFavoritePhotosUseCase(it) }

    fun onBatchMoveToTrash() = runBatch { batchMovePhotosToTrashUseCase(it) }

    fun onUndoBatch() {
        val undo = _selection.value.undo ?: return
        _selection.value = _selection.value.copy(undo = null)
        viewModelScope.launch {
            // 写入失败（存储满等）不闪退：照片停在批量操作后的状态，日志记下原因
            val result = try {
                undoBatchActionUseCase(undo.action)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "撤回批量操作失败", e)
                return@launch
            }
            if (result !is PhotoOperationResult.Success) Log.e(TAG, "撤回批量操作失败：$result")
        }
    }

    fun onUndoExpired(seq: Long) {
        if (_selection.value.undo?.seq == seq) _selection.value = _selection.value.copy(undo = null)
    }

    private fun runBatch(block: suspend (List<PhotoItem>) -> Pair<com.winters.cleanphotos.domain.model.BatchAction, PhotoOperationResult>) {
        val state = _selection.value
        if (!state.isSelecting || state.isProcessing) return
        val selected = selectablePhotos.filter { it.id in state.selectedIds }
        _selection.value = state.copy(isProcessing = true)
        viewModelScope.launch {
            val outcome = try {
                block(selected)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "批量操作失败", e)
                null
            }
            if (outcome == null || outcome.second !is PhotoOperationResult.Success) {
                // 失败（存储满等）不闪退：保留多选和选中项，可以再试一次
                if (outcome != null) Log.e(TAG, "批量操作失败：${outcome.second}")
                _selection.value = _selection.value.copy(isProcessing = false)
                return@launch
            }
            val action = outcome.first
            // 动作完成后退出多选，并给出撤回胶囊
            _selection.value = PhotoSelectionState(undo = BatchUndo(++undoSeq, action, batchUndoTitle(action)))
        }
    }

    private fun updateSelection(ids: Set<Long>) {
        val kept = ids.filterTo(LinkedHashSet()) { it in selectableById }
        if (kept.isEmpty()) {
            // 退出多选：拖选起点和起点集合一起作废，下次长按重新开始
            dragBase = emptySet()
            dragAnchor = null
        }
        val current = _selection.value
        _selection.value = current.copy(
            selectedIds = kept,
            allSelectedFavorite = kept.isNotEmpty() && kept.all { selectableById.getValue(it).isFavorite },
        )
    }

    private fun refreshPhotos() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, hasPermission = true, errorMessage = null)
            try {
                photoRepository.refreshPhotos()
                _uiState.value = _uiState.value.copy(isLoading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "读取图库失败", e)
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = UiText.Res(R.string.error_load_photos))
            } finally {
                _uiState.value = _uiState.value.copy(isInitialLoad = false)
            }
        }
    }

    private companion object {
        const val TAG = "AllPhotosViewModel"
        const val HANG_PHOTO_COUNT = 3
    }
}

private data class PhotoFrame(
    val allPhotos: List<PhotoItem>,
    val sortOrder: PhotoSortOrder,
    val scope: LibraryScope,
    val unseenOnly: Boolean,
    val seenIds: Set<Long>,
)

private data class Listed(
    val state: AllPhotosUiState,
    val selectable: List<PhotoItem>,
    val selectableById: Map<Long, PhotoItem>,
    val gridPhotos: List<PhotoItem>,
)

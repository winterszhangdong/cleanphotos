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
import com.winters.cleanphotos.domain.model.PhotoSortOrder
import com.winters.cleanphotos.domain.model.SmartFilterRules
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
import javax.inject.Inject

/**
 * 照片页。数据链：ObserveAllPhotos + ObserveSeenIds + ObserveAllSeenStats → 排序 → 筛选 → BuildPhotoSections。
 * 「看过」状态只经由 UseCase 读取。
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

    /** 当前列表里可见的照片，按显示顺序（拖选按这个顺序取区间）。 */
    private var visiblePhotos: List<PhotoItem> = emptyList()
    private var visibleById: Map<Long, PhotoItem> = emptyMap()
    /** 长按开始拖选那一刻已选中的集合；拖选结果 = 它 ∪ 起点到当前格的区间。 */
    private var dragBase: Set<Long> = emptySet()
    /** 本次拖选的起点；长按没生效（处理中 / 不在列表）时为 null，拖动回调直接忽略。 */
    private var dragAnchor: Long? = null
    private var undoSeq = 0L

    /** 排序、筛选不持久化，进入页面时为默认值。 */
    private val _sortOrder = MutableStateFlow(PhotoSortOrder.NewestFirst)
    private val _filterMode = MutableStateFlow(PhotoFilterMode.AllPhotos)

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
                observeAllPhotosUseCase(),
                _sortOrder,
                _filterMode,
                observeSeenIdsUseCase(),
                observeAllSeenStatsUseCase(),
            ) { allPhotos, sortOrder, filterMode, seenIds, stats ->
                val sorted = sortPhotosUseCase(allPhotos, sortOrder)
                val filtered = filterPhotosUseCase(sorted, filterMode, seenIds)
                // 菜单数量按照片页全集算，不随「仅未看过」或当前筛选缩小
                var screenshotCount = 0
                var largeFileCount = 0
                var chatAppCount = 0
                for (photo in allPhotos) {
                    if (SmartFilterRules.isScreenshot(photo)) screenshotCount++
                    if (SmartFilterRules.isLargeFile(photo)) largeFileCount++
                    if (SmartFilterRules.isChatApp(photo)) chatAppCount++
                }
                val current = _uiState.value
                current.copy(
                    totalCount = allPhotos.size,
                    unseenCount = allPhotos.count { it.id !in seenIds },
                    screenshotCount = screenshotCount,
                    largeFileCount = largeFileCount,
                    chatAppCount = chatAppCount,
                    visibleCount = filtered.size,
                    sortOrder = sortOrder,
                    filterMode = filterMode,
                    sections = buildPhotoSectionsUseCase(filtered, dayPool = allPhotos, seenIds = seenIds),
                    seenIds = seenIds,
                    allSeenStats = stats,
                    hangPhotos = sorted.take(HANG_PHOTO_COUNT),
                )
            }
                // 排序 / 分组整个相册不在主线程：本页留在返回栈里时，清理页每次滑动都会让这里重算一遍
                .flowOn(computeDispatcher)
                .collect { state ->
                    visiblePhotos = state.sections.flatMap { it.photos }
                    visibleById = visiblePhotos.associateBy { it.id }
                    // 列表变了（照片被移走、筛选切换）：选中集合只保留还在列表里的；一张不剩即退出多选
                    updateSelection(_selection.value.selectedIds)
                    // 数据流更新不覆盖加载 / 权限 / 错误这几个由刷新流程维护的字段
                    val now = _uiState.value
                    _uiState.value = state.copy(
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

    fun onFilterModeChanged(mode: PhotoFilterMode) {
        _filterMode.value = mode
    }

    // ---------------------------------------------------------------- 多选

    /**
     * 长按某一格：进入多选并选中它（已在多选中则只是加选），同时作为拖选起点。
     * 返回是否开始了拖选；没开始时网格不接管后续拖动（列表照常滚动），也不会拿旧的起点集合去拖选。
     */
    fun onLongPressPhoto(photoId: Long): Boolean {
        if (photoId !in visibleById || _selection.value.isProcessing) {
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
        if (anchorId != dragAnchor || _selection.value.isProcessing) return
        val from = visiblePhotos.indexOfFirst { it.id == anchorId }
        val to = visiblePhotos.indexOfFirst { it.id == currentId }
        if (from < 0 || to < 0) return
        val range = visiblePhotos.subList(minOf(from, to), maxOf(from, to) + 1).map { it.id }
        updateSelection(dragBase + range)
    }

    /** 多选中点一格：切换选中；取消到一张不剩退出多选。 */
    fun onToggleSelection(photoId: Long) {
        val current = _selection.value.selectedIds
        updateSelection(if (photoId in current) current - photoId else current + photoId)
    }

    /** 天标题「全选这一天」：这一天已全选 → 全部取消，否则补齐。 */
    fun onToggleDay(dayKey: String) {
        val ids = _uiState.value.sections.firstOrNull { it.dayKey == dayKey }?.photos?.map { it.id } ?: return
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
        val selected = visiblePhotos.filter { it.id in state.selectedIds }
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
        val kept = ids.filterTo(LinkedHashSet()) { it in visibleById }
        if (kept.isEmpty()) {
            // 退出多选：拖选起点和起点集合一起作废，下次长按重新开始
            dragBase = emptySet()
            dragAnchor = null
        }
        val current = _selection.value
        _selection.value = current.copy(
            selectedIds = kept,
            allSelectedFavorite = kept.isNotEmpty() && kept.all { visibleById.getValue(it).isFavorite },
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

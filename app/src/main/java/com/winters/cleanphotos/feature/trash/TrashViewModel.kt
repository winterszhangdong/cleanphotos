package com.winters.cleanphotos.feature.trash

import kotlinx.coroutines.CancellationException
import com.winters.cleanphotos.core.text.UiText
import com.winters.cleanphotos.R
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.winters.cleanphotos.domain.model.MediaType
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationFailureReason
import com.winters.cleanphotos.domain.model.PlaybackTarget
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.model.TrashOldestDays
import com.winters.cleanphotos.domain.repository.PhotoRepository
import com.winters.cleanphotos.domain.usecase.DeletePhotosPermanentlyUseCase
import com.winters.cleanphotos.domain.usecase.ObserveTrashedPhotosUseCase
import com.winters.cleanphotos.domain.usecase.RestorePhotosFromTrashUseCase
import com.winters.cleanphotos.domain.usecase.RestorePhotoFromTrashUseCase
import com.winters.cleanphotos.domain.usecase.ObserveTrashMovedAtUseCase
import com.winters.cleanphotos.domain.usecase.ObserveTrashSummaryUseCase
import com.winters.cleanphotos.domain.usecase.AddFreedBytesUseCase
import com.winters.cleanphotos.domain.usecase.ObserveTotalFreedBytesUseCase
import com.winters.cleanphotos.domain.usecase.LocateMotionPhotoVideoUseCase
import com.winters.cleanphotos.domain.usecase.SumPhotoBytesUseCase
import com.winters.cleanphotos.core.coroutines.DefaultDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 垃圾桶页 ViewModel。
 *
 * 行为变化（App 内垃圾桶）：
 * - 单张/批量移出垃圾桶：直接成功，不弹系统窗
 * - 清空垃圾桶：先 App 确认框，再系统永久删除授权
 * - 删除成功后刷新 MediaStore 并校验残留
 */
@HiltViewModel
class TrashViewModel @Inject constructor(
    private val observeTrashedPhotosUseCase: ObserveTrashedPhotosUseCase,
    private val restorePhotoFromTrashUseCase: RestorePhotoFromTrashUseCase,
    private val restorePhotosFromTrashUseCase: RestorePhotosFromTrashUseCase,
    private val deletePhotosPermanentlyUseCase: DeletePhotosPermanentlyUseCase,
    private val photoRepository: PhotoRepository,
    private val observeTrashSummaryUseCase: ObserveTrashSummaryUseCase,
    private val observeTrashMovedAtUseCase: ObserveTrashMovedAtUseCase,
    private val sumPhotoBytesUseCase: SumPhotoBytesUseCase,
    private val observeTotalFreedBytesUseCase: ObserveTotalFreedBytesUseCase,
    private val addFreedBytesUseCase: AddFreedBytesUseCase,
    private val locateMotionPhotoVideoUseCase: LocateMotionPhotoVideoUseCase,
    @DefaultDispatcher private val computeDispatcher: CoroutineDispatcher,
) : ViewModel() {

    companion object {
        private const val TAG = "TrashViewModel"
    }

    private val _uiState = MutableStateFlow(TrashUiState())
    val uiState: StateFlow<TrashUiState> = _uiState.asStateFlow()

    // 一次性事件通道（系统授权）
    private val _effects = Channel<TrashEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    // 系统授权后待继续的操作
    private var pendingSystemAction: (suspend () -> Unit)? = null

    // 本轮清空已申请过写权限（Android 8–10）：重试仍缺权限时直接提示，不再循环申请
    private var writePermissionRequested = false
    private var playbackJob: Job? = null
    private var prefetchJob: Job? = null

    init {
        loadTrashedPhotos()
        observeTrashedPhotos()
        observeTrashDetails()
    }

    /** 「清空后可释放 X GB」、最早一张天数，与单张预览「N天前移入」。 */
    private fun observeTrashDetails() {
        viewModelScope.launch {
            observeTrashSummaryUseCase().collect { summary ->
                _uiState.value = _uiState.value.copy(totalBytes = summary.totalBytes)
            }
        }
        viewModelScope.launch {
            observeTrashMovedAtUseCase().collect { movedAt ->
                _uiState.value = _uiState.value.copy(
                    movedAt = movedAt,
                    oldestDays = TrashOldestDays.wholeDays(movedAt.values, System.currentTimeMillis()),
                )
            }
        }
        viewModelScope.launch {
            observeTotalFreedBytesUseCase().collect { bytes ->
                _uiState.value = _uiState.value.copy(totalFreedBytes = bytes)
            }
        }
    }

    /** 已选照片总大小。 */
    fun selectedBytes(state: TrashUiState = _uiState.value): Long =
        sumPhotoBytesUseCase(state.photos, state.selectedPhotoIds)

    /** 加载垃圾桶照片列表 */
    private fun loadTrashedPhotos() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                photoRepository.refreshTrashedPhotos()
                _uiState.value = _uiState.value.copy(isLoading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = UiText.Res(R.string.error_load_trash)
                )
            }
        }
    }

    /** 观察垃圾桶照片 Flow，自动更新列表 */
    private fun observeTrashedPhotos() {
        viewModelScope.launch {
            // 整库 associateBy 不在主线程：本页留在返回栈里时，清理页每次滑动写 DataStore 都会让这里重算
            observeTrashedPhotosUseCase().flowOn(computeDispatcher).collect { photos ->
                // isLoading 由刷新流程维护：首次刷新完成前照片表还是空的，不能先闪「垃圾桶是空的」
                val ids = photos.mapTo(HashSet()) { it.id }
                _uiState.update { now ->
                    // 预览中垃圾桶被外部清空（回到前台刷新、系统相册里删掉）：退出预览，不留一块没有照片的全屏黑底
                    val previewGone = now.screenMode == TrashScreenMode.Preview && photos.isEmpty()
                    now.copy(
                        photos = photos,
                        selectedPhotoIds = now.selectedPhotoIds.filterTo(HashSet()) { it in ids },
                        screenMode = if (previewGone) TrashScreenMode.Grid else now.screenMode,
                        previewPhotoId = if (previewGone) null else now.previewPhotoId,
                        playback = if (previewGone) null else now.playback,
                    )
                }
            }
        }
    }

    // ========== 网格模式操作 ==========

    /** 点击缩略图：进入全屏预览模式 */
    fun onPhotoClick(photo: PhotoItem) {
        val state = _uiState.value
        if (state.screenMode == TrashScreenMode.Grid) {
            _uiState.value = state.copy(
                screenMode = TrashScreenMode.Preview,
                previewPhotoId = photo.id,
            )
            publishPlayback(photo)
        }
    }

    /** 点击"选择"按钮：进入多选模式 */
    fun onEnterSelectionMode() {
        _uiState.value = _uiState.value.copy(
            screenMode = TrashScreenMode.Selection,
            selectedPhotoIds = emptySet()
        )
    }

    /** 点击"清空"按钮：显示确认弹框 */
    fun onShowEmptyTrashConfirm() {
        _uiState.value = _uiState.value.copy(showEmptyTrashConfirmDialog = true)
    }

    /** 关闭清空垃圾桶确认弹框 */
    fun onDismissEmptyTrashConfirm() {
        _uiState.value = _uiState.value.copy(showEmptyTrashConfirmDialog = false)
    }

    // ========== 多选模式操作 ==========

    /** 多选模式：点击缩略图切换选中状态 */
    fun onTogglePhotoSelection(photo: PhotoItem) {
        val state = _uiState.value
        if (state.screenMode != TrashScreenMode.Selection) return

        val newSelected = if (photo.id in state.selectedPhotoIds) {
            state.selectedPhotoIds - photo.id
        } else {
            state.selectedPhotoIds + photo.id
        }
        _uiState.value = state.copy(selectedPhotoIds = newSelected)
    }

    /** 退出多选模式 */
    fun onExitSelectionMode() {
        _uiState.value = _uiState.value.copy(
            screenMode = TrashScreenMode.Grid,
            selectedPhotoIds = emptySet()
        )
    }

    /**
     * 多选模式：批量移出垃圾桶。
     * App 内垃圾桶操作，直接成功，不弹系统窗。
     */
    fun onBatchRestore() {
        val state = _uiState.value
        if (state.selectedPhotoIds.isEmpty() || state.isProcessingAction) return

        val selectedPhotos = state.photos.filter { it.id in state.selectedPhotoIds }
        if (selectedPhotos.isEmpty()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessingAction = true)

            val result = safeWrite { restorePhotosFromTrashUseCase(selectedPhotos) }
            when (result) {
                is PhotoOperationResult.Success -> {
                    handleBatchRestoreSuccess()
                }
                // App 内垃圾桶操作不会返回 NeedSystemApproval
                is PhotoOperationResult.NeedSystemApproval -> {
                    Log.wtf(TAG, "App 内垃圾桶恢复不应触发系统授权")
                    handleBatchRestoreSuccess()
                }
                is PhotoOperationResult.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        isProcessingAction = false,
                        errorMessage = getFailureMessage(result.reason)
                    )
                }
            }
        }
    }

    // ========== 预览模式操作 ==========

    /** 退出预览模式 */
    fun onExitPreview() {
        playbackJob?.cancel()
        _uiState.value = _uiState.value.copy(
            screenMode = TrashScreenMode.Grid,
            previewPhotoId = null,
            playback = null,
        )
    }

    /** 预览模式：切换到指定照片 */
    fun onPreviewPhotoChange(photoId: Long) {
        _uiState.value = _uiState.value.copy(previewPhotoId = photoId)
        publishPlayback(_uiState.value.photos.find { it.id == photoId })
    }

    /** 这次停留垃圾桶页记住静音，离开预览不清。 */
    fun onPlaybackMuted(muted: Boolean) {
        _uiState.value = _uiState.value.copy(playbackMuted = muted)
    }

    private fun publishPlayback(photo: PhotoItem?) {
        playbackJob?.cancel()
        if (photo == null) {
            _uiState.update { it.copy(playback = null) }
            return
        }
        if (photo.mediaType == MediaType.Video) {
            _uiState.update { it.copy(playback = PlaybackTarget.Video(photo.uri)) }
            prefetchNext(photo.id)
            return
        }
        _uiState.update { it.copy(playback = null) }
        val id = photo.id
        playbackJob = viewModelScope.launch {
            val target = locateOrNull(photo)
            _uiState.update { state ->
                if (state.previewPhotoId == id && state.screenMode == TrashScreenMode.Preview) {
                    state.copy(playback = target)
                } else {
                    state
                }
            }
        }
        prefetchNext(photo.id)
    }

    private fun prefetchNext(photoId: Long) {
        val photos = _uiState.value.photos
        val index = photos.indexOfFirst { it.id == photoId }
        val next = photos.getOrNull(index + 1) ?: return
        if (next.mediaType != MediaType.Image) return
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch { locateOrNull(next) }
    }

    private suspend fun locateOrNull(photo: PhotoItem): PlaybackTarget? = try {
        locateMotionPhotoVideoUseCase.playbackTarget(photo)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "动态照片识别失败 id=${photo.id}", e)
        null
    }

    /**
     * 预览模式：恢复当前预览照片。
     * App 内垃圾桶操作，直接成功，不弹系统窗。
     *
     * 恢复前快照当前状态，确保后续索引计算基于恢复前快照而非刷新后的流状态，
     * 避免竞态导致 indexOfFirst == -1 引发闪退。
     */
    fun onRestorePreviewPhoto() {
        val state = _uiState.value
        val photoId = state.previewPhotoId ?: return
        val photo = state.photos.find { it.id == photoId } ?: return

        // 快照恢复前的照片列表和当前索引，供成功回调使用
        val preRestorePhotos = state.photos.toList()
        val preRestoreIndex = preRestorePhotos.indexOfFirst { it.id == photoId }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessingAction = true)

            val result = safeWrite { restorePhotoFromTrashUseCase(photo) }
            when (result) {
                is PhotoOperationResult.Success -> {
                    handlePreviewRestoreSuccess(preRestorePhotos, preRestoreIndex)
                }
                is PhotoOperationResult.NeedSystemApproval -> {
                    Log.wtf(TAG, "App 内垃圾桶恢复不应触发系统授权")
                    handlePreviewRestoreSuccess(preRestorePhotos, preRestoreIndex)
                }
                is PhotoOperationResult.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        isProcessingAction = false,
                        errorMessage = getFailureMessage(result.reason)
                    )
                }
            }
        }
    }

    // ========== 清空垃圾桶 ==========

    /**
     * 确认清空垃圾桶。
     * 发起系统永久删除授权，成功后刷新校验。
     */
    fun onConfirmEmptyTrash() {
        val state = _uiState.value
        // 确认框淡出期间按钮仍可点，连点会发两次系统删除请求
        if (state.photos.isEmpty() || state.isProcessingAction) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isProcessingAction = true,
                showEmptyTrashConfirmDialog = false
            )

            // 删除前拍快照：之后的 state 可能已被别的刷新改掉（Android 11+ 从系统删除确认回来时，
            // 回到前台的刷新可能先于授权结果跑完，列表已经没有被删的照片），溶解和残留校验都以快照为准
            val snapshot = TrashDissolve(state.photos, state.totalBytes)
            val result = safeWrite { deletePhotosPermanentlyUseCase(state.photos) }
            when (result) {
                is PhotoOperationResult.Success -> {
                    writePermissionRequested = false
                    handleEmptyTrashSuccess(snapshot)
                }
                is PhotoOperationResult.NeedSystemApproval -> {
                    pendingSystemAction = { handleEmptyTrashSuccess(snapshot) }
                    _effects.send(TrashEffect.LaunchSystemMediaAction(result.request))
                }
                is PhotoOperationResult.Failure -> {
                    if (result.reason == PhotoOperationFailureReason.WritePermissionRequired && !writePermissionRequested) {
                        // Android 8–10：先申请写权限，拿到后重试一次；保持 isProcessingAction 防止连点
                        writePermissionRequested = true
                        _effects.send(TrashEffect.RequestWritePermission)
                    } else {
                        writePermissionRequested = false
                        // Android 8–10 直接删除可能删掉了一部分：刷新列表，已删掉的不再显示、记录清掉
                        refreshPhotoLists()
                        forgetVanished(snapshot.photos)
                        _uiState.value = _uiState.value.copy(
                            isProcessingAction = false,
                            errorMessage = getFailureMessage(result.reason)
                        )
                    }
                }
            }
        }
    }

    /** 写权限申请结果（Android 8–10）。同意后重新发起清空；拒绝则提示，列表不变。 */
    fun onWritePermissionResult(granted: Boolean) {
        if (!writePermissionRequested) return
        if (granted) {
            _uiState.value = _uiState.value.copy(isProcessingAction = false)
            onConfirmEmptyTrash()
        } else {
            writePermissionRequested = false
            _uiState.value = _uiState.value.copy(
                isProcessingAction = false,
                errorMessage = getFailureMessage(PhotoOperationFailureReason.WritePermissionRequired)
            )
        }
    }

    // ========== 系统授权回调 ==========

    /**
     * 系统媒体授权结果回调。
     * 只在清空垃圾桶时触发。
     */
    fun onSystemMediaActionResult(authorized: Boolean) {
        viewModelScope.launch {
            if (authorized) {
                val pending = pendingSystemAction
                if (pending != null) {
                    pending()
                } else {
                    // 系统删除确认期间进程被回收：重建后没有快照可溶解，至少刷新列表并给出结果提示
                    handleEmptyTrashAfterRecreate()
                }
            } else {
                // 用户取消系统授权：静默处理，列表保持不变
                _uiState.value = _uiState.value.copy(isProcessingAction = false)
            }
            pendingSystemAction = null
        }
    }

    // ========== 通用操作 ==========

    /** 溶解播完。 */
    fun onDissolveFinished() {
        _uiState.value = _uiState.value.copy(dissolving = null)
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun dismissActionMessage() {
        _uiState.value = _uiState.value.copy(actionMessage = null)
    }

    /** 重试加载垃圾桶 */
    fun retryLoad() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
        loadTrashedPhotos()
    }

    // ========== 内部处理方法 ==========

    /**
     * 预览模式恢复成功后的切换逻辑。
     *
     * 完全基于恢复前快照 [preRestorePhotos] 和 [preRestoreIndex] 计算目标照片，
     * 先决定目标再刷新列表，避免 Flow 提前更新导致索引失效。
     *
     * @param preRestorePhotos 恢复前的照片列表快照
     * @param preRestoreIndex 恢复前当前照片在列表中的索引
     */
    private suspend fun handlePreviewRestoreSuccess(
        preRestorePhotos: List<PhotoItem>,
        preRestoreIndex: Int
    ) {
        // 基于恢复前快照计算剩余照片
        val remainingPhotos = preRestorePhotos.filterIndexed { index, _ -> index != preRestoreIndex }

        if (remainingPhotos.isEmpty()) {
            // 恢复前只有 1 张：退出 Preview，回到 Grid
            _uiState.value = _uiState.value.copy(
                screenMode = TrashScreenMode.Grid,
                previewPhotoId = null,
                isProcessingAction = false,
                actionMessage = UiText.Res(R.string.msg_restored),
                justFreedBytes = null,
            )
            // 目标已决定，再刷新列表
            refreshPhotoLists()
            return
        }

        // 目标照片规则：
        // 1. 原索引后还有照片 → 显示原下一张（保持原索引位置）
        // 2. 原索引已是最后一张 → 显示新的最后一张
        val targetPhoto = if (preRestoreIndex < remainingPhotos.size) {
            remainingPhotos[preRestoreIndex]
        } else {
            remainingPhotos.last()
        }

        // 先更新目标照片，再刷新列表
        _uiState.value = _uiState.value.copy(
            previewPhotoId = targetPhoto.id,
            isProcessingAction = false,
            actionMessage = UiText.Res(R.string.msg_restored),
            justFreedBytes = null,
        )
        // 补进来的这张不经过 onPreviewPhotoChange：不发布的话视频不播、点也没反应
        publishPlayback(targetPhoto)
        refreshPhotoLists()
    }

    /** 批量恢复成功：退出多选，回到网格 */
    private suspend fun handleBatchRestoreSuccess() {
        refreshPhotoLists()
        _uiState.value = _uiState.value.copy(
            screenMode = TrashScreenMode.Grid,
            selectedPhotoIds = emptySet(),
            isProcessingAction = false,
            actionMessage = UiText.Res(R.string.msg_restored),
            justFreedBytes = null,
        )
    }

    /**
     * 清空垃圾桶成功后：
     * 1. 刷新 MediaStore
     * 2. 校验本次待删照片是否真的消失
     * 3. 全部消失：显示"已清空垃圾桶"（失效的 AppTrashEntry 由刷新里的清理移除）
     * 4. 存在残留：显示失败提示
     */
    private suspend fun handleEmptyTrashSuccess(snapshot: TrashDissolve) {
        // 先挂上溶解快照再刷新：否则刷新后列表先变空，空状态会闪一帧（P5 录屏 13-sheet 第 9 格）
        _uiState.value = _uiState.value.copy(dissolving = snapshot, justFreedBytes = snapshot.totalBytes)
        refreshPhotoLists()

        // 刷新后检查残留
        val remainingIds = forgetVanished(snapshot.photos)

        if (remainingIds.isEmpty()) {
            // 全部删除成功
            _uiState.value = _uiState.value.copy(
                screenMode = TrashScreenMode.Grid,
                selectedPhotoIds = emptySet(),
                previewPhotoId = null,
                isProcessingAction = false,
                actionMessage = UiText.Res(R.string.msg_trash_emptied),
                // dissolving 已在刷新前挂上；这里不能再写一次：动画关闭时溶解会在刷新期间就播完并清空，
                // 再写回同一个快照会被 StateFlow 合并成「从未变化」，LaunchedEffect 不重启，页面卡在溶解态。
            )
        } else {
            // 部分残留
            Log.w(TAG, "清空垃圾桶后仍有 ${remainingIds.size} 张照片残留")
            _uiState.value = _uiState.value.copy(
                screenMode = TrashScreenMode.Grid,
                selectedPhotoIds = emptySet(),
                previewPhotoId = null,
                isProcessingAction = false,
                errorMessage = UiText.Res(R.string.error_partial_delete),
                dissolving = null,
                justFreedBytes = null,
            )
        }
    }

    /** 进程重建后收到系统删除授权结果：刷新，垃圾桶空了就提示已清空。 */
    private suspend fun handleEmptyTrashAfterRecreate() {
        // shortcut: 进程重建后没有删除前快照，这次释放的空间不计入累计；要精确就把快照存进 SavedStateHandle
        refreshPhotoLists()
        val remaining = observeTrashedPhotosUseCase().first()
        _uiState.value = _uiState.value.copy(
            isProcessingAction = false,
            actionMessage = if (remaining.isEmpty()) UiText.Res(R.string.msg_trash_emptied) else null,
        )
    }

    /**
     * 刷新后，快照里已经不在图库（垃圾桶列表）里的照片：清掉它们残留的垃圾桶 / 收藏 / 看过记录。
     * 系统授权删除、部分照片权限下刷新不做失效清理，不收尾的话这些记录会一直留着（A-D8）。
     * 返回刷新后仍在垃圾桶里的 id。
     */
    private suspend fun forgetVanished(snapshot: List<PhotoItem>): Set<Long> {
        val remainingIds = observeTrashedPhotosUseCase().first().mapTo(HashSet()) { it.id }
        val gone = snapshot.mapTo(HashSet()) { it.id } - remainingIds
        if (gone.isNotEmpty()) {
            try {
                deletePhotosPermanentlyUseCase.forgetDeleted(gone)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "清理已删除照片的记录失败", e)
            }
            // 只计刷新后确认不在垃圾桶里的；size 未知按 0。为 0 不写。
            val freed = sumPhotoBytesUseCase(snapshot, gone)
            if (freed > 0L) {
                // 照片已经删掉了：累计写不进去（存储满等）只少记一次，不能让清空流程崩掉
                try {
                    addFreedBytesUseCase(freed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "记录累计释放空间失败", e)
                }
            }
        }
        return remainingIds
    }

    /**
     * 刷新照片列表。
     * refreshTrashedPhotos() 内部委托 refreshPhotos()，只调一次避免重复扫描 MediaStore。
     */
    private suspend fun refreshPhotoLists() {
        try {
            photoRepository.refreshTrashedPhotos()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 刷新失败不影响操作结果
        }
    }

    /** DataStore 写入失败（存储满等）按普通失败提示，不闪退。 */
    private suspend fun safeWrite(block: suspend () -> PhotoOperationResult): PhotoOperationResult = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "垃圾桶操作失败", e)
        PhotoOperationResult.Failure(PhotoOperationFailureReason.MediaStoreFailed)
    }

    private fun getFailureMessage(reason: PhotoOperationFailureReason): UiText {
        return when (reason) {
            PhotoOperationFailureReason.SystemFavoriteUnsupported -> UiText.Res(R.string.fail_generic)
            PhotoOperationFailureReason.PermissionDenied -> UiText.Res(R.string.fail_permission)
            PhotoOperationFailureReason.UserCancelled -> UiText.Res(R.string.fail_cancelled)
            PhotoOperationFailureReason.MediaStoreFailed -> UiText.Res(R.string.fail_generic)
            PhotoOperationFailureReason.WritePermissionRequired -> UiText.Res(R.string.fail_write_permission)
        }
    }
}

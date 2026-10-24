package com.winters.cleanphotos.feature.similar

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.winters.cleanphotos.R
import com.winters.cleanphotos.core.text.UiText
import com.winters.cleanphotos.domain.model.BatchAction
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.model.SimilarGroup
import com.winters.cleanphotos.domain.model.SimilarMark
import com.winters.cleanphotos.domain.model.SimilarScan
import com.winters.cleanphotos.domain.repository.SimilarResolvedRepository
import com.winters.cleanphotos.domain.usecase.BatchMovePhotosToTrashUseCase
import com.winters.cleanphotos.domain.usecase.ObserveSimilarGroupsUseCase
import com.winters.cleanphotos.domain.usecase.UndoBatchActionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 相似照片。分组由 UseCase 给出；这里记住每张的留下 / 移走，以及提交后的撤回。
 * 标记不持久化。组还在（key 相同）时不被新一轮扫描盖掉；组没了就丢掉。
 */
@HiltViewModel
class SimilarViewModel @Inject constructor(
    private val observeSimilarGroupsUseCase: ObserveSimilarGroupsUseCase,
    private val batchMovePhotosToTrashUseCase: BatchMovePhotosToTrashUseCase,
    private val undoBatchActionUseCase: UndoBatchActionUseCase,
    private val similarResolvedRepository: SimilarResolvedRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SimilarUiState())
    val uiState: StateFlow<SimilarUiState> = _uiState.asStateFlow()

    private val marksByGroup = HashMap<String, MutableMap<Long, SimilarMark>>()
    private var undoSeq = 0L
    private var started = false

    /**
     * 第一次切到「相似」才开始比对。再调一次什么都不做，切走再回来不重算。
     */
    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            observeSimilarGroupsUseCase().collect { scan ->
                when (scan) {
                    is SimilarScan.Comparing -> _uiState.update {
                        it.copy(phase = SimilarPhase.Comparing, compared = scan.done, compareTotal = scan.total)
                    }
                    is SimilarScan.Ready -> publish(scan.groups)
                }
            }
        }
    }

    fun onMark(groupKey: String, photoId: Long, mark: SimilarMark) {
        if (_uiState.value.moving) return
        val map = marksByGroup[groupKey] ?: return
        if (photoId !in map || map[photoId] == mark) return
        map[photoId] = mark
        _uiState.update { it.copy(marks = snapshotMarks()) }
    }

    /**
     * 提交这一组：移走 [SimilarMark.Remove]。
     * 还会留在全部照片里的 [SimilarMark.Keep] 有两张及以上时写成一条已处理记录。
     * 不弹确认。成功后 [onDone]（对比页用来回到列表）。失败打日志，不出胶囊。
     */
    fun onCommit(groupKey: String, onDone: () -> Unit = {}) {
        val state = _uiState.value
        if (state.moving) return
        val group = state.groups.firstOrNull { it.key == groupKey } ?: return
        val marks = state.marks[groupKey] ?: return
        val remove = group.photos.filter { marks[it.id] == SimilarMark.Remove }
        val keepIds = group.photos.map { it.id }.filter { marks[it] == SimilarMark.Keep }.toSet()
        val resolved = keepIds.takeIf { it.size >= 2 }
        _uiState.update { it.copy(moving = true) }
        viewModelScope.launch {
            var trashed: BatchAction? = null
            try {
                if (remove.isNotEmpty()) {
                    val outcome = batchMovePhotosToTrashUseCase(remove)
                    if (outcome.second !is PhotoOperationResult.Success) {
                        Log.e(TAG, "移走相似照片失败：${outcome.second}")
                        _uiState.update { it.copy(moving = false) }
                        return@launch
                    }
                    trashed = outcome.first
                }
                if (resolved != null) similarResolvedRepository.addResolved(resolved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "提交相似组失败", e)
                rollback(trashed)
                _uiState.update { it.copy(moving = false) }
                return@launch
            }
            val thumb = remove.firstOrNull() ?: group.photos.first()
            _uiState.update {
                it.copy(
                    moving = false,
                    undo = SimilarUndo(
                        seq = ++undoSeq,
                        thumbId = thumb.id,
                        thumbModel = thumb.uri,
                        title = undoTitle(remove),
                        trashed = trashed,
                        resolvedIds = resolved,
                    ),
                )
            }
            onDone()
        }
    }

    fun onUndo() {
        val undo = _uiState.value.undo ?: return
        _uiState.update { it.copy(undo = null) }
        viewModelScope.launch {
            try {
                if (undo.resolvedIds != null) similarResolvedRepository.removeResolved(undo.resolvedIds)
                if (undo.trashed != null) {
                    val result = undoBatchActionUseCase(undo.trashed)
                    if (result !is PhotoOperationResult.Success) Log.e(TAG, "撤回移走相似照片失败：$result")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "撤回相似提交失败", e)
            }
        }
    }

    fun onUndoExpired(seq: Long) {
        if (_uiState.value.undo?.seq == seq) _uiState.update { it.copy(undo = null) }
    }

    private suspend fun rollback(trashed: BatchAction?) {
        if (trashed != null) {
            try {
                undoBatchActionUseCase(trashed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "回滚移走失败", e)
            }
        }
    }

    private fun publish(groups: List<SimilarGroup>) {
        val live = groups.mapTo(HashSet()) { it.key }
        marksByGroup.keys.retainAll(live)
        for (group in groups) {
            if (group.key !in marksByGroup) {
                marksByGroup[group.key] = defaultMarks(group).toMutableMap()
            }
        }
        _uiState.update {
            it.copy(phase = SimilarPhase.Ready, groups = groups, marks = snapshotMarks())
        }
    }

    private fun snapshotMarks(): Map<String, Map<Long, SimilarMark>> =
        marksByGroup.mapValues { it.value.toMap() }

    private fun defaultMarks(group: SimilarGroup): Map<Long, SimilarMark> =
        group.photos.associate { photo ->
            photo.id to if (photo.id == group.recommendedId) SimilarMark.Keep else SimilarMark.Remove
        }

    private fun undoTitle(remove: List<PhotoItem>): UiText =
        if (remove.isNotEmpty()) UiText.Plural(R.plurals.batch_undo_trash, remove.size)
        else UiText.Res(R.string.similar_undo_kept)

    private companion object {
        const val TAG = "SimilarViewModel"
    }
}

package com.winters.cleanphotos.feature.similar

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.model.SimilarGroup
import com.winters.cleanphotos.domain.model.SimilarScan
import com.winters.cleanphotos.domain.usecase.BatchMovePhotosToTrashUseCase
import com.winters.cleanphotos.domain.usecase.ObserveSimilarGroupsUseCase
import com.winters.cleanphotos.domain.usecase.UndoBatchActionUseCase
import com.winters.cleanphotos.feature.allphotos.BatchUndo
import com.winters.cleanphotos.feature.allphotos.batchUndoTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 相似照片。分组和默认保留由 UseCase 给出；这里只记住用户改过的保留项，以及移走后的撤回。
 */
@HiltViewModel
class SimilarViewModel @Inject constructor(
    private val observeSimilarGroupsUseCase: ObserveSimilarGroupsUseCase,
    private val batchMovePhotosToTrashUseCase: BatchMovePhotosToTrashUseCase,
    private val undoBatchActionUseCase: UndoBatchActionUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SimilarUiState())
    val uiState: StateFlow<SimilarUiState> = _uiState.asStateFlow()

    /** 用户点过的保留项。组还在（同一批照片）时盖过默认选择。 */
    private val keepOverrides = HashMap<String, Long>()
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
                    is SimilarScan.Ready -> _uiState.update {
                        it.copy(phase = SimilarPhase.Ready, groups = applyKeep(scan.groups))
                    }
                }
            }
        }
    }

    fun onKeep(groupKey: String, photoId: Long) {
        val state = _uiState.value
        if (state.moving) return
        val group = state.groups.firstOrNull { it.key == groupKey } ?: return
        if (group.photos.none { it.id == photoId } || group.keepId == photoId) return
        keepOverrides[groupKey] = photoId
        _uiState.update { it.copy(groups = applyKeep(it.groups)) }
    }

    /** 把这一组里没被留下的照片移入垃圾桶。不弹确认。 */
    fun onMoveRest(groupKey: String) {
        val state = _uiState.value
        if (state.moving) return
        val group = state.groups.firstOrNull { it.key == groupKey } ?: return
        val rest = group.photos.filter { it.id != group.keepId }
        check(rest.isNotEmpty()) { "一组里没有可移走的照片" }
        _uiState.update { it.copy(moving = true) }
        viewModelScope.launch {
            val outcome = try {
                batchMovePhotosToTrashUseCase(rest)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "移走相似照片失败", e)
                null
            }
            if (outcome == null || outcome.second !is PhotoOperationResult.Success) {
                if (outcome != null) Log.e(TAG, "移走相似照片失败：${outcome.second}")
                _uiState.update { it.copy(moving = false) }
                return@launch
            }
            _uiState.update {
                it.copy(
                    moving = false,
                    undo = BatchUndo(++undoSeq, outcome.first, batchUndoTitle(outcome.first)),
                )
            }
        }
    }

    fun onUndo() {
        val undo = _uiState.value.undo ?: return
        _uiState.update { it.copy(undo = null) }
        viewModelScope.launch {
            val result = try {
                undoBatchActionUseCase(undo.action)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "撤回移走相似照片失败", e)
                return@launch
            }
            if (result !is PhotoOperationResult.Success) Log.e(TAG, "撤回移走相似照片失败：$result")
        }
    }

    fun onUndoExpired(seq: Long) {
        if (_uiState.value.undo?.seq == seq) _uiState.update { it.copy(undo = null) }
    }

    private fun applyKeep(groups: List<SimilarGroup>): List<SimilarGroup> {
        if (keepOverrides.isEmpty()) return groups
        return groups.map { group ->
            val id = keepOverrides[group.key] ?: return@map group
            if (group.photos.any { it.id == id }) {
                group.copy(keepId = id)
            } else {
                keepOverrides.remove(group.key)
                group
            }
        }
    }

    private companion object {
        const val TAG = "SimilarViewModel"
    }
}

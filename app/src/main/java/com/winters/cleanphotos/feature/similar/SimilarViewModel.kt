package com.winters.cleanphotos.feature.similar

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.winters.cleanphotos.R
import com.winters.cleanphotos.core.coroutines.UptimeClock
import com.winters.cleanphotos.core.design.MotionDurations
import com.winters.cleanphotos.core.text.UiText
import com.winters.cleanphotos.domain.model.BatchAction
import com.winters.cleanphotos.domain.model.BatchActionType
import com.winters.cleanphotos.domain.model.MediaType
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.model.SimilarGroup
import com.winters.cleanphotos.domain.model.SimilarMark
import com.winters.cleanphotos.domain.model.SimilarScan
import com.winters.cleanphotos.domain.repository.SimilarResolvedRepository
import com.winters.cleanphotos.domain.usecase.BatchMovePhotosToTrashUseCase
import com.winters.cleanphotos.domain.usecase.EnsurePhotosLoadedUseCase
import com.winters.cleanphotos.domain.usecase.LocateMotionPhotoVideoUseCase
import com.winters.cleanphotos.domain.usecase.ObserveSimilarGroupsUseCase
import com.winters.cleanphotos.domain.usecase.UndoBatchActionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 相似照片。分组由 UseCase 给出；这里记住每张的留下 / 移走，以及提交或下滑删除后的撤回。
 * 标记不持久化。组还在（key 相同）时不被新一轮扫描盖掉；组没了就丢掉。
 * key 因为少了已删的照片而变掉时，把还在的标记带到新组上。
 */
@HiltViewModel
class SimilarViewModel @Inject constructor(
    private val observeSimilarGroupsUseCase: ObserveSimilarGroupsUseCase,
    private val batchMovePhotosToTrashUseCase: BatchMovePhotosToTrashUseCase,
    private val undoBatchActionUseCase: UndoBatchActionUseCase,
    private val similarResolvedRepository: SimilarResolvedRepository,
    private val ensurePhotosLoadedUseCase: EnsurePhotosLoadedUseCase,
    private val locateMotionPhotoVideoUseCase: LocateMotionPhotoVideoUseCase,
    private val uptimeClock: UptimeClock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SimilarUiState())
    val uiState: StateFlow<SimilarUiState> = _uiState.asStateFlow()

    private val marksByGroup = HashMap<String, MutableMap<Long, SimilarMark>>()
    /** 下滑删掉时的标记。失败或撤回时放回去；扫描已经把它拿掉时，等它回到某一组再放。 */
    private val hiddenMarks = HashMap<Long, SimilarMark>()
    private val pendingRestore = HashMap<Long, SimilarMark>()
    private var undoSeq = 0L
    /** 提交一开始就加一。还在写的下滑看到变了，就不再盖掉这次提交的撤回。 */
    private var actionEpoch = 0
    private var started = false
    private var compareWatchId: Long? = null
    private var compareJob: Job? = null
    /** 这次进相似页里已经确认是动态照片的 id。认不出的不缓存，下次停上再读。 */
    private val motionKnown = HashSet<Long>()

    /**
     * 第一次切到「相似」才开始比对。成功之后再调一次什么都不做，切走再回来不重算。
     * 照片表加载失败时可以再调：那次不算扫完。
     */
    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            // 冷启动直接进对比页时内存照片表是空的。先加载再订阅，避免空表立刻 Ready 把页面弹走。
            // 重试耗尽会抛出。这不是「没有相似照片」，不能当成 Ready(empty)；取消必须继续抛。
            if (_uiState.value.phase == SimilarPhase.Failed) {
                _uiState.update { it.copy(phase = SimilarPhase.Loading) }
            }
            try {
                ensurePhotosLoadedUseCase()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "加载照片失败", e)
                started = false
                _uiState.update {
                    it.copy(phase = SimilarPhase.Failed, compared = 0, compareTotal = 0, groups = emptyList(), marks = emptyMap())
                }
                return@launch
            }
            observeSimilarGroupsUseCase().collect { scan ->
                when (scan) {
                    // 出过一次结果后，图库或垃圾桶变了会整轮重跑：不再回到比对中，算完静默换掉
                    is SimilarScan.Comparing -> _uiState.update {
                        if (it.phase == SimilarPhase.Ready) it
                        else it.copy(phase = SimilarPhase.Comparing, compared = scan.done, compareTotal = scan.total)
                    }
                    is SimilarScan.Ready -> publish(scan.groups)
                }
            }
        }
    }

    /**
     * 对比页停在这张上。动态照片不进整库查询，这里按需读文件。
     * 换张立刻清掉上一张的结果；读失败当成不是动态照片。
     */
    fun watchComparePhoto(photo: PhotoItem) {
        if (compareWatchId == photo.id) return
        compareWatchId = photo.id
        compareJob?.cancel()
        val known = photo.id in motionKnown
        _uiState.update { it.copy(compareMotionId = photo.id.takeIf { known }) }
        if (known || photo.mediaType != MediaType.Image) return
        val id = photo.id
        compareJob = viewModelScope.launch {
            val motion = isMotion(photo)
            if (motion) motionKnown += id
            if (compareWatchId == id) {
                _uiState.update { it.copy(compareMotionId = id.takeIf { motion }) }
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
     * 对比页下滑：只把这一张立刻送进垃圾桶。组里其他标记不动。
     * 连着滑、上一条撤回还是这种下滑并且没过期，就并进同一条，到期从这一滑重新算。
     * 写完时如果用户已经点了提交，不再盖掉提交的撤回。失败则把这张放回组里。
     * 返回是否已经从组里拿掉（调用方用来飞走并切到下一张）。
     */
    fun onSwipeTrash(groupKey: String, photoId: Long): Boolean {
        val state = _uiState.value
        if (state.moving || photoId in state.hiddenIds) return false
        val group = state.groups.firstOrNull { it.key == groupKey }
        val held = state.remainder[groupKey]
            ?: state.remainder.values.firstOrNull { it.photo.id == photoId }
        val photo = group?.photos?.firstOrNull { it.id == photoId }
            ?: held?.photo?.takeIf { group == null && it.id == photoId }
            ?: return false
        val map = marksByGroup[groupKey]
        hiddenMarks[photoId] = map?.get(photoId) ?: held?.mark ?: SimilarMark.Remove
        map?.remove(photoId)
        _uiState.update { it.copy(hiddenIds = it.hiddenIds + photoId, marks = snapshotMarks()) }
        val epoch = actionEpoch
        viewModelScope.launch {
            val trashed = try {
                val outcome = batchMovePhotosToTrashUseCase(listOf(photo))
                if (outcome.second !is PhotoOperationResult.Success) {
                    Log.e(TAG, "下滑移入垃圾桶失败：${outcome.second}")
                    null
                } else {
                    outcome.first
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "下滑移入垃圾桶失败", e)
                null
            }
            if (trashed == null) {
                unhide(photoId)
                return@launch
            }
            if (epoch != actionEpoch) {
                hiddenMarks.remove(photoId)
                return@launch
            }
            mergeBurst(trashed.photos.firstOrNull() ?: photo)
        }
        return true
    }

    /**
     * 提交这一组：移走还标着 [SimilarMark.Remove]、并且不是刚下滑删掉的。
     * 还会留在全部照片里的 [SimilarMark.Keep] 有两张及以上时写成一条已处理记录。
     * 不弹确认。成功后 [onDone]（对比页用来回到列表）。失败打日志，不出胶囊。
     * 这一次撤回盖掉还没过期的下滑撤回。
     */
    fun onCommit(groupKey: String, onDone: () -> Unit = {}) {
        val state = _uiState.value
        if (state.moving) return
        val group = state.groups.firstOrNull { it.key == groupKey } ?: return
        val marks = state.marks[groupKey] ?: return
        val hidden = state.hiddenIds
        val remove = group.photos.filter { it.id !in hidden && marks[it.id] == SimilarMark.Remove }
        val keepIds = group.photos.map { it.id }.filter { it !in hidden && marks[it] == SimilarMark.Keep }.toSet()
        val resolved = keepIds.takeIf { it.size >= 2 }
        actionEpoch++
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
                        deadlineUptimeMillis = uptimeClock.uptimeMillis() + MotionDurations.UNDO_COUNTDOWN,
                        burst = false,
                    ),
                )
            }
            onDone()
        }
    }

    fun onUndo() {
        val undo = _uiState.value.undo ?: return
        if (uptimeClock.uptimeMillis() >= undo.deadlineUptimeMillis) {
            if (_uiState.value.undo?.seq == undo.seq) {
                forgetHiddenMarks(undo)
                _uiState.update { it.copy(undo = null) }
            }
            return
        }
        val ids = undo.trashed?.photos?.map { it.id }.orEmpty()
        restoreMarks(ids)
        _uiState.update { it.copy(undo = null, hiddenIds = it.hiddenIds - ids.toSet(), marks = snapshotMarks()) }
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
        val undo = _uiState.value.undo ?: return
        if (undo.seq != seq) return
        forgetHiddenMarks(undo)
        _uiState.update { it.copy(undo = null) }
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
        val previous = marksByGroup.mapValues { it.value.toMap() }
        val state = _uiState.value
        val hidden = state.hiddenIds
        val nextRemainder = soleRemainder(state.groups, groups, hidden, previous, state.remainder)
        val live = groups.mapTo(HashSet()) { it.key }
        for (group in groups) {
            if (group.key !in marksByGroup) {
                marksByGroup[group.key] = (migratedSimilarMarks(previous, group) ?: defaultMarks(group)).toMutableMap()
            }
            val map = marksByGroup.getValue(group.key)
            for (photo in group.photos) {
                if (photo.id !in hidden) pendingRestore.remove(photo.id)?.let { map[photo.id] = it }
            }
        }
        marksByGroup.keys.retainAll(live)
        val present = groups.flatMapTo(HashSet()) { group -> group.photos.map { it.id } }
        _uiState.update {
            it.copy(
                phase = SimilarPhase.Ready,
                groups = groups,
                marks = snapshotMarks(),
                remainder = nextRemainder,
                hiddenIds = it.hiddenIds.intersect(present),
            )
        }
    }

    /** 连着的下滑合成一条。过了期，或上一条是提交，就另起一条盖住它。 */
    private fun mergeBurst(photo: PhotoItem) {
        val now = uptimeClock.uptimeMillis()
        val current = _uiState.value.undo
        val existing = current?.takeIf { it.burst && now < it.deadlineUptimeMillis }?.trashed
        val photos = if (existing != null) (existing.photos + photo).distinctBy { it.id } else listOf(photo)
        _uiState.update {
            it.copy(
                undo = SimilarUndo(
                    seq = ++undoSeq,
                    thumbId = photo.id,
                    thumbModel = photo.uri,
                    title = UiText.Plural(R.plurals.batch_undo_trash, photos.size),
                    trashed = BatchAction(BatchActionType.MoveToTrash, photos),
                    resolvedIds = null,
                    deadlineUptimeMillis = now + MotionDurations.UNDO_COUNTDOWN,
                    burst = true,
                ),
            )
        }
    }

    private fun unhide(photoId: Long) {
        val mark = hiddenMarks.remove(photoId)
        val group = _uiState.value.groups.firstOrNull { candidate -> candidate.photos.any { it.id == photoId } }
        if (group != null && mark != null) marksByGroup[group.key]?.put(photoId, mark)
        else if (mark != null) pendingRestore[photoId] = mark
        _uiState.update { it.copy(hiddenIds = it.hiddenIds - photoId, marks = snapshotMarks()) }
    }

    private fun restoreMarks(ids: List<Long>) {
        for (id in ids) {
            val mark = hiddenMarks.remove(id) ?: continue
            val group = _uiState.value.groups.firstOrNull { candidate -> candidate.photos.any { it.id == id } }
            if (group != null) marksByGroup[group.key]?.put(id, mark) else pendingRestore[id] = mark
        }
    }

    private fun forgetHiddenMarks(undo: SimilarUndo) {
        undo.trashed?.photos?.forEach { hiddenMarks.remove(it.id) }
    }

    private fun snapshotMarks(): Map<String, Map<Long, SimilarMark>> =
        marksByGroup.mapValues { it.value.toMap() }

    private fun defaultMarks(group: SimilarGroup): Map<Long, SimilarMark> =
        group.photos.associate { photo ->
            photo.id to if (photo.id == group.recommendedId) SimilarMark.Keep else SimilarMark.Remove
        }

    private suspend fun isMotion(photo: PhotoItem): Boolean = try {
        locateMotionPhotoVideoUseCase(photo) != null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "动态照片识别失败 id=${photo.id}", e)
        false
    }

    private fun undoTitle(remove: List<PhotoItem>): UiText =
        if (remove.isNotEmpty()) UiText.Plural(R.plurals.batch_undo_trash, remove.size)
        else UiText.Res(R.string.similar_undo_kept)

    private companion object {
        const val TAG = "SimilarViewModel"
    }
}

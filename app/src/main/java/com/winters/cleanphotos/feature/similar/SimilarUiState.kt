package com.winters.cleanphotos.feature.similar

import android.net.Uri
import com.winters.cleanphotos.core.text.UiText
import com.winters.cleanphotos.domain.model.BatchAction
import com.winters.cleanphotos.domain.model.SimilarGroup
import com.winters.cleanphotos.domain.model.SimilarMark

enum class SimilarPhase { Loading, Comparing, Ready, Failed }

/**
 * 一次撤回。提交是整组；对比页连着下滑是同一条，停手后重新算 4 秒。
 * [burst] 为真时，下一次下滑还在期限内就并进这一条，并刷新到期时刻。
 */
data class SimilarUndo(
    val seq: Long,
    val thumbId: Long,
    val thumbModel: Uri,
    val title: UiText,
    val trashed: BatchAction?,
    val resolvedIds: Set<Long>?,
    /** 单调时钟上的到期时刻。离开相似再回来不重新计算。连着下滑会把它改到最后一滑。 */
    val deadlineUptimeMillis: Long,
    val burst: Boolean = false,
)

data class SimilarUiState(
    val phase: SimilarPhase = SimilarPhase.Loading,
    val compared: Int = 0,
    val compareTotal: Int = 0,
    val groups: List<SimilarGroup> = emptyList(),
    val marks: Map<String, Map<Long, SimilarMark>> = emptyMap(),
    /** 对比页已经下滑删掉、扫描还没把它们从组里拿掉的照片。 */
    val hiddenIds: Set<Long> = emptySet(),
    /** 扫描把组拿掉之后，对比页还留着的最后一张。key 是原来的组 key。 */
    val remainder: Map<String, CompareRemainder> = emptyMap(),
    val undo: SimilarUndo? = null,
    val moving: Boolean = false,
    /** 对比页当前张已确认是动态照片时，等于它的 id。换张先清掉，避免上一张的结果留在这一张上。 */
    val compareMotionId: Long? = null,
) {
    /** 还留在可见组里、标成移走的张数。下滑已经删掉的不算，解散的组也不算。 */
    val removableCount: Int
        get() = groups.sumOf { group ->
            val visible = group.photos.filter { it.id !in hiddenIds }
            if (visible.size < 2) 0
            else visible.count { marks[group.key]?.get(it.id) == SimilarMark.Remove }
        }
}

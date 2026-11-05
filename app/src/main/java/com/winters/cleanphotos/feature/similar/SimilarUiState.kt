package com.winters.cleanphotos.feature.similar

import android.net.Uri
import com.winters.cleanphotos.core.text.UiText
import com.winters.cleanphotos.domain.model.BatchAction
import com.winters.cleanphotos.domain.model.SimilarGroup
import com.winters.cleanphotos.domain.model.SimilarMark

enum class SimilarPhase { Loading, Comparing, Ready, Failed }

/**
 * 一次提交的撤回。移走和已处理记录一起撤。
 */
data class SimilarUndo(
    val seq: Long,
    val thumbId: Long,
    val thumbModel: Uri,
    val title: UiText,
    val trashed: BatchAction?,
    val resolvedIds: Set<Long>?,
    /** 单调时钟上的到期时刻。离开相似再回来不重新计算。 */
    val deadlineUptimeMillis: Long,
)

data class SimilarUiState(
    val phase: SimilarPhase = SimilarPhase.Loading,
    val compared: Int = 0,
    val compareTotal: Int = 0,
    val groups: List<SimilarGroup> = emptyList(),
    val marks: Map<String, Map<Long, SimilarMark>> = emptyMap(),
    val undo: SimilarUndo? = null,
    val moving: Boolean = false,
    /** 对比页当前张已确认是动态照片时，等于它的 id。换张先清掉，避免上一张的结果留在这一张上。 */
    val compareMotionId: Long? = null,
) {
    /** 当前所有组里标成移走的张数。 */
    val removableCount: Int
        get() = marks.values.sumOf { group -> group.values.count { it == SimilarMark.Remove } }
}

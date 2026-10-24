package com.winters.cleanphotos.feature.similar

import android.net.Uri
import com.winters.cleanphotos.core.text.UiText
import com.winters.cleanphotos.domain.model.BatchAction
import com.winters.cleanphotos.domain.model.SimilarGroup
import com.winters.cleanphotos.domain.model.SimilarMark

enum class SimilarPhase { Loading, Comparing, Ready }

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
)

data class SimilarUiState(
    val phase: SimilarPhase = SimilarPhase.Loading,
    val compared: Int = 0,
    val compareTotal: Int = 0,
    val groups: List<SimilarGroup> = emptyList(),
    val marks: Map<String, Map<Long, SimilarMark>> = emptyMap(),
    val undo: SimilarUndo? = null,
    val moving: Boolean = false,
) {
    /** 当前所有组里标成移走的张数。 */
    val removableCount: Int
        get() = marks.values.sumOf { group -> group.values.count { it == SimilarMark.Remove } }
}

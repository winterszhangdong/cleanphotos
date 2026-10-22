package com.winters.cleanphotos.feature.similar

import com.winters.cleanphotos.domain.model.SimilarGroup
import com.winters.cleanphotos.feature.allphotos.BatchUndo

enum class SimilarPhase { Loading, Comparing, Ready }

data class SimilarUiState(
    val phase: SimilarPhase = SimilarPhase.Loading,
    val compared: Int = 0,
    val compareTotal: Int = 0,
    val groups: List<SimilarGroup> = emptyList(),
    val undo: BatchUndo? = null,
    val moving: Boolean = false,
)

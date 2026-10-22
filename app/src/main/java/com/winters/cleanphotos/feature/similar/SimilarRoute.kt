package com.winters.cleanphotos.feature.similar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SimilarRoute(
    onNavigateBack: () -> Unit,
    viewModel: SimilarViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SimilarScreen(
        uiState = uiState,
        onBack = onNavigateBack,
        onKeep = viewModel::onKeep,
        onMoveRest = viewModel::onMoveRest,
        onUndo = viewModel::onUndo,
        onUndoExpired = viewModel::onUndoExpired,
    )
}

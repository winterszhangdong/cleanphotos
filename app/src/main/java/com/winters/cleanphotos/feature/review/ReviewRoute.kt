package com.winters.cleanphotos.feature.review

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.winters.cleanphotos.domain.model.ReviewEffect

@Composable
fun ReviewRoute(
    photoId: Long,
    sortOrder: String,
    filterMode: String,
    scope: String,
    unseenOnly: Boolean,
    onNavigateBack: () -> Unit,
    onNavigateToTrash: () -> Unit = {},
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 系统媒体授权（目前收藏 / 垃圾桶都只写 DataStore，保留通道以防仓库返回 NeedSystemApproval）
    val systemMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        viewModel.onSystemMediaActionResult(result.resultCode == android.app.Activity.RESULT_OK)
    }
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ReviewEffect.LaunchSystemMediaAction ->
                    systemMediaLauncher.launch(IntentSenderRequest.Builder(effect.request.intentSender).build())
            }
        }
    }
    LaunchedEffect(photoId) {
        viewModel.initialize(photoId, sortOrder, filterMode, scope, unseenOnly)
    }

    ReviewScreen(
        uiState = uiState,
        entryPhotoId = photoId,
        onSwipeUp = viewModel::onSwipeUp,
        onSwipeDown = viewModel::onSwipeDown,
        onPageSettled = viewModel::onPageSettled,
        onUndo = viewModel::onUndo,
        onUndoExpired = viewModel::onUndoExpired,
        onDismissError = viewModel::dismissError,
        onNavigateBack = onNavigateBack,
        onNavigateToTrash = onNavigateToTrash,
        onDismissTutorial = viewModel::dismissTutorial,
    )
}

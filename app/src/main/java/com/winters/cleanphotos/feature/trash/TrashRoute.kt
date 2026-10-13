package com.winters.cleanphotos.feature.trash

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.winters.cleanphotos.core.permission.PermissionChecker

@Composable
fun TrashRoute(
    onNavigateBack: () -> Unit,
    viewModel: TrashViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 系统媒体授权启动器
    val systemMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onSystemMediaActionResult(result.resultCode == android.app.Activity.RESULT_OK)
    }

    // Android 8–10 清空垃圾桶前的写权限申请
    val writePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onWritePermissionResult(granted) }

    // 监听一次性 Effect 事件（系统授权）
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is TrashEffect.LaunchSystemMediaAction -> {
                    systemMediaLauncher.launch(
                        IntentSenderRequest.Builder(effect.request.intentSender).build()
                    )
                }
                TrashEffect.RequestWritePermission -> {
                    PermissionChecker.legacyWritePermission?.let(writePermissionLauncher::launch)
                        ?: viewModel.onWritePermissionResult(true)
                }
            }
        }
    }

    TrashScreen(
        uiState = uiState,
        selectedBytes = viewModel.selectedBytes(uiState),
        onPhotoClick = viewModel::onPhotoClick,
        onToggleSelection = viewModel::onTogglePhotoSelection,
        onEnterSelectionMode = viewModel::onEnterSelectionMode,
        onExitSelectionMode = viewModel::onExitSelectionMode,
        onBatchRestore = viewModel::onBatchRestore,
        onRestorePreviewPhoto = viewModel::onRestorePreviewPhoto,
        onExitPreview = viewModel::onExitPreview,
        onPreviewPhotoChange = viewModel::onPreviewPhotoChange,
        onShowEmptyTrashConfirm = viewModel::onShowEmptyTrashConfirm,
        onDismissEmptyTrashConfirm = viewModel::onDismissEmptyTrashConfirm,
        onConfirmEmptyTrash = viewModel::onConfirmEmptyTrash,
        onDismissError = viewModel::dismissError,
        onDismissActionMessage = viewModel::dismissActionMessage,
        onNavigateBack = onNavigateBack,
        onRetry = viewModel::retryLoad,
        onDissolveFinished = viewModel::onDissolveFinished,
    )
}

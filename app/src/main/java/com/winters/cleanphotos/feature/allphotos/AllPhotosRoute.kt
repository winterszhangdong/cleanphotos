package com.winters.cleanphotos.feature.allphotos

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.winters.cleanphotos.core.permission.PermissionChecker

@Composable
fun AllPhotosRoute(
    onNavigateToReview: (photoId: Long, sortOrder: String, filterMode: String) -> Unit,
    onNavigateToTrash: () -> Unit,
    viewModel: AllPhotosViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 拒绝且系统不再弹窗：主按钮改为「去系统设置开启」（interaction.md）
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val granted = PermissionChecker.hasPhotoPermission(context)
        if (!granted) {
            val activity = context as? Activity
            permanentlyDenied = activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, PermissionChecker.getRequiredPermission())
        }
        viewModel.onPermissionResult(granted)
    }

    LifecycleResumeEffect(Unit) {
        viewModel.recheckPermission()
        onPauseOrDispose { }
    }

    AllPhotosScreen(
        uiState = uiState,
        permanentlyDenied = permanentlyDenied,
        onRequestPermission = {
            if (permanentlyDenied) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            } else {
                permissionLauncher.launch(PermissionChecker.requestPermissions())
            }
        },
        onPhotoClick = { photoId ->
            onNavigateToReview(photoId, uiState.sortOrder.name, uiState.filterMode.name)
        },
        onTrashClick = onNavigateToTrash,
        onSortOrderChanged = viewModel::onSortOrderChanged,
        onFilterModeChanged = viewModel::onFilterModeChanged,
        selection = selection,
        selectionActions = remember(viewModel) {
            PhotoSelectionActions(
                onLongPress = viewModel::onLongPressPhoto,
                onDrag = viewModel::onDragSelect,
                onToggle = viewModel::onToggleSelection,
                onToggleDay = viewModel::onToggleDay,
                onExit = viewModel::onExitSelection,
                onFavorite = viewModel::onBatchFavorite,
                onMoveToTrash = viewModel::onBatchMoveToTrash,
                onUndo = viewModel::onUndoBatch,
                onUndoExpired = viewModel::onUndoExpired,
            )
        },
    )
}

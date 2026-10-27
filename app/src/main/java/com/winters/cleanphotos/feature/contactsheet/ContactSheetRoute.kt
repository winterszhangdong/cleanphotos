package com.winters.cleanphotos.feature.contactsheet

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.winters.cleanphotos.core.permission.PermissionChecker
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

@Composable
fun ContactSheetRoute(
    onNavigateBack: () -> Unit,
    onNextDay: (dayKey: String) -> Unit,
    viewModel: ContactSheetViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val systemMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> viewModel.onSystemMediaActionResult(result.resultCode == android.app.Activity.RESULT_OK) }

    // Android 8–10 装袋前的存储写权限
    val writePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onWritePermissionResult(granted) }

    // 飞入 / 收起交给页面播；授权类的在这里启动
    val flights = remember { Channel<ContactSheetEffect>(Channel.BUFFERED) }
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ContactSheetEffect.LaunchSystemAction ->
                    systemMediaLauncher.launch(IntentSenderRequest.Builder(effect.request.intentSender).build())
                ContactSheetEffect.RequestWritePermission ->
                    PermissionChecker.legacyWritePermission?.let(writePermissionLauncher::launch)
                        ?: viewModel.onWritePermissionResult(true)
                is ContactSheetEffect.Flight, ContactSheetEffect.Bounce -> flights.send(effect)
            }
        }
    }

    ContactSheetScreen(
        state = uiState,
        flights = remember { flights.receiveAsFlow() },
        onBack = onNavigateBack,
        onToggle = viewModel::onToggle,
        onCircle = viewModel::onCircle,
        onCircleAll = viewModel::onCircleAll,
        onSystemFavoriteToggle = viewModel::onSystemFavoriteToggle,
        onFile = viewModel::onFile,
        onUnfavorite = viewModel::onUnfavorite,
        onUndo = viewModel::onUndo,
        onUndoExpired = viewModel::onUndoExpired,
        onNextDay = onNextDay,
    )
}

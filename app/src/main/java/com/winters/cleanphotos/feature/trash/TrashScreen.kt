package com.winters.cleanphotos.feature.trash

import com.winters.cleanphotos.core.text.asString
import com.winters.cleanphotos.core.locale.currentLocale
import com.winters.cleanphotos.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import com.winters.cleanphotos.core.design.MotionSprings
import kotlinx.coroutines.delay
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.rememberCoroutineScope
import com.winters.cleanphotos.core.navigation.BackdropSnapshot
import com.winters.cleanphotos.core.navigation.captureBackdrop
import com.winters.cleanphotos.core.navigation.predictiveBackAvailable
import com.winters.cleanphotos.core.navigation.predictiveBackdrop
import com.winters.cleanphotos.core.navigation.predictiveClip
import com.winters.cleanphotos.core.navigation.predictiveShrink
import com.winters.cleanphotos.core.navigation.recordBackdrop
import com.winters.cleanphotos.core.navigation.rememberBackdropRecorder
import com.winters.cleanphotos.core.navigation.rememberPredictiveBack
import com.winters.cleanphotos.core.navigation.AppDockEffect
import com.winters.cleanphotos.core.navigation.DockMode
import com.winters.cleanphotos.core.navigation.DockScrim
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.DeleteOutlineButton
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.EmptyPileArt
import com.winters.cleanphotos.core.design.EmptyStateArea
import com.winters.cleanphotos.core.design.EmptyTitleAndBody
import com.winters.cleanphotos.core.design.Eyebrow
import com.winters.cleanphotos.core.design.GhostButton
import com.winters.cleanphotos.core.design.GlassBackdropHost
import com.winters.cleanphotos.core.design.HeaderCount
import com.winters.cleanphotos.core.design.ListFade
import com.winters.cleanphotos.core.design.MotionDurations
import com.winters.cleanphotos.core.design.MotionEasings
import com.winters.cleanphotos.core.design.PaperButton
import com.winters.cleanphotos.core.design.ScreenHeader
import com.winters.cleanphotos.core.design.SystemBarIcons
import com.winters.cleanphotos.core.design.TrayTextButton
import com.winters.cleanphotos.core.design.glassBackdropSource
import com.winters.cleanphotos.core.design.plainClickable
import com.winters.cleanphotos.core.util.FileSizeFormatter
import com.winters.cleanphotos.domain.model.PhotoItem

/**
 * 垃圾桶、多选、单张预览与永久删除确认（组件细节）。
 */
@Composable
fun TrashScreen(
    uiState: TrashUiState,
    selectedBytes: Long,
    onPhotoClick: (PhotoItem) -> Unit,
    onToggleSelection: (PhotoItem) -> Unit,
    onEnterSelectionMode: () -> Unit,
    onExitSelectionMode: () -> Unit,
    onBatchRestore: () -> Unit,
    onRestorePreviewPhoto: () -> Unit,
    onExitPreview: () -> Unit,
    onPreviewPhotoChange: (Long) -> Unit,
    onShowEmptyTrashConfirm: () -> Unit,
    onDismissEmptyTrashConfirm: () -> Unit,
    onConfirmEmptyTrash: () -> Unit,
    onDismissError: () -> Unit,
    onDismissActionMessage: () -> Unit,
    onNavigateBack: () -> Unit,
    onRetry: () -> Unit,
    onDissolveFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val isPreview = uiState.screenMode == TrashScreenMode.Preview
    val isSelection = uiState.screenMode == TrashScreenMode.Selection

    SystemBarIcons(lightBackground = false)
    // 系统返回：先关预览、再退出多选
    // 34+ 由预览里的预测性返回接管；同时启用时普通 BackHandler 会抢先
    BackHandler(enabled = isPreview && !predictiveBackAvailable()) { onExitPreview() }
    val gridRecorder = rememberBackdropRecorder()
    val recorderScope = rememberCoroutineScope()
    BackHandler(enabled = isSelection && !isPreview) { onExitSelectionMode() }

    val errorText = uiState.errorMessage?.asString()
    val actionText = uiState.actionMessage?.asString()
    LaunchedEffect(uiState.errorMessage) {
        if (errorText != null && uiState.photos.isNotEmpty()) {
            snackbarHostState.showSnackbar(errorText)
            onDismissError()
        }
    }
    LaunchedEffect(uiState.actionMessage) {
        actionText?.let { message ->
            snackbarHostState.showSnackbar(message)
            onDismissActionMessage()
        }
    }

    GlassBackdropHost {
        AppDockEffect(
            mode = if (isPreview) DockMode.Hidden else DockMode.Visible,
            scrim = if (uiState.showEmptyTrashConfirmDialog) {
                DockScrim(DarkroomColors.DialogScrim, MotionDurations.SHORT, MotionDurations.SHORT, onDismissEmptyTrashConfirm)
            } else null,
        )
        Box(modifier.fillMaxSize().background(DarkroomColors.Room)) {
            if (isPreview && uiState.previewPhotoId != null) {
                val back = rememberPredictiveBack(enabled = true, onBack = onExitPreview)
                TrashPreviewOverlay(
                    photos = uiState.photos,
                    currentPhotoId = uiState.previewPhotoId,
                    movedAt = uiState.movedAt,
                    isProcessingAction = uiState.isProcessingAction,
                    onPhotoChange = onPreviewPhotoChange,
                    onRestore = onRestorePreviewPhoto,
                    onExit = onExitPreview,
                    // 全屏预览跟手缩小，后面露出垃圾桶网格快照
                    modifier = Modifier
                        .fillMaxSize()
                        .predictiveBackdrop(back) { BackdropSnapshot.image }
                        .predictiveShrink(back)
                        .predictiveClip(back),
                )
            } else {
                Column(Modifier.fillMaxSize().recordBackdrop(gridRecorder).glassBackdropSource()) {
                    if (isSelection) {
                        SelectionBar(
                            selectedCount = uiState.selectedPhotoIds.size,
                            onExit = onExitSelectionMode,
                            onBatchRestore = onBatchRestore,
                            isRestoreEnabled = uiState.selectedPhotoIds.isNotEmpty() && !uiState.isProcessingAction,
                        )
                    } else {
                        ScreenHeader(
                            eyebrow = { Eyebrow(stringResource(R.string.tab_trash)) },
                            count = { HeaderCount(uiState.photos.size) },
                            actions = {
                                if (uiState.photos.isNotEmpty()) TrayTextButton(stringResource(R.string.trash_select), onClick = onEnterSelectionMode)
                            },
                        )
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            uiState.dissolving != null -> TrashDissolveGrid(
                                dissolve = uiState.dissolving,
                                header = { bytes -> FreeCard(bytes, onClear = {}) },
                                onFinished = onDissolveFinished,
                            )
                            uiState.isLoading && uiState.photos.isEmpty() -> Unit
                            uiState.errorMessage != null && uiState.photos.isEmpty() -> Unit
                            uiState.photos.isEmpty() -> Unit
                            else -> TrashPhotoGrid(
                                photos = uiState.photos,
                                selectedPhotoIds = uiState.selectedPhotoIds,
                                isSelectionMode = isSelection,
                                onPhotoClick = { photo ->
                                    if (!isSelection) recorderScope.captureBackdrop(gridRecorder)
                                    onPhotoClick(photo)
                                },
                                onToggleSelection = onToggleSelection,
                                header = {
                                    if (isSelection) SelectionSummary(selectedBytes) else FreeCard(uiState.totalBytes, onShowEmptyTrashConfirm)
                                },
                            )
                        }
                    }
                }
                when {
                    uiState.errorMessage != null && uiState.photos.isEmpty() && !uiState.isLoading -> EmptyStateArea {
                        EmptyTitleAndBody(stringResource(R.string.trash_error_title), errorText.orEmpty())
                        GhostButton(stringResource(R.string.retry), onClick = onRetry)
                    }
                    uiState.dissolving != null -> ListFade()
                    uiState.photos.isEmpty() && !uiState.isLoading -> RisingEmpty(rise = uiState.justFreedBytes != null) {
                        EmptyPileArt(sink = true, modifier = Modifier.padding(bottom = 32.dp))
                        EmptyTitleAndBody(
                            stringResource(R.string.trash_empty_title),
                            uiState.justFreedBytes?.let { stringResource(R.string.trash_just_freed, FileSizeFormatter.format(it, currentLocale())) }
                                ?: stringResource(R.string.trash_empty_body),
                        )
                    }
                    else -> ListFade()
                }

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = Dimens.NavBottom + Dimens.NavHeight + 16.dp),
                ) { data ->
                    // 默认 Material Snackbar 是浅色底（录屏里那条白条），换成暗房提示条（同排序提示）
                    val shape = RoundedCornerShape(Dimens.ToastHeight / 2)
                    Box(
                        Modifier
                            .height(Dimens.ToastHeight)
                            .clip(shape)
                            .background(DarkroomColors.ToastFill)
                            .border(Dimens.Hairline, DarkroomColors.ToastRim, shape)
                            .padding(horizontal = Dimens.ToastPaddingH),
                        contentAlignment = Alignment.Center,
                    ) { Text(data.visuals.message, style = DarkroomText.Toast) }
                }
            }

            AnimatedVisibility(
                visible = uiState.showEmptyTrashConfirmDialog,
                modifier = Modifier.fillMaxSize().zIndex(4f),
                enter = fadeIn(tween(MotionDurations.SHORT, easing = MotionEasings.Standard)),
                exit = fadeOut(tween(MotionDurations.SHORT, easing = MotionEasings.Standard)),
            ) {
                EmptyTrashConfirmDialog(
                    count = uiState.photos.size,
                    totalBytes = uiState.totalBytes,
                    onDismiss = onDismissEmptyTrashConfirm,
                    onConfirm = onConfirmEmptyTrash,
                )
            }
        }
    }
}

/** .free：「清空后可释放」卡片（外边距 4 16 16，网格自身 2dp 间隔从下边距里扣掉）。 */
@Composable
private fun FreeCard(totalBytes: Long, onClear: () -> Unit) {
    val shape = RoundedCornerShape(Dimens.FreeCorner)
    val (num, unit) = FileSizeFormatter.parts(totalBytes, currentLocale())
    Row(
        Modifier
            .padding(start = Dimens.ScreenSide, end = Dimens.ScreenSide, top = Dimens.FreeTop, bottom = 16.dp - Dimens.GridGap)
            .fillMaxWidth()
            .clip(shape)
            .background(DarkroomColors.Tray)
            .border(Dimens.Hairline, DarkroomColors.Rim, shape)
            .padding(Dimens.FreePadding),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.free_label), style = DarkroomText.Label12)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(num, style = DarkroomText.FreeNum, modifier = Modifier.alignByBaseline())
                Text(unit, style = DarkroomText.FreeUnit, modifier = Modifier.alignByBaseline())
            }
            Text(stringResource(R.string.free_sub), style = DarkroomText.FreeSub, modifier = Modifier.padding(top = 4.dp))
        }
        DeleteOutlineButton(stringResource(R.string.empty_trash), onClick = onClear)
    }
}

/** .sel-bar：三栏（取消 / 已选择 N 项 / 移出垃圾桶），状态栏下 84dp，底对齐。 */
@Composable
private fun SelectionBar(selectedCount: Int, onExit: () -> Unit, onBatchRestore: () -> Unit, isRestoreEnabled: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(Dimens.HeaderHeight)
            .padding(start = Dimens.ScreenSide, end = Dimens.ScreenSide, bottom = Dimens.HeaderBottomPadding),
    ) {
        TrayTextButton(stringResource(R.string.cancel), onClick = onExit, modifier = Modifier.align(Alignment.BottomStart))
        Row(
            Modifier.align(Alignment.BottomCenter).height(Dimens.SmallButtonHeight),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 中文「已选择 N 项」，英文「N selected」：前后缀为空时不占位
            val prefix = stringResource(R.string.trash_selected_prefix)
            val suffix = stringResource(R.string.trash_selected_suffix)
            if (prefix.isNotEmpty()) Text(prefix, style = DarkroomText.SelTitle, modifier = Modifier.alignByBaseline())
            Text(selectedCount.toString(), style = DarkroomText.SelNum, modifier = Modifier.alignByBaseline())
            if (suffix.isNotEmpty()) Text(suffix, style = DarkroomText.SelTitle, modifier = Modifier.alignByBaseline())
        }
        PaperButton(stringResource(R.string.restore_from_trash), onClick = onBatchRestore, enabled = isRestoreEnabled, modifier = Modifier.align(Alignment.BottomEnd))
    }
}

/** .sel-sum：40dp，左提示，右「9.6 MB · 回到相册」。 */
@Composable
private fun SelectionSummary(selectedBytes: Long) {
    Row(
        Modifier.fillMaxWidth().height(Dimens.SelSumHeight - Dimens.GridGap).padding(horizontal = Dimens.ScreenSide),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(R.string.sel_sum_hint), style = DarkroomText.SelSum)
        val selectedSize = FileSizeFormatter.format(selectedBytes, currentLocale())
        val backLabel = stringResource(R.string.sel_sum_back)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = DarkroomColors.Paper, fontWeight = FontWeight.Medium)) {
                    append(selectedSize)
                }
                append(backLabel)
            },
            style = DarkroomText.SelSumMono,
        )
    }
}

/**
 * 永久删除确认（组件细节）：64% 压暗；托盘色对话框左右 24、垂直居中、圆角 24；「删除」是删除红唯一的完整用法。
 */
@Composable
private fun EmptyTrashConfirmDialog(count: Int, totalBytes: Long, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(DarkroomColors.DialogScrim).plainClickable(onClick = onDismiss))
        val shape = RoundedCornerShape(Dimens.DialogCorner)
        val (num, unit) = FileSizeFormatter.parts(totalBytes, currentLocale())
        // 「会释放 1.2 GB。……」里数字加粗：按占位符位置拆开拼
        val body = stringResource(R.string.dialog_delete_body, "\u0000")
        val bodyBefore = body.substringBefore('\u0000')
        val bodyAfter = body.substringAfter('\u0000')
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = Dimens.DialogSide)
                .fillMaxWidth()
                .dropShadow(shape, Shadow(radius = 48.dp, color = DarkroomColors.DialogShadow, offset = DpOffset(0.dp, 24.dp)))
                .clip(shape)
                .background(DarkroomColors.Tray)
                .border(Dimens.Hairline, DarkroomColors.Rim, shape)
                .plainClickable(onClick = {})
                .padding(24.dp),
        ) {
            Text(pluralStringResource(R.plurals.dialog_delete_title, count, count), style = DarkroomText.DialogTitle)
            Text(
                buildAnnotatedString {
                    append(bodyBefore)
                    withStyle(SpanStyle(fontFamily = DarkroomText.Num.fontFamily, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")) { append(num) }
                    append(" $unit")
                    append(bodyAfter)
                },
                style = DarkroomText.DialogBody,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                DialogButton(stringResource(R.string.cancel), DarkroomColors.Paper, null, onDismiss)
                DialogButton(stringResource(R.string.delete), DarkroomColors.OnDelete, DarkroomColors.Delete, onConfirm)
            }
        }
    }
}

@Composable
private fun DialogButton(text: String, color: androidx.compose.ui.graphics.Color, fill: androidx.compose.ui.graphics.Color?, onClick: () -> Unit) {
    Box(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .then(if (fill != null) Modifier.background(fill) else Modifier)
            .plainClickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = DarkroomText.Button14.copy(color = color)) }
}

/** 清空溶解后空状态缓缓升起（溶解完 160ms 后，舒缓弹簧，上移 8px → 12.6dp + 淡入）；其余情况直接显示。 */
@Composable
private fun RisingEmpty(rise: Boolean, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val progress = remember { Animatable(if (rise) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) {
            delay(MotionDurations.DISSOLVE_EMPTY_DELAY.toLong())
            progress.animateTo(1f, MotionSprings.Gentle.spec())
        }
    }
    EmptyStateArea(
        Modifier.graphicsLayer {
            alpha = progress.value.coerceIn(0f, 1f)
            translationY = Dimens.EmptyRise.toPx() * (1f - progress.value)
        },
        content = content,
    )
}

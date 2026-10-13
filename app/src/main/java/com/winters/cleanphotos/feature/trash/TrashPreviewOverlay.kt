package com.winters.cleanphotos.feature.trash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomIcons
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.GlassIconButton
import com.winters.cleanphotos.core.design.PrimaryButton
import com.winters.cleanphotos.core.design.saturateBrightness
import com.winters.cleanphotos.core.util.DayDisplay
import com.winters.cleanphotos.core.util.FileSizeFormatter
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.ui.photopager.HorizontalSwipeState
import com.winters.cleanphotos.ui.photopager.PhotoPager
import com.winters.cleanphotos.ui.photopager.detectPagerGestures
import com.winters.cleanphotos.ui.photopager.rememberPagerZoomState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt


/**
 * 垃圾桶全屏预览模式。
 *
 * 黑底沉浸式展示照片，使用共享横向预览组件实现左右切换，
 * 与清理页保持一致的跟手滑动、边界回弹和滑入/滑出动画。
 *
 * 使用本地 displayIndex 承接 fling 结束瞬间的无跳变切页，
 * 与 ReviewScreen 保持一致的切页缓冲策略。
 */
@Composable
fun TrashPreviewOverlay(
    photos: List<PhotoItem>,
    currentPhotoId: Long,
    movedAt: Map<Long, Long>,
    isProcessingAction: Boolean,
    onPhotoChange: (Long) -> Unit,
    onRestore: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenSizePx = remember(density) {
        IntSize(
            with(density) { configuration.screenWidthDp.dp.roundToPx() },
            with(density) { configuration.screenHeightDp.dp.roundToPx() }
        )
    }

    val swipeState = remember { HorizontalSwipeState(screenSizePx, density) }
    val zoomState = rememberPagerZoomState()
    val scope = rememberCoroutineScope()

    // 容器尺寸，用于 zoom 边界计算
    var containerSize by remember { mutableStateOf(screenSizePx) }

    // 本地切页缓冲状态：与 ReviewScreen 保持一致的策略
    var displayPhotoId by remember { mutableStateOf(currentPhotoId) }
    var displayIndex by remember { mutableIntStateOf(photos.indexOfFirst { it.id == currentPhotoId }.coerceAtLeast(0)) }
    var pendingPhotoId by remember { mutableStateOf<Long?>(null) }
    var isHorizontalFlinging by remember { mutableStateOf(false) }
    var settledOffsetXOverride by remember { mutableStateOf<Int?>(null) }

    // 同步外部 currentPhotoId 到本地显示状态
    LaunchedEffect(photos, currentPhotoId) {
        if (photos.isEmpty()) {
            displayIndex = 0
            displayPhotoId = -1L
            pendingPhotoId = null
            settledOffsetXOverride = null
            isHorizontalFlinging = false
            return@LaunchedEffect
        }

        val targetIndex = photos.indexOfFirst { it.id == currentPhotoId }.coerceAtLeast(0)
        when {
            pendingPhotoId == null -> {
                displayIndex = targetIndex
                displayPhotoId = currentPhotoId
                settledOffsetXOverride = null
            }
            pendingPhotoId == currentPhotoId -> {
                displayIndex = targetIndex
                displayPhotoId = currentPhotoId
                pendingPhotoId = null
                settledOffsetXOverride = null
                isHorizontalFlinging = false
            }
        }
    }

    // 照片切换时重置滑动状态和缩放状态
    // fling 动画期间不重置，避免中途打断横滑偏移
    LaunchedEffect(displayPhotoId) {
        if (isHorizontalFlinging || pendingPhotoId != null) return@LaunchedEffect
        if (swipeState.currentOffsetX != 0f) {
            swipeState.reset()
        }
        zoomState.reset()
    }

    val canSwipePrevious = displayIndex > 0
    val canSwipeNext = displayIndex < photos.size - 1
    val isGestureBlocked = isProcessingAction ||
        isHorizontalFlinging ||
        pendingPhotoId != null

    // 恢复/处理状态变化时重置 pending 和 offset
    LaunchedEffect(isProcessingAction) {
        if (isProcessingAction) {
            pendingPhotoId = null
            isHorizontalFlinging = false
            settledOffsetXOverride = null
        }
    }

    val currentPhoto = photos.getOrNull(displayIndex)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkroomColors.Room)
    ) {
        // .pv-top：状态栏下 12dp，40dp 高，左玻璃返回钮，中间「垃圾桶 / i / N」
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 12.dp, start = 12.dp, end = 12.dp)
                .fillMaxWidth()
                .height(40.dp)
                .zIndex(2f)
        ) {
            GlassIconButton(onClick = onExit, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(DarkroomIcons.Back, contentDescription = "返回", tint = DarkroomColors.Paper, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("垃圾桶", style = DarkroomText.PvTitle)
                Text("${displayIndex + 1} / ${photos.size}", style = DarkroomText.PvSub)
            }
        }

        // 共享横向预览组件 + 统一手势检测器
        PhotoPager(
            photos = photos,
            currentIndex = displayIndex,
            offsetX = settledOffsetXOverride ?: swipeState.currentOffsetX.roundToInt(),
            offsetY = 0,
            scale = 1f,
            zoomScale = zoomState.currentZoomScale,
            zoomOffsetX = zoomState.currentZoomOffsetX,
            zoomOffsetY = zoomState.currentZoomOffsetY,
            contentScale = ContentScale.Fit,
            colorFilter = previewColorFilter,
            backgroundColor = DarkroomColors.Room,
            modifier = Modifier
                // .pv-photo：状态栏下 144dp，宽 = 屏宽、高 = 宽，contain
                .statusBarsPadding()
                .padding(top = 144.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .onSizeChanged { containerSize = it }
                .pointerInput(isGestureBlocked, displayPhotoId) {
                    if (isGestureBlocked) return@pointerInput

                    detectPagerGestures(
                        zoomState = zoomState,
                        zoomEnabled = true,
                        containerSize = { containerSize },
                        onDrag = { delta ->
                            // 垃圾桶预览只支持水平滑动，忽略垂直分量
                            if (!zoomState.isZoomed) {
                                scope.launch {
                                    swipeState.onDrag(
                                        delta = delta.x,
                                        canSwipePrevious = canSwipePrevious,
                                        canSwipeNext = canSwipeNext
                                    )
                                }
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                when {
                                    swipeState.isSwipeLeftTriggered && canSwipeNext -> {
                                        val targetX = -screenSizePx.width.toFloat()
                                        val targetIndex = displayIndex + 1
                                        val targetId = photos[targetIndex].id
                                        isHorizontalFlinging = true
                                        swipeState.flyHorizontal(targetX)
                                        // 先归零视觉偏移，再切 displayIndex，避免一帧闪烁
                                        settledOffsetXOverride = 0
                                        pendingPhotoId = targetId
                                        displayIndex = targetIndex
                                        displayPhotoId = targetId
                                        swipeState.reset()
                                        onPhotoChange(targetId)
                                    }
                                    swipeState.isSwipeRightTriggered && canSwipePrevious -> {
                                        val targetX = screenSizePx.width.toFloat()
                                        val targetIndex = displayIndex - 1
                                        val targetId = photos[targetIndex].id
                                        isHorizontalFlinging = true
                                        swipeState.flyHorizontal(targetX)
                                        // 先归零视觉偏移，再切 displayIndex，避免一帧闪烁
                                        settledOffsetXOverride = 0
                                        pendingPhotoId = targetId
                                        displayIndex = targetIndex
                                        displayPhotoId = targetId
                                        swipeState.reset()
                                        onPhotoChange(targetId)
                                    }
                                    else -> {
                                        swipeState.springBack()
                                    }
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                swipeState.springBack()
                            }
                        },
                        onDoubleTap = { tapOffset ->
                            scope.launch {
                                zoomState.toggleDoubleTapZoom(tapOffset, containerSize)
                            }
                        }
                    )
                }
        )

        if (currentPhoto != null) {
            // .pv-meta：照片下 16dp，两行等宽小字居中
            Text(
                text = "${baseName(currentPhoto.displayName)}${currentPhoto.size?.let { " · " + FileSizeFormatter.format(it) } ?: ""}\n" +
                    DayDisplay.trashPreviewDates(currentPhoto, movedAt[currentPhoto.id], System.currentTimeMillis()),
                style = DarkroomText.PvMeta,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 144.dp + 16.dp)
                    .offset { IntOffset(0, containerSize.height) },
            )
            Text(
                text = "移出后回到照片页的 ${DayDisplay.cleanPageDateFor(currentPhoto)}",
                style = DarkroomText.PvHint,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = Dimens.PreviewHintBottom),
            )
        }
        PrimaryButton(
            text = "移出垃圾桶",
            icon = DarkroomIcons.Restore,
            onClick = onRestore,
            enabled = !isProcessingAction,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = Dimens.PreviewActionBottom)
                .zIndex(2f),
        )
    }
}

/** 「IMG_1987.jpg」→「IMG_1987」。 */
private fun baseName(name: String?): String = name?.substringBeforeLast('.') ?: ""

/** 预览同网格：saturate(0.7) brightness(0.86)。 */
private val previewColorFilter = saturateBrightness(0.7f, 0.86f)

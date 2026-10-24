package com.winters.cleanphotos.feature.trash

import com.winters.cleanphotos.core.locale.currentLocale
import com.winters.cleanphotos.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.SideEffect
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
import com.winters.cleanphotos.core.design.LocalAnimationsEnabled
import com.winters.cleanphotos.core.design.DarkroomIcons
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.GlassIconButton
import com.winters.cleanphotos.core.design.PrimaryButton
import com.winters.cleanphotos.core.design.saturateBrightness
import com.winters.cleanphotos.core.util.DayDisplay
import com.winters.cleanphotos.core.util.FileSizeFormatter
import com.winters.cleanphotos.core.media.MotionReplayPill
import com.winters.cleanphotos.core.media.PlaybackAutoplay
import com.winters.cleanphotos.core.media.PlaybackCenterButton
import com.winters.cleanphotos.core.media.PlaybackProgressRow
import com.winters.cleanphotos.core.media.PlaybackSurface
import com.winters.cleanphotos.core.media.rememberPlaybackEngine
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PlaybackTarget
import com.winters.cleanphotos.domain.model.awaitingAutoplay
import com.winters.cleanphotos.domain.model.playbackChrome
import com.winters.cleanphotos.domain.model.playbackPhase
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
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
    playback: PlaybackTarget?,
    playbackMuted: Boolean,
    onPlaybackMuted: (Boolean) -> Unit,
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
    val engine = rememberPlaybackEngine()
    var playbackSettled by remember { mutableStateOf(true) }
    /** 横向飞出结束前不换片。非空时 TextureView 留在飞出的那张上，角色变成侧页也继续画定格帧。 */
    var videoPinnedId by remember { mutableStateOf<Long?>(null) }
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
    val currentPhoto = photos.getOrNull(displayIndex)
    val pinnedPhoto = videoPinnedId?.let { id -> photos.firstOrNull { it.id == id } }
    val media = engine.media
    // 飞出途中不换片。落定后只装和当前照片是同一个目标的媒体，避免旧画面盖到新页。
    SideEffect {
        if (videoPinnedId == null) {
            engine.ensureLoaded(playback?.takeIf { currentPhoto?.uri == it.uri })
        }
        engine.ensureMuted(playbackMuted)
    }
    val videoHostId = when {
        videoPinnedId != null && pinnedPhoto?.uri == media?.uri -> videoPinnedId
        videoPinnedId == null && media != null && media == playback && currentPhoto?.uri == media.uri -> currentPhoto.id
        else -> null
    }
    PlaybackAutoplay(
        engine,
        settled = playbackSettled && videoPinnedId == null && videoHostId != null && videoHostId == currentPhoto?.id,
    )
    val pageIsVideo = playback is PlaybackTarget.Video && currentPhoto?.uri == playback.uri
    val pageIsMotion = playback is PlaybackTarget.MotionPhoto && currentPhoto?.uri == playback.uri
    val videoOnThisPage = videoHostId != null && videoHostId == currentPhoto?.id
    val trashChrome = playbackChrome(
        isVideo = pageIsVideo,
        phase = playbackPhase(engine.started, engine.userPaused, engine.ended, engine.playing),
        gestureActive = !playbackSettled,
        pinnedOrExiting = videoPinnedId != null,
        awaitingAutoplay = awaitingAutoplay(
            animationsEnabled = LocalAnimationsEnabled.current,
            settled = playbackSettled && videoPinnedId == null && videoOnThisPage,
            resumed = engine.resumed,
            started = engine.started,
            userPaused = engine.userPaused,
            ended = engine.ended,
        ),
    )
    val videoTapLabel = if (pageIsVideo) stringResource(R.string.cd_playback_toggle) else null

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
                Icon(DarkroomIcons.Back, contentDescription = stringResource(R.string.cd_back), tint = DarkroomColors.Paper, modifier = Modifier.size(20.dp))
            }
            if (pageIsMotion && videoPinnedId == null) {
                MotionReplayPill(
                    onClick = { engine.replayMotion() },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.tab_trash), style = DarkroomText.PvTitle)
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
            videoHostId = videoHostId,
            videoOverlay = { PlaybackSurface(engine, Modifier.fillMaxSize()) },
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
                            // 垃圾桶预览只支持水平滑动，忽略垂直分量。任一方向的拖动都先停住播放。
                            if (!zoomState.isZoomed) {
                                if (playbackSettled) playbackSettled = false
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
                                        videoPinnedId = currentPhoto?.takeIf { engine.media?.uri == it.uri }?.id
                                        swipeState.flyHorizontal(targetX)
                                        videoPinnedId = null
                                        // 先归零视觉偏移，再切 displayIndex，避免一帧闪烁
                                        settledOffsetXOverride = 0
                                        pendingPhotoId = targetId
                                        displayIndex = targetIndex
                                        displayPhotoId = targetId
                                        swipeState.reset()
                                        onPhotoChange(targetId)
                                        playbackSettled = true
                                    }
                                    swipeState.isSwipeRightTriggered && canSwipePrevious -> {
                                        val targetX = screenSizePx.width.toFloat()
                                        val targetIndex = displayIndex - 1
                                        val targetId = photos[targetIndex].id
                                        isHorizontalFlinging = true
                                        videoPinnedId = currentPhoto?.takeIf { engine.media?.uri == it.uri }?.id
                                        swipeState.flyHorizontal(targetX)
                                        videoPinnedId = null
                                        // 先归零视觉偏移，再切 displayIndex，避免一帧闪烁
                                        settledOffsetXOverride = 0
                                        pendingPhotoId = targetId
                                        displayIndex = targetIndex
                                        displayPhotoId = targetId
                                        swipeState.reset()
                                        onPhotoChange(targetId)
                                        playbackSettled = true
                                    }
                                    else -> {
                                        swipeState.springBack()
                                        playbackSettled = true
                                    }
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                swipeState.springBack()
                                playbackSettled = true
                            }
                        },
                        onDoubleTap = { tapOffset ->
                            scope.launch {
                                zoomState.toggleDoubleTapZoom(tapOffset, containerSize)
                            }
                        },
                        onSingleTap = {
                            val photo = photos.getOrNull(displayIndex)
                            val playing = engine.media
                            if (photo != null && playing is PlaybackTarget.Video && playing.uri == photo.uri && videoPinnedId == null) {
                                engine.togglePlay()
                            }
                        },
                    )
                }
                .then(
                    if (videoTapLabel != null) {
                        Modifier.semantics {
                            onClick(label = videoTapLabel) {
                                engine.togglePlay()
                                true
                            }
                        }
                    } else {
                        Modifier
                    },
                )
        )

        Box(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 144.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .zIndex(3f),
        ) {
            PlaybackCenterButton(
                visible = pageIsVideo && videoOnThisPage && trashChrome.showCenter,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (currentPhoto != null) {
            val locale = currentLocale()
            val takenText = currentPhoto.dateTaken?.takeIf { it > 0L }
                ?.let { stringResource(R.string.trash_taken, DayDisplay.cleanPageDate(it, java.time.ZoneId.systemDefault(), locale)) }
            val movedText = movedAt[currentPhoto.id]?.let { DayDisplay.daysSince(it, System.currentTimeMillis()) }?.let { days ->
                if (days == 0) stringResource(R.string.trash_moved_today) else pluralStringResource(R.plurals.trash_moved_days, days, days)
            }
            // .pv-meta：照片下 16dp。播放控件和文件信息放在同一块，不进照片的 pointerInput。
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 144.dp + 16.dp)
                    .offset { IntOffset(0, containerSize.height) }
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.PreviewSide),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (pageIsVideo && videoPinnedId == null) {
                    PlaybackProgressRow(
                        engine = engine,
                        muted = playbackMuted,
                        onMutedChange = onPlaybackMuted,
                        timeStyle = DarkroomText.RvFile,
                    )
                    Spacer(Modifier.height(Dimens.GhostGap))
                }
                Text(
                    text = "${baseName(currentPhoto.displayName)}${currentPhoto.size?.let { " · " + FileSizeFormatter.format(it, locale) } ?: ""}\n" +
                        listOfNotNull(takenText, movedText).joinToString(" · "),
                    style = DarkroomText.PvMeta,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = stringResource(R.string.trash_restore_hint, DayDisplay.photoDate(currentPhoto)?.let { DayDisplay.cleanDate(it, locale) } ?: stringResource(R.string.date_unknown)),
                style = DarkroomText.PvHint,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = Dimens.PreviewHintBottom),
            )
        }
        PrimaryButton(
            text = stringResource(R.string.restore_from_trash),
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

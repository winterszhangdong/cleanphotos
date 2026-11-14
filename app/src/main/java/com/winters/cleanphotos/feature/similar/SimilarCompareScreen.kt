package com.winters.cleanphotos.feature.similar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import coil.compose.AsyncImage
import com.winters.cleanphotos.R
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomIcons
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.PhotoMarkText
import com.winters.cleanphotos.core.design.GestureTokens
import com.winters.cleanphotos.core.design.GlassIconButton
import com.winters.cleanphotos.core.design.GlassSpecs
import com.winters.cleanphotos.core.design.LocalAnimationsEnabled
import com.winters.cleanphotos.core.design.MotionDurations
import com.winters.cleanphotos.core.design.glassBlur
import com.winters.cleanphotos.core.design.plainClickable
import com.winters.cleanphotos.core.navigation.AppDockEffect
import com.winters.cleanphotos.core.navigation.DockMode
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.ReviewPage
import com.winters.cleanphotos.domain.model.SimilarMark
import com.winters.cleanphotos.domain.model.isHeavyStill
import com.winters.cleanphotos.domain.model.isRawFileName
import com.winters.cleanphotos.feature.review.ExitFlight
import com.winters.cleanphotos.feature.review.ExitKind
import com.winters.cleanphotos.feature.review.ExitPath
import com.winters.cleanphotos.feature.review.ReviewGestureMath
import com.winters.cleanphotos.feature.review.ReviewThresholds
import com.winters.cleanphotos.feature.review.detectReviewGestures
import com.winters.cleanphotos.feature.review.rememberReviewImageRequest
import com.winters.cleanphotos.feature.review.tone
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 对比页和照片页共用同一个 [SimilarViewModel]，挂在 all_photos 的返回栈上。
 * 看得见的只剩一张时留在这一页。再下滑，飞完才回到列表。返回不提交还没滑的标记。
 */
@Composable
fun SimilarCompareRoute(
    groupKey: String,
    photoId: Long,
    onBack: () -> Unit,
    parentEntry: NavBackStackEntry,
    viewModel: SimilarViewModel = hiltViewModel(parentEntry),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 进程被系统回收后栈顶可能就是对比页，照片页的 LaunchedEffect 不会跑。这里再调一次，start 自己保证不重扫。
    LaunchedEffect(Unit) {
        viewModel.start()
    }
    var lastFlight by remember(groupKey) { mutableStateOf(false) }
    var pinnedMarks by remember(groupKey) { mutableStateOf<Map<Long, SimilarMark>>(emptyMap()) }
    val held = remainderForRoute(groupKey, uiState.remainder)
    val session = compareSession(groupKey, uiState.groups, uiState.hiddenIds, held)
    val liveMarks = session?.let { uiState.marks[it.sourceKey] }.orEmpty().ifEmpty {
        held?.let { mapOf(it.photo.id to it.mark) }.orEmpty()
    }
    SideEffect {
        if (session != null) pinnedMarks = liveMarks
    }
    val commitKey = session?.sourceKey ?: groupKey
    LaunchedEffect(session, uiState.phase, lastFlight) {
        if (similarCompareShouldLeave(uiState.phase, session != null || lastFlight)) onBack()
    }
    SimilarCompareScreen(
        routeKey = groupKey,
        session = session,
        photoId = photoId,
        marks = if (session != null) liveMarks else pinnedMarks,
        moving = uiState.moving,
        undo = uiState.undo,
        compareMotionId = uiState.compareMotionId,
        loadFailed = uiState.phase == SimilarPhase.Failed,
        showCommit = uiState.groups.any { it.key == commitKey },
        onBack = onBack,
        onRetry = viewModel::start,
        onMark = viewModel::onMark,
        onSwipeTrash = viewModel::onSwipeTrash,
        onCommit = { viewModel.onCommit(commitKey, onBack) },
        onUndo = viewModel::onUndo,
        onUndoExpired = viewModel::onUndoExpired,
        onWatch = viewModel::watchComparePhoto,
        onLastFlight = { lastFlight = it },
    )
}

/** 只有扫描真正结束且这组不在结果里才离开。加载、比对中、照片表加载失败都不是「组没了」。 */
internal fun similarCompareShouldLeave(phase: SimilarPhase, hasGroup: Boolean): Boolean =
    phase == SimilarPhase.Ready && !hasGroup

@Composable
private fun SimilarCompareScreen(
    routeKey: String,
    session: CompareSession?,
    photoId: Long,
    marks: Map<Long, SimilarMark>,
    moving: Boolean,
    undo: SimilarUndo?,
    compareMotionId: Long?,
    loadFailed: Boolean,
    showCommit: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onMark: (String, Long, SimilarMark) -> Unit,
    onSwipeTrash: (String, Long) -> Boolean,
    onCommit: () -> Unit,
    onUndo: () -> Unit,
    onUndoExpired: (Long) -> Unit,
    onWatch: (PhotoItem) -> Unit,
    onLastFlight: (Boolean) -> Unit,
) {
    AppDockEffect(DockMode.Hidden)
    var exit by remember { mutableStateOf<CompareExit?>(null) }
    var pinned by remember(routeKey) { mutableStateOf<CompareSession?>(null) }
    SideEffect {
        if (session != null) pinned = session
    }
    // 最后一张飞走时会话已经空了，舞台还按飞之前的布局留着，避免飞到一半被顶栏和胶片条挤动。
    val display = session ?: pinned?.takeIf { exit != null }
    if (display == null) {
        Box(Modifier.fillMaxSize().background(DarkroomColors.Room)) {
            if (loadFailed) SimilarLoadFailed(onRetry)
            GlassIconButton(
                onClick = onBack,
                modifier = Modifier.statusBarsPadding().padding(Dimens.ScreenSide),
            ) {
                Icon(
                    DarkroomIcons.Back,
                    contentDescription = stringResource(R.string.cd_back),
                    tint = DarkroomColors.Paper,
                    modifier = Modifier.size(Dimens.ToolIcon),
                )
            }
        }
        return
    }
    val photos = display.photos
    val heroPhotos = if (session != null) photos else emptyList()
    val sourceKey = display.sourceKey
    var currentId by rememberSaveable(routeKey) { mutableLongStateOf(photoId) }
    val found = photos.indexOfFirst { it.id == currentId }
    val safeIndex = if (found >= 0) found else 0
    val scope = rememberCoroutineScope()
    val motion = remember { SimilarCompareMotion(scope) }
    val density = LocalDensity.current
    var stageSize by remember { mutableIntStateOf(0) }
    var stageHeight by remember { mutableIntStateOf(0) }
    val animations = LocalAnimationsEnabled.current
    val current = photos[safeIndex]
    val currentMark = marks[current.id] ?: SimilarMark.Keep
    val beginExit = { photo: PhotoItem, startY: Float, startBrightness: Float ->
        val next = nextComparePhotoId(photos, photo.id)
        if (next != null) currentId = next
        val flyingLast = next == null
        if (animations && motion.height > 0f) {
            if (flyingLast) onLastFlight(true)
            val flight = ExitFlight(
                id = 0L,
                page = ReviewPage.Photo(photo),
                kind = ExitKind.Down,
                startY = startY,
                startScale = motion.zoom,
                startSaturation = 1f,
                startBrightness = startBrightness,
                velocityY = 0f,
                target = Offset(0f, motion.height),
                rise = 0f,
            )
            val leaving = CompareExit(flight, motion.zoomOffset)
            exit = leaving
            scope.launch {
                flight.progress.animateTo(1f, tween(MotionDurations.EXIT_MAIN_MAX, easing = LinearEasing))
                if (exit?.flight === flight) exit = null
                if (flyingLast) onLastFlight(false)
            }
        }
    }
    LaunchedEffect(current.id) { onWatch(current) }
    SideEffect {
        if (found < 0 && photos.isNotEmpty()) currentId = photos[safeIndex].id
        motion.pageIndex = safeIndex
        motion.pageCount = photos.size
        motion.accepting = session != null && !moving
        motion.animationsEnabled = animations
        motion.onCommitPage = { dir ->
            val next = (safeIndex - dir).coerceIn(0, photos.lastIndex)
            currentId = photos[next].id
        }
        motion.onVertical = vert@{ up ->
            val photo = photos.getOrNull(motion.pageIndex) ?: return@vert false
            if (up) {
                onMark(sourceKey, photo.id, SimilarMark.Keep)
                return@vert true
            }
            if (!onSwipeTrash(sourceKey, photo.id)) return@vert false
            val startY = motion.dy
            beginExit(
                photo,
                startY,
                similarHeroBrightness(
                    slot = 0,
                    fingerVertical = true,
                    dy = startY,
                    dx = 0f,
                    width = motion.width,
                    thresholds = motion.thresholds,
                ),
            )
            true
        }
    }
    val keepLabel = stringResource(R.string.similar_action_keep)
    val trashLabel = stringResource(R.string.action_move_to_trash)
    val trashCurrent = {
        val accepted = !moving && onSwipeTrash(sourceKey, current.id)
        if (accepted) beginExit(current, 0f, 1f)
        accepted
    }
    val actions = remember(sourceKey, current.id, keepLabel, trashLabel, moving) {
        listOf(
            CustomAccessibilityAction(keepLabel) {
                onMark(sourceKey, current.id, SimilarMark.Keep)
                true
            },
            CustomAccessibilityAction(trashLabel, trashCurrent),
        )
    }
    val removeCount = photos.count { (marks[it.id] ?: SimilarMark.Keep) == SimilarMark.Remove }
    Column(Modifier.fillMaxSize().background(DarkroomColors.Room)) {
        CompareTopBar(
            index = safeIndex,
            total = photos.size,
            mark = currentMark,
            recommended = current.id == display.recommendedId && current.id in display.sharpness,
            onBack = onBack,
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .onSizeChanged { size ->
                    stageSize = size.width
                    stageHeight = size.height
                    motion.width = size.width.toFloat()
                    motion.height = size.height.toFloat()
                    with(density) {
                        motion.thresholds = ReviewThresholds(
                            vertical = GestureTokens.VerticalThreshold.toPx(),
                            verticalFlingVelocity = GestureTokens.VERTICAL_FLING_VELOCITY_DP * density.density,
                            minFlingDistance = GestureTokens.MinFlingDistance.toPx(),
                            horizontal = ReviewGestureMath.horizontalThreshold(
                                size.width.toFloat(),
                                GestureTokens.HorizontalThresholdMin.toPx(),
                                GestureTokens.HorizontalThresholdMax.toPx(),
                            ),
                            horizontalFlingVelocity = GestureTokens.HORIZONTAL_FLING_VELOCITY_DP * density.density,
                            effectCap = GestureTokens.EffectCapDistance.toPx(),
                            edgeLength = size.width * GestureTokens.EDGE_RESISTANCE_LENGTH_FRACTION,
                        )
                    }
                }
                .pointerInput(motion) { detectReviewGestures(motion) }
                .semantics { customActions = actions },
        ) {
            val imageSize = IntSize(stageSize.coerceAtLeast(1), stageHeight.coerceAtLeast(1))
            for (slot in -1..1) {
                val page = safeIndex + slot
                if (page !in heroPhotos.indices) continue
                ComparePage(
                    photo = heroPhotos[page],
                    mark = marks[heroPhotos[page].id] ?: SimilarMark.Keep,
                    slot = slot,
                    motion = motion,
                    imageSize = imageSize,
                )
            }
            CompareCueLayer(motion = motion, modifier = Modifier.align(Alignment.Center))
            val leaving = exit
            if (leaving != null) {
                CompareExitLayer(
                    exit = leaving,
                    imageSize = imageSize,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val rawLabel = stringResource(R.string.mark_raw).takeIf { isRawFileName(current.displayName) }
            val motionLabel = stringResource(R.string.playback_motion).takeIf { compareMotionId == current.id }
            if (rawLabel != null || motionLabel != null) {
                PhotoMarkText(
                    rawLabel = rawLabel,
                    motionLabel = motionLabel,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = Dimens.ScreenSide, bottom = Dimens.ScreenSide),
                )
            }
        }
        CompareFilmstrip(
            photos = photos,
            marks = marks,
            index = safeIndex,
            onSelect = { i ->
                if (i != safeIndex) {
                    motion.prepareJump()
                    currentId = photos[i].id
                }
            },
        )
        if (undo == null && showCommit) {
            CompareCommit(
                label = similarCommitLabel(removeCount),
                enabled = !moving,
                onCommit = onCommit,
            )
        }
        SimilarUndoBar(
            undo = undo,
            visible = undo != null,
            onUndo = onUndo,
            onExpired = onUndoExpired,
            aboveDock = false,
        )
    }
}

private class CompareExit(val flight: ExitFlight, val startOffset: Offset)

@Composable
private fun CompareExitLayer(exit: CompareExit, imageSize: IntSize, modifier: Modifier = Modifier) {
    val photo = (exit.flight.page as ReviewPage.Photo).photo
    val request = rememberReviewImageRequest(photo, imageSize, sharpen = false)
    Box(
        modifier.graphicsLayer {
            val pose = ExitPath.pose(exit.flight, exit.flight.progress.value)
            val remain = 1f - exit.flight.progress.value
            translationX = pose.x + exit.startOffset.x * remain
            translationY = pose.y + exit.startOffset.y * remain
            scaleX = pose.scale
            scaleY = pose.scale
            alpha = pose.alpha
        },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().tone(
                saturation = { ExitPath.pose(exit.flight, exit.flight.progress.value).saturation },
                brightness = { ExitPath.pose(exit.flight, exit.flight.progress.value).brightness },
            ),
        )
    }
}

@Composable
private fun CompareTopBar(index: Int, total: Int, mark: SimilarMark, recommended: Boolean, onBack: () -> Unit) {
    Box(
        Modifier
            .statusBarsPadding()
            .fillMaxWidth()
            .height(Dimens.HeaderHeight)
            .padding(horizontal = Dimens.ScreenSide),
    ) {
        GlassIconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(
                DarkroomIcons.Back,
                contentDescription = stringResource(R.string.cd_back),
                tint = DarkroomColors.Paper,
                modifier = Modifier.size(Dimens.ToolIcon),
            )
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.similar_position, index + 1, total), style = DarkroomText.Body)
            val caption = when {
                mark == SimilarMark.Remove -> stringResource(R.string.similar_mark_remove) to DarkroomText.Caption.copy(color = DarkroomColors.Fade)
                recommended && mark == SimilarMark.Keep -> stringResource(R.string.similar_recommended) to DarkroomText.Caption
                else -> null
            }
            if (caption != null) {
                Text(caption.first, style = caption.second)
            }
        }
    }
}

@Composable
private fun ComparePage(
    photo: PhotoItem,
    mark: SimilarMark,
    slot: Int,
    motion: SimilarCompareMotion,
    imageSize: IntSize,
) {
    // 和清理页同一条：一倍用屏幕大小的图；RAW / PNG 放大时只给当前这张换更清楚的。邻页不读 zoom。
    val sharpen by remember(photo.id, slot) {
        derivedStateOf { isHeavyStill(photo.displayName) && slot == 0 && motion.isZoomed }
    }
    val request = rememberReviewImageRequest(photo, imageSize, sharpen)
    if (slot == 0) {
        SideEffect {
            motion.fit = ReviewGestureMath.fitSize(photo.width, photo.height, motion.width, motion.height)
            motion.zoomOffset = motion.offsetForNorm()
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = motion.dx + slot * motion.width
                if (slot == 0) {
                    translationX += motion.zoomOffset.x
                    translationY = motion.dy + motion.zoomOffset.y
                    scaleX = motion.zoom
                    scaleY = motion.zoom
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            contentDescription = stringResource(
                R.string.similar_film_cd,
                photo.displayName ?: photo.id.toString(),
                similarMarkLabel(mark),
            ),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .tone(
                    saturation = { 1f },
                    brightness = {
                        similarHeroBrightness(
                            slot = slot,
                            fingerVertical = motion.fingerVertical,
                            dy = motion.dy,
                            dx = motion.dx,
                            width = motion.width,
                            thresholds = motion.thresholds,
                        )
                    },
                ),
        )
    }
}

@Composable
private fun CompareCueLayer(motion: SimilarCompareMotion, modifier: Modifier = Modifier) {
    val dragging by remember { derivedStateOf { motion.fingerVertical } }
    val holding by remember { derivedStateOf { motion.holding } }
    if (dragging) {
        val up by remember { derivedStateOf { motion.dy < 0f } }
        val past by remember { derivedStateOf { abs(motion.dy) >= motion.thresholds.vertical } }
        CompareCue(
            up = up,
            past = past,
            progress = { ReviewGestureMath.cueProgress(motion.dy, motion.thresholds) },
            modifier = modifier,
        )
    } else if (holding) {
        CompareCue(
            up = motion.heldUp,
            past = motion.heldPast,
            progress = { motion.heldProgress },
            modifier = modifier.graphicsLayer { alpha = motion.cueAlpha },
        )
    }
}

@Composable
private fun CompareCue(
    up: Boolean,
    past: Boolean,
    progress: () -> Float,
    modifier: Modifier = Modifier,
) {
    val accent = DarkroomColors.Paper
    val icon = if (up) DarkroomIcons.Check else DarkroomIcons.Trash
    val title = if (up) {
        if (past) R.string.similar_cue_release_keep else R.string.similar_cue_keep
    } else {
        if (past) R.string.similar_cue_release_remove else R.string.similar_cue_remove
    }
    if (!past) {
        val ring = DarkroomColors.Paper.copy(alpha = 0.32f)
        val text = DarkroomColors.Paper.copy(alpha = 0.5f)
        Row(
            modifier,
            horizontalArrangement = Arrangement.spacedBy(Dimens.DayGap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(Dimens.CueRing).drawBehind {
                    val w = Dimens.Hairline.toPx()
                    drawCircle(ring.copy(alpha = ring.alpha * 0.45f), style = Stroke(w))
                    val p = progress()
                    drawArc(
                        ring,
                        -90f,
                        360f * p,
                        false,
                        topLeft = Offset(w / 2f, w / 2f),
                        size = Size(size.width - w, size.height - w),
                        style = Stroke(w, cap = StrokeCap.Round),
                    )
                },
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = text, modifier = Modifier.size(Dimens.CueIcon))
            }
            Text(stringResource(title), style = DarkroomText.CuePre.copy(color = text))
        }
    } else {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(Dimens.CueSolid)
                    .drawBehind {
                        drawCircle(
                            DarkroomColors.Paper.copy(alpha = 0.08f),
                            radius = size.minDimension / 2f + Dimens.DayGap.toPx(),
                        )
                    }
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = DarkroomColors.Room, modifier = Modifier.size(Dimens.CueIconSolid))
            }
            Spacer(Modifier.height(Dimens.CapsuleGap))
            Text(stringResource(title), style = DarkroomText.CueTitle.copy(color = accent))
        }
    }
}

@Composable
private fun CompareFilmstrip(
    photos: List<PhotoItem>,
    marks: Map<Long, SimilarMark>,
    index: Int,
    onSelect: (Int) -> Unit,
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = index)
    val density = LocalDensity.current
    val animations = LocalAnimationsEnabled.current
    val itemPx = with(density) { Dimens.SimilarThumb.roundToPx() }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(index) {
        snapshotFlow {
            val info = state.layoutInfo
            info.viewportEndOffset - info.viewportStartOffset
        }.first { it > 0 }
        state.followFilmstrip(index, itemPx, animate = placed && animations)
        placed = true
    }
    LazyRow(
        state = state,
        modifier = Modifier
            .padding(top = Dimens.DayGap)
            .fillMaxWidth()
            .height(Dimens.SimilarThumb)
            .padding(horizontal = Dimens.ScreenSide),
        horizontalArrangement = Arrangement.spacedBy(Dimens.DayGap),
    ) {
        itemsIndexed(photos, key = { _, photo -> photo.id }) { i, photo ->
            val mark = marks[photo.id] ?: SimilarMark.Keep
            SimilarThumb(
                photo = photo,
                mark = mark,
                contentDescription = stringResource(
                    R.string.similar_film_cd,
                    photo.displayName ?: photo.id.toString(),
                    similarMarkLabel(mark),
                ),
                onClick = { onSelect(i) },
                framed = i == index,
            )
        }
    }
}

@Composable
private fun CompareCommit(label: String, enabled: Boolean, onCommit: () -> Unit) {
    val shape = RoundedCornerShape(Dimens.SmallButtonHeight / 2)
    Box(
        Modifier
            .navigationBarsPadding()
            .padding(horizontal = Dimens.ScreenSide, vertical = Dimens.FreePadding)
            .fillMaxWidth()
            .height(Dimens.SmallButtonHeight)
            .clip(shape)
            .glassBlur(GlassSpecs.Button)
            .border(Dimens.Hairline, DarkroomColors.GlassRim, shape)
            .plainClickable(enabled = enabled, onClick = onCommit),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = DarkroomText.Button13)
    }
}

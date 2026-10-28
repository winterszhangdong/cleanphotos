package com.winters.cleanphotos.feature.similar

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
import androidx.compose.ui.platform.LocalContext
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
import com.winters.cleanphotos.core.design.GestureTokens
import com.winters.cleanphotos.core.design.GlassIconButton
import com.winters.cleanphotos.core.design.GlassSpecs
import com.winters.cleanphotos.core.design.LocalAnimationsEnabled
import com.winters.cleanphotos.core.design.glassBlur
import com.winters.cleanphotos.core.design.plainClickable
import com.winters.cleanphotos.core.navigation.AppDockEffect
import com.winters.cleanphotos.core.navigation.DockMode
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.SimilarGroup
import com.winters.cleanphotos.domain.model.SimilarMark
import com.winters.cleanphotos.feature.review.ReviewGestureMath
import com.winters.cleanphotos.feature.review.ReviewThresholds
import com.winters.cleanphotos.feature.review.detectReviewGestures
import com.winters.cleanphotos.feature.review.reviewImageRequest
import com.winters.cleanphotos.feature.review.tone
import kotlin.math.abs
import kotlinx.coroutines.flow.first

/**
 * 对比页和照片页共用同一个 [SimilarViewModel]，挂在 all_photos 的返回栈上。
 * 扫描把这组刷没了就回到列表。返回不提交。
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
    val group = uiState.groups.firstOrNull { it.key == groupKey }
    LaunchedEffect(group, uiState.phase) {
        if (group == null && uiState.phase == SimilarPhase.Ready) onBack()
    }
    SimilarCompareScreen(
        group = group,
        photoId = photoId,
        marks = uiState.marks[groupKey].orEmpty(),
        moving = uiState.moving,
        onBack = onBack,
        onMark = viewModel::onMark,
        onCommit = { viewModel.onCommit(groupKey, onBack) },
    )
}

@Composable
private fun SimilarCompareScreen(
    group: SimilarGroup?,
    photoId: Long,
    marks: Map<Long, SimilarMark>,
    moving: Boolean,
    onBack: () -> Unit,
    onMark: (String, Long, SimilarMark) -> Unit,
    onCommit: () -> Unit,
) {
    AppDockEffect(DockMode.Hidden)
    if (group == null) {
        Box(Modifier.fillMaxSize().background(DarkroomColors.Room)) {
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
    val photos = group.photos
    var index by rememberSaveable(group.key, photoId) {
        mutableIntStateOf(photos.indexOfFirst { it.id == photoId }.coerceAtLeast(0))
    }
    val safeIndex = index.coerceIn(0, photos.lastIndex)
    val scope = rememberCoroutineScope()
    val motion = remember { SimilarCompareMotion(scope) }
    val density = LocalDensity.current
    var stageSize by remember { mutableIntStateOf(0) }
    var stageHeight by remember { mutableIntStateOf(0) }
    val animations = LocalAnimationsEnabled.current
    val current = photos[safeIndex]
    val currentMark = marks[current.id] ?: SimilarMark.Keep
    SideEffect {
        motion.pageIndex = safeIndex
        motion.pageCount = photos.size
        motion.accepting = !moving
        motion.animationsEnabled = animations
        motion.onCommitPage = { dir -> index = (safeIndex - dir).coerceIn(0, photos.lastIndex) }
        motion.onVertical = vert@{ up ->
            val photo = photos.getOrNull(motion.pageIndex) ?: return@vert
            onMark(group.key, photo.id, if (up) SimilarMark.Keep else SimilarMark.Remove)
        }
    }
    val keepLabel = stringResource(R.string.similar_action_keep)
    val removeLabel = stringResource(R.string.similar_mark_remove)
    val actions = remember(group.key, current.id, keepLabel, removeLabel) {
        listOf(
            CustomAccessibilityAction(keepLabel) {
                onMark(group.key, current.id, SimilarMark.Keep)
                true
            },
            CustomAccessibilityAction(removeLabel) {
                onMark(group.key, current.id, SimilarMark.Remove)
                true
            },
        )
    }
    val removeCount = photos.count { (marks[it.id] ?: SimilarMark.Keep) == SimilarMark.Remove }
    Column(Modifier.fillMaxSize().background(DarkroomColors.Room)) {
        CompareTopBar(
            index = safeIndex,
            total = photos.size,
            mark = currentMark,
            recommended = current.id == group.recommendedId && current.id in group.sharpness,
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
                if (page !in photos.indices) continue
                ComparePage(
                    photo = photos[page],
                    mark = marks[photos[page].id] ?: SimilarMark.Keep,
                    slot = slot,
                    motion = motion,
                    imageSize = imageSize,
                )
            }
            CompareCueLayer(motion = motion, modifier = Modifier.align(Alignment.Center))
        }
        CompareFilmstrip(
            photos = photos,
            marks = marks,
            index = safeIndex,
            onSelect = { i ->
                if (i != safeIndex) {
                    motion.prepareJump()
                    index = i
                }
            },
        )
        CompareCommit(
            label = similarCommitLabel(removeCount),
            enabled = !moving,
            onCommit = onCommit,
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
    val context = LocalContext.current
    val request = remember(photo.id, photo.uri, imageSize) { reviewImageRequest(context, photo, imageSize) }
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
    val icon = if (up) DarkroomIcons.Check else DarkroomIcons.Down
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

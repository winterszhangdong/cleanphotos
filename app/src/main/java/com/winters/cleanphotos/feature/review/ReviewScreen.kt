package com.winters.cleanphotos.feature.review

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.imageLoader
import androidx.compose.ui.graphics.asImageBitmap
import com.winters.cleanphotos.core.navigation.AppDockEffect
import com.winters.cleanphotos.core.navigation.DockMode
import com.winters.cleanphotos.core.navigation.LocalAppDock
import com.winters.cleanphotos.core.design.BottomTab
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomMenu
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.GestureTokens
import com.winters.cleanphotos.core.design.GlassBackdropHost
import com.winters.cleanphotos.core.navigation.BackdropSnapshot
import com.winters.cleanphotos.core.navigation.isNavEntering
import com.winters.cleanphotos.core.navigation.predictiveBackdrop
import com.winters.cleanphotos.core.navigation.predictiveClip
import com.winters.cleanphotos.core.navigation.predictiveShrink
import com.winters.cleanphotos.core.navigation.rememberPredictiveBack
import com.winters.cleanphotos.core.navigation.navChrome
import com.winters.cleanphotos.core.navigation.sharedPhoto
import com.winters.cleanphotos.core.design.Haptics
import com.winters.cleanphotos.core.design.MenuOption
import com.winters.cleanphotos.core.design.MenuScrim
import com.winters.cleanphotos.core.design.MotionDurations
import com.winters.cleanphotos.core.design.MotionEasings
import com.winters.cleanphotos.core.design.SystemBarIcons
import com.winters.cleanphotos.core.design.glassBackdropSource
import com.winters.cleanphotos.domain.model.PhotoActionType
import com.winters.cleanphotos.domain.model.ReviewPage
import com.winters.cleanphotos.domain.model.ReviewScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 清理页（暗房）。
 * 只做展示与手势；业务全部在 [ReviewViewModel]（上下滑立即返回下一页 key，UI 同帧切换）。
 */
@Composable
fun ReviewScreen(
    uiState: ReviewUiState,
    onSwipeUp: (Long) -> String?,
    onSwipeDown: (Long) -> String?,
    onPageSettled: (String) -> Unit,
    onUndo: () -> String?,
    onUndoExpired: (Long) -> Unit,
    onDismissError: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToTrash: () -> Unit,
    onDismissTutorial: () -> Unit,
    /** 入口照片：会话还在加载时先用它的网格缩略图铺满，避免进入那几帧空屏。 */
    entryPhotoId: Long? = null,
    modifier: Modifier = Modifier,
) {
    SystemBarIcons(lightBackground = false)
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    ImmersiveInLandscape(landscape)
    val density = LocalDensity.current
    val view = LocalView.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stage = remember { ReviewStageState(scope) }
    val landing = LocalAppDock.current.landing
    LaunchedEffect(Unit) { landing.reset() }
    var menuOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = menuOpen) { menuOpen = false }
    val back = rememberPredictiveBack(enabled = !menuOpen, onBack = onNavigateBack)

    val statusTop = if (landscape) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var size by remember { mutableStateOf(IntSize.Zero) }

    // —— 同步 ViewModel 序列（本地 currentKey 优先，见 ReviewStageState）——
    if (stage.pages !== uiState.pages) stage.pages = uiState.pages
    val lastVm = remember { arrayOfNulls<Any>(2) }
    if (lastVm[0] !== uiState.pages || lastVm[1] != uiState.currentKey) {
        // ViewModel 状态变了：本地 key 在新序列里仍然有效就保留（本地先切页的情况）；否则采用 ViewModel 的 key
        lastVm[0] = uiState.pages
        lastVm[1] = uiState.currentKey
        val localValid = stage.currentKey != null && uiState.pages.any { it.key == stage.currentKey }
        if (!localValid && !stage.sessionEndedLocally && uiState.currentKey != null) {
            com.winters.cleanphotos.core.util.ReviewTrace.log("vmSync", "local" to stage.currentKey, "vm" to uiState.currentKey, "pages" to uiState.pages.size)
            stage.currentKey = uiState.currentKey
            stage.noteCurrentDay(tick = false)
        }
    }

    with(density) {
        stage.thresholds = ReviewThresholds(
            vertical = GestureTokens.VerticalThreshold.toPx(),
            verticalFlingVelocity = GestureTokens.VERTICAL_FLING_VELOCITY_DP * this.density,
            minFlingDistance = GestureTokens.MinFlingDistance.toPx(),
            horizontal = ReviewGestureMath.horizontalThreshold(
                size.width.toFloat(), GestureTokens.HorizontalThresholdMin.toPx(), GestureTokens.HorizontalThresholdMax.toPx(),
            ),
            horizontalFlingVelocity = GestureTokens.HORIZONTAL_FLING_VELOCITY_DP * this.density,
            effectCap = GestureTokens.EffectCapDistance.toPx(),
            edgeLength = size.width * GestureTokens.EDGE_RESISTANCE_LENGTH_FRACTION,
        )
    }
    stage.onSettled = onPageSettled
    stage.onSwipe = { release, id ->
        val key = if (release == VerticalRelease.Up) onSwipeUp(id) else onSwipeDown(id)
        if (key == null) stage.sessionEndedLocally = true
        key
    }
    stage.onThresholdChanged = { past -> if (past) Haptics.thresholdActivate(view) else Haptics.thresholdDeactivate(view) }
    stage.onDayChanged = { Haptics.dayChange(view) }
    stage.onExitStarted = { kind -> landing.show(scope, if (kind == ExitKind.Up) BottomTab.Favorites else BottomTab.Trash) }
    stage.exitTarget = { kind -> navTarget(kind, size, density, navBottom) }

    // 会话结束且没有小结：等离场主体结束后回到入口页
    LaunchedEffect(uiState.isCompleted) {
        if (uiState.isCompleted) {
            delay(MotionDurations.EXIT_MAIN_MAX.toLong())
            onNavigateBack()
        }
    }
    // 预取大图：往后 3 张（连续快滑时每 ~180ms 前进一张，只预取 2 张会被追上，见 P4 验收日志）、往回 2 张
    val imageSize = remember(size) { IntSize(size.width.coerceAtLeast(1), size.height.coerceAtLeast(1)) }
    LaunchedEffect(stage.currentKey, uiState.pages, imageSize) {
        val i = uiState.pages.indexOfFirst { it.key == stage.currentKey }
        if (i < 0 || size == IntSize.Zero) return@LaunchedEffect
        for (j in listOf(i + 1, i + 2, i + 3, i - 1, i - 2)) {
            val p = uiState.pages.getOrNull(j) as? ReviewPage.Photo ?: continue
            context.imageLoader.enqueue(reviewImageRequest(context, p.photo, imageSize))
        }
    }

    val current = stage.currentPage
    val currentPhoto = (current as? ReviewPage.Photo)?.photo
    val onSummary = current is ReviewPage.Summary
    val tutorial = uiState.showTutorial && !onSummary
    // 只在「是否竖向拖动 / 方向」翻转时重组整屏；直接读 stage.dy 会让拖动的每一帧都重组 ReviewScreen（P4 卡顿剖析）
    val vertical by remember { derivedStateOf { stage.isVerticalDrag } }
    val upDrag by remember { derivedStateOf { stage.dy < 0f } }

    GlassBackdropHost {
        AppDockEffect(DockMode.Landing)
        Box(
            modifier
                .fillMaxSize()
                .onSizeChanged {
                    size = it
                    stage.width = it.width.toFloat()
                    stage.height = it.height.toFloat()
                }
                // 返回手势跟手缩小，后面露出照片页快照
                .predictiveBackdrop(back) { BackdropSnapshot.image }
                .predictiveShrink(back)
                .predictiveClip(back)
                .background(DarkroomColors.Room),
        ) {
            ReviewStage(
                state = stage,
                tutorialDim = tutorial,
                imageSize = imageSize,
                summaries = uiState.summaries,
                summaryContent = { summary, isCurrent ->
                    val i = uiState.pages.indexOfFirst { it.key == ReviewPage.summaryKeyOf(summary.dayKey) }
                    ReviewSummaryPage(
                        summary = summary,
                        isCurrent = isCurrent,
                        showNext = i in 0 until uiState.pages.lastIndex,
                        landscape = landscape,
                        onNext = { stage.goNext() },
                        onBackToPhotos = onNavigateBack,
                    )
                },
                modifier = Modifier.glassBackdropSource(),
            )

            // 会话加载中（还没有当前页）：直接取内存里的网格缩略图铺满，进入那几帧不空屏
            val navEntering = isNavEntering()
            if (stage.currentPage == null && uiState.isLoading && entryPhotoId != null) {
                val thumb = remember(entryPhotoId) {
                    coil.Coil.imageLoader(context).memoryCache
                        ?.get(coil.memory.MemoryCache.Key(com.winters.cleanphotos.core.design.photoThumbKey(entryPhotoId)))
                        ?.bitmap?.asImageBitmap()
                }
                if (thumb != null) {
                    androidx.compose.foundation.Image(
                        bitmap = thumb,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        // 与随后大图显影的起始态一致（亮度 0.6 / 对比 0.85 / 饱和 0.6），交接时不跳亮度
                        // 共享元素带进来时大图不显影，占位也保持原色
                        colorFilter = if (navEntering) null else androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                            androidx.compose.ui.graphics.ColorMatrix(
                                com.winters.cleanphotos.core.design.DevelopMatrix.compose(
                                    com.winters.cleanphotos.core.design.DevelopProfiles.ReviewNext.brightness.start,
                                    com.winters.cleanphotos.core.design.DevelopProfiles.ReviewNext.contrast.start,
                                    com.winters.cleanphotos.core.design.DevelopProfiles.ReviewNext.saturation.start,
                                ),
                            ),
                        ),
                        modifier = Modifier.zIndex(1f).fillMaxSize().sharedPhoto(entryPhotoId, expandsHere = true),
                    )
                }
            }

            // 上下暖黑渐变；拖动时随控件一起退开
            if (currentPhoto != null && !tutorial) {
                Box(
                    Modifier.zIndex(5f).align(Alignment.TopCenter).fillMaxWidth().height(if (landscape) 96.dp else 160.dp).navChrome()
                        .graphicsLayer { alpha = stage.chromeAlpha.value }
                        .background(Brush.verticalGradient(listOf(DarkroomColors.ScrimTop, Color.Transparent))),
                )
                if (!landscape) {
                    Box(
                        Modifier.zIndex(5f).align(Alignment.BottomCenter).fillMaxWidth().height(200.dp).navChrome()
                            .graphicsLayer { alpha = stage.chromeAlpha.value }
                            .background(Brush.verticalGradient(listOf(Color.Transparent, DarkroomColors.ScrimBottom))),
                    )
                }
            }

            // 进度条：教学期间不显示
            if (!tutorial && current != null) {
                ReviewProgressBar(
                    progress = uiState.progress[current.key],
                    modifier = Modifier
                        .zIndex(6f)
                        .align(Alignment.TopCenter)
                        .navChrome()
                        .padding(
                            top = statusTop + if (landscape) 12.dp else 8.dp,
                            start = if (landscape) 48.dp else 16.dp,
                            end = if (landscape) 24.dp else 16.dp,
                        )
                        .graphicsLayer { alpha = if (onSummary) 1f else stage.chromeAlpha.value },
                )
            }

            if (currentPhoto != null || (tutorial && currentPhoto != null)) {
                ReviewTopBar(
                    photo = currentPhoto,
                    onBack = onNavigateBack,
                    onMore = if (tutorial) null else ({ menuOpen = true }),
                    modifier = Modifier
                        .zIndex(6f)
                        .align(Alignment.TopCenter)
                        .navChrome()
                        .padding(
                            top = statusTop + when {
                                landscape -> 24.dp
                                tutorial -> 12.dp
                                else -> 20.dp
                            },
                            start = if (landscape) 40.dp else 12.dp,
                            end = if (landscape) 24.dp else 12.dp,
                        )
                        .graphicsLayer { alpha = stage.chromeAlpha.value },
                )
            }

            // 松手后提示圆钮保持满强度，随离场 266ms 淡出（不再在松手那一帧消失）
            val releaseCue by remember { derivedStateOf { stage.releaseCue.value > 0f } }
            if (!vertical && releaseCue) {
                val up = stage.releaseCueUp
                ReviewCue(
                    state = stage,
                    up = up,
                    scope = uiState.scope,
                    released = true,
                    modifier = Modifier
                        .zIndex(7f)
                        .align(if (up) Alignment.BottomCenter else Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(top = if (up) 0.dp else statusTop + 24.dp, bottom = if (up) navBottom + 16.dp else 0.dp)
                        .graphicsLayer { alpha = stage.releaseCue.value },
                )
            }

            // 过阈值提示：上滑在下方，下滑在上方
            if (vertical) {
                val up = upDrag
                ReviewCue(
                    state = stage,
                    up = up,
                    scope = uiState.scope,
                    modifier = Modifier
                        .zIndex(7f)
                        .align(if (up) Alignment.BottomCenter else Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(
                            top = if (up) 0.dp else statusTop + if (stage.pastThreshold) 24.dp else 16.dp,
                            bottom = if (up) navBottom + if (stage.pastThreshold) 16.dp else 4.dp else 0.dp,
                        ),
                )
            }

            val undoVisible = uiState.undo != null && !vertical && !tutorial && !onSummary
            if (currentPhoto != null && !tutorial) {
                AnimatedVisibility(
                    visible = !undoVisible,
                    enter = fadeIn(tween(MotionDurations.SHORT)),
                    exit = fadeOut(tween(MotionDurations.MICRO)),
                    modifier = Modifier
                        .zIndex(6f)
                        .align(Alignment.BottomStart)
                        .navChrome()
                        .padding(
                            start = if (landscape) 48.dp else 16.dp,
                            end = 16.dp,
                            bottom = navBottom + if (landscape) 20.dp else 32.dp,
                        )
                        .graphicsLayer { alpha = stage.chromeAlpha.value * (1f - landing.alpha.value) /* 落点导航浮现时让开 */ },
                ) {
                    ReviewMeta(currentPhoto, showHints = uiState.showDirectionHints && !landscape, scope = uiState.scope)
                }
            }

            ReviewUndoBar(
                undo = uiState.undo,
                visible = undoVisible,
                onUndo = {
                    val info = uiState.undo
                    val type = info?.type
                    val key = onUndo()
                    if (key != null && type != null) {
                        stage.sessionEndedLocally = false
                        stage.undo(ReviewPage.Photo(info.photo), if (type == PhotoActionType.MoveToTrash) ExitKind.Down else ExitKind.Up)
                        landing.show(scope, if (type == PhotoActionType.MoveToTrash) BottomTab.Trash else BottomTab.Favorites)
                    }
                },
                onExpired = onUndoExpired,
                modifier = Modifier
                    .zIndex(8f)
                    .align(if (landscape) Alignment.BottomEnd else Alignment.BottomCenter)
                    .padding(
                        start = if (landscape) 0.dp else 16.dp,
                        end = if (landscape) 24.dp else 16.dp,
                        bottom = navBottom + if (landscape) 20.dp else 32.dp,
                    )
                    .then(if (landscape) Modifier.width(320.dp) else Modifier.fillMaxWidth())
                    // 落点导航浮现时撤回条让到导航胶囊上方，两者不重叠
                    .graphicsLayer { translationY = -UNDO_LIFT_OVER_NAV.toPx() * landing.alpha.value },
            )

            // 会话首尾越界提示
            EdgeHint(stage, Modifier.zIndex(6f).align(Alignment.Center))

            if (tutorial) {
                Box(Modifier.zIndex(10f).fillMaxSize()) {
                    ReviewTutorialOverlay(topInset = statusTop, bottomInset = navBottom, onKnow = onDismissTutorial)
                }
            }

            uiState.errorMessage?.let { message ->
                LaunchedEffect(message) {
                    delay(ERROR_VISIBLE_MS)
                    onDismissError()
                }
                Text(
                    message,
                    style = DarkroomText.EdgeHint,
                    modifier = Modifier
                        .zIndex(11f)
                        .align(Alignment.BottomCenter)
                        .padding(bottom = navBottom + 104.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(DarkroomColors.UndoGlass)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }

            if (menuOpen) {
                Box(Modifier.zIndex(12f).fillMaxSize()) {
                    MenuScrim(visible = true, onDismiss = { menuOpen = false })
                    DarkroomMenu(
                        visible = true,
                        header = "更多",
                        options = listOf(
                            MenuOption("去垃圾桶", uiState.trashCount.takeIf { it > 0 }?.toString(), false) {
                                menuOpen = false
                                onNavigateToTrash()
                            },
                            MenuOption("回到照片", null, false) {
                                menuOpen = false
                                onNavigateBack()
                            },
                        ),
                    )
                }
            }
        }
    }
}

/** 会话第一张继续右滑 / 最后一张继续左滑时露出「第一张 / 最后一张」。 */
@Composable
private fun EdgeHint(stage: ReviewStageState, modifier: Modifier) {
    // derivedStateOf：只在越界状态翻转时重组，拖动中 dx 每帧变化不触发重组
    val atStart by remember(stage) { derivedStateOf { stage.dx > 0f && stage.previousPage == null && stage.axis == DragAxis.Horizontal } }
    val atEnd by remember(stage) { derivedStateOf { stage.dx < 0f && stage.nextPage == null && stage.axis == DragAxis.Horizontal } }
    if (!atStart && !atEnd) return
    Box(modifier.fillMaxSize()) {
        Text(
            if (atStart) "第一张" else "最后一张",
            style = DarkroomText.EdgeHint,
            modifier = Modifier
                .align(if (atStart) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 16.dp)
                .graphicsLayer { alpha = (abs(stage.dx) / (stage.thresholds.edgeLength * 0.5f)).coerceIn(0f, 1f) }
                .clip(RoundedCornerShape(14.dp))
                .background(DarkroomColors.UndoGlass)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** 导航胶囊里「收藏」（第 2 项）/「垃圾桶」（第 3 项）的中心，相对屏幕中心。 */
private fun navTarget(kind: ExitKind, size: IntSize, density: androidx.compose.ui.unit.Density, navBottom: androidx.compose.ui.unit.Dp): Offset =
    with(density) {
        val index = if (kind == ExitKind.Up) 1 else 2
        val x = (index - 1) * Dimens.NavItemWidth.toPx()
        val y = size.height / 2f - (navBottom + Dimens.NavBottom + Dimens.NavHeight / 2).toPx()
        Offset(x, y)
    }

/** 横屏隐藏状态栏（沉浸式），回到竖屏或离开清理页时恢复。 */
@Composable
private fun ImmersiveInLandscape(landscape: Boolean) {
    val view = LocalView.current
    DisposableEffect(landscape) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (landscape) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.statusBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.statusBars()) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val ERROR_VISIBLE_MS = 2500L

/** 导航胶囊顶边（24 + 44dp）+ 12dp 间距 − 撤回条原底距 32dp。 */
private val UNDO_LIFT_OVER_NAV = Dimens.NavBottom + Dimens.NavHeight + 12.dp - 32.dp

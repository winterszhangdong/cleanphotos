package com.winters.cleanphotos.feature.contactsheet

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.winters.cleanphotos.R
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomIcons
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.FilingMotion
import com.winters.cleanphotos.core.design.GlassIconButton
import com.winters.cleanphotos.core.design.Haptics
import com.winters.cleanphotos.core.design.MotionDurations
import com.winters.cleanphotos.core.design.MotionEasings
import com.winters.cleanphotos.core.design.MotionSprings
import com.winters.cleanphotos.core.design.SystemBarIcons
import com.winters.cleanphotos.core.design.TrayTextButton
import com.winters.cleanphotos.core.design.UndoBar
import com.winters.cleanphotos.core.design.UndoCapsuleContent
import com.winters.cleanphotos.core.design.blockTouchesBelow
import com.winters.cleanphotos.core.locale.currentLocale
import com.winters.cleanphotos.core.navigation.AppDockEffect
import com.winters.cleanphotos.core.navigation.DockMode
import com.winters.cleanphotos.core.text.asString
import com.winters.cleanphotos.core.util.DayDisplay
import com.winters.cleanphotos.domain.model.PhotoAlbum
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.feature.allphotos.PhotoHitRects
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** 装袋从哪里起飞：点袋子时从胶片上各自的位置；拖进袋子时从手指下的照片叠。[intoSheet]：飞进相册面板里的一行。 */
private sealed interface Launch {
    val heldCount: Int
    val heldCover: PhotoItem?
    val intoSheet: Boolean
    /** 面板行飞入前的相片（[SheetPrints.of]）。 */
    val heldPrints: List<PhotoItem>
    data class Frames(
        val rects: Map<Long, Rect>,
        override val heldCount: Int,
        override val heldCover: PhotoItem?,
        override val intoSheet: Boolean = false,
        override val heldPrints: List<PhotoItem> = emptyList(),
    ) : Launch
    data class Stack(
        val at: Offset,
        override val heldCount: Int,
        override val heldCover: PhotoItem?,
        override val intoSheet: Boolean = false,
        override val heldPrints: List<PhotoItem> = emptyList(),
    ) : Launch
}

@Composable
internal fun ContactSheetScreen(
    state: ContactSheetUiState,
    flights: Flow<ContactSheetEffect>,
    onBack: () -> Unit,
    onToggle: (Long) -> Unit,
    onCircle: (Long) -> Unit,
    onCircleAll: () -> Unit,
    onSystemFavoriteToggle: () -> Unit,
    onFile: (SheetTarget) -> Unit,
    onUnfavorite: () -> Unit,
    onUndo: () -> Unit,
    onUndoExpired: (Long) -> Unit,
    onNextDay: (String) -> Unit,
    onFlightDone: (String) -> Unit = {},
) {
    SystemBarIcons(lightBackground = false)
    AppDockEffect(DockMode.Hidden)
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current
    val visuals = remember { FilingVisuals() }
    val hits = remember { PhotoHitRects() }
    val gridState = rememberLazyGridState()
    val trayRowState = rememberLazyListState()
    val sheetListState = rememberLazyListState()
    var launch by remember { mutableStateOf<Launch?>(null) }
    var naming by remember { mutableStateOf<Offset?>(null) }
    var namingOpen by rememberSaveable { mutableStateOf(false) }
    var namingName by rememberSaveable { mutableStateOf("") }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var entering by rememberSaveable { mutableStateOf(true) }
    // 相册面板：sheetOpen 时在组合里，sheetAnim 是升起进度；parked = 照片叠停靠在面板标题左边
    var sheetOpen by remember { mutableStateOf(false) }
    val sheetAnim = remember { Animatable(0f) }
    var query by rememberSaveable { mutableStateOf("") }
    var parked by remember { mutableStateOf(false) }
    val now = remember(sheetOpen) { System.currentTimeMillis() }
    val latest by rememberUpdatedState(state)
    LaunchedEffect(state.loaded) {
        if (state.loaded && state.photos.isNotEmpty()) {
            withFrameNanos { }
            entering = false
        }
    }
    val stackLift = with(density) { FilingMotion.StackLift.toPx() }
    val slop = with(density) { 8.dp.toPx() }
    val placement = remember(stackLift) { StackPlacement(stackLift) }

    fun albumBefore(path: String): PhotoAlbum? = state.albums.firstOrNull { it.relativePath == path }

    // ViewModel 忙（上一袋还在等授权 / 移动）时它会忽略这次装袋；起飞点也不能被这次覆盖
    fun canFile() = !state.busy && launch == null

    fun circledRects(): Map<Long, Rect> =
        state.photos.filter { it.id in state.circled }.mapNotNull { p -> hits[p.id]?.let { p.id to it } }.toMap()

    /** 没放进袋子：照片叠散开，每张落回自己的格子，落定后格子里的原图回来。 */
    fun putBack(drag: DragStack) {
        if (drag.returning) return
        // 停靠着的小叠从标题左边散开
        if (parked) {
            visuals.parkAt?.let { drag.pointer = placement.pointerFor(it) }
            parked = false
        }
        scope.launch {
            drag.putBack(drag.photos.mapNotNull { p -> hits[p.id]?.let { p.id to it } }.toMap())
            if (visuals.drag === drag) visuals.drag = null
        }
    }

    fun fileFromFrames(album: PhotoAlbum) {
        if (!canFile()) return
        launch = Launch.Frames(circledRects(), album.count, album.recent.firstOrNull())
        visuals.trayPicked = HeldEnvelope.of(album)
        onFile(SheetTarget.Existing(album))
    }

    /** 点「还有 N 个」或把照片叠松在上面：面板升起（拖动中不会自己打开，用户 2026-10-27 决定）。 */
    fun openSheet() {
        if (sheetOpen && sheetAnim.targetValue == 1f) return
        query = ""
        sheetOpen = true
        visuals.sheetPicked = null
        scope.launch {
            visuals.clearHover(scope)
            sheetListState.scrollToItem(0)
            sheetAnim.animateTo(1f, MotionSprings.Settle.spec())
        }
    }

    suspend fun closeSheet() {
        visuals.sheetPicked = null
        sheetAnim.animateTo(0f, MotionSprings.Settle.spec())
        sheetOpen = false
    }

    /** 下拉 / 点压暗处 / 返回：停靠着的照片叠落回格子，圈选保留。 */
    fun dismissSheet() {
        visuals.drag?.let(::putBack)
        scope.launch { closeSheet() }
    }

    fun startNaming(name: String, at: Offset?) {
        naming = at
        namingName = name
        namingOpen = true
    }

    visuals.onEnter = { Haptics.hoverTick(view) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(DarkroomColors.Room)
            .onGloballyPositioned { origin = it.positionInRoot() },
    ) {
        val stripWidth = maxWidth - Dimens.ScreenSide * 2
        val cols = FilmGeometry.columns(state.photos.size)
        val frameW = FilmGeometry.frameWidth(stripWidth, cols)
        val frameSize = frameW to frameW * 3f / 4f
        val screenW = with(density) { maxWidth.toPx() }
        // launch：已经点了袋子、正等授权或移动结果。ViewModel 先清圈选、飞入后到，这段时间托盘不能先降下去
        val trayVisible = state.circled.isNotEmpty() || launch != null || visuals.drag != null || visuals.flight != null || visuals.peek != null || sheetOpen
        // 托盘降下去以后回到开头：下次升起总是从「新袋子」和最近用的几个开始，不停在上次滚到的位置
        val trayShown = trayVisible && state.summary == null
        LaunchedEffect(trayShown) {
            if (!trayShown) {
                delay(MotionDurations.LONG.toLong()) // 等 Settle 滑出走完，不在眼前跳
                trayRowState.scrollToItem(0)
            }
        }
        val trayTop = with(density) { maxHeight.toPx() } - with(density) { EnvelopeGeometry.TrayHeight.toPx() }
        val summaryVisible = state.summary != null && visuals.flight == null

        Column(Modifier.fillMaxSize()) {
            SheetHeader(state, onBack, onCircleAll)
            FilmSheet(
                photos = state.photos,
                circled = state.circled,
                date = state.date,
                gridState = gridState,
                contentPadding = PaddingValues(
                    start = Dimens.ScreenSide,
                    end = Dimens.ScreenSide,
                    top = 12.dp,
                    bottom = EnvelopeGeometry.TrayHeight + 48.dp,
                ),
                stripWidth = stripWidth,
                hits = hits,
                entering = entering,
                slotAlpha = visuals::slotAlpha,
                gestures = SheetGestureCallbacks(
                    onTap = onToggle,
                    onCircle = onCircle,
                    onPickUp = { p ->
                        val drag = DragStack(state.photos.filter { it.id in state.circled }, p, circledRects())
                        visuals.drag = drag
                        scope.launch { drag.pickUp() }
                    },
                    onDragMove = { p ->
                        visuals.drag?.pointer = p
                        scope.launch { visuals.updateHover(p, scope, slop) }
                    },
                    onDrop = {
                        val drag = visuals.drag
                        if (drag == null) return@SheetGestureCallbacks
                        val target = visuals.hovered
                        scope.launch { visuals.clearHover() }
                        when (target) {
                            NEW_ENVELOPE_KEY -> {
                                visuals.drag = null
                                startNaming("", placement.center(drag.pointer))
                            }
                            MORE_KEY -> {
                                // 在「还有 N 个」上松手：打开面板，照片叠停靠在标题左边等点一行
                                parked = true
                                openSheet()
                            }
                            null -> putBack(drag)
                            else -> {
                                val album = albumBefore(target)
                                if (album == null || !canFile()) {
                                    putBack(drag)
                                } else {
                                    launch = Launch.Stack(placement.center(drag.pointer) - origin, album.count, album.recent.firstOrNull())
                                    visuals.trayPicked = HeldEnvelope.of(album)
                                    onFile(SheetTarget.Existing(album))
                                }
                            }
                        }
                    },
                    onPreview = { id -> visuals.preview = state.photos.firstOrNull { it.id == id } },
                    onPreviewEnd = { visuals.preview = null },
                ),
                modifier = Modifier.weight(1f),
            )
        }
        // 撤回胶囊和提示在同一高度：胶囊出现时提示让开
        AnimatedVisibility(
            visible = state.loaded && state.photos.isNotEmpty() && state.undo == null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 32.dp),
            enter = fadeIn(tween(MotionDurations.SHORT, easing = MotionEasings.Standard)),
            exit = fadeOut(tween(MotionDurations.SHORT, easing = MotionEasings.Standard)),
        ) {
            Text(stringResource(R.string.sheet_hint), style = DarkroomText.PvHint)
        }

        // 偷看：印样表压暗，袋子上方是相册名、张数和真实路径
        val peek = visuals.peek
        if (peek != null) {
            Box(Modifier.fillMaxSize().background(DarkroomColors.DialogScrim).graphicsLayer { alpha = visuals.peekFan.value.coerceIn(0f, 1f) })
            val rect = visuals.envelopeRects[peek.relativePath]
            if (rect != null) {
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .graphicsLayer {
                            translationY = rect.top - origin.y - 212.dp.toPx()
                            alpha = visuals.peekFan.value.coerceIn(0f, 1f)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(peek.name, style = DarkroomText.PeekName)
                    Text(
                        pluralStringResource(R.plurals.peek_meta, peek.count, peek.count, peek.relativePath.trimEnd('/')),
                        style = DarkroomText.GhostMono,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = trayVisible && !summaryVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(MotionSprings.Settle.spec(androidx.compose.ui.unit.IntOffset.VisibilityThreshold)) { it },
            exit = slideOutVertically(MotionSprings.Settle.spec(androidx.compose.ui.unit.IntOffset.VisibilityThreshold)) { it },
        ) {
            FilingTray(
                albums = state.tray,
                moreCount = state.moreCount,
                rowState = trayRowState,
                visuals = visuals,
                systemFavoriteSupported = state.systemFavoriteSupported,
                systemFavorite = state.systemFavorite,
                onSystemFavoriteToggle = onSystemFavoriteToggle,
                onUnfavorite = onUnfavorite,
                onFile = ::fileFromFrames,
                onNew = { startNaming("", null) },
                onMore = { openSheet() },
                onPeek = { album ->
                    Haptics.longPress(view)
                    visuals.peek = album
                    scope.launch { visuals.peekFan.animateTo(1f, MotionSprings.Settle.spec()) }
                },
                onPeekEnd = {
                    if (visuals.peek != null) {
                        scope.launch {
                            visuals.peekFan.animateTo(0f, tween(MotionDurations.SHORT, easing = MotionEasings.Standard))
                            visuals.peek = null
                        }
                    }
                },
            )
        }

        // 袋口滑出的照片：偷看 5 张，拖着照片叠悬停 3 张
        val fanAlbum = peek ?: visuals.hovered?.let(::albumBefore)
        val fanRect = fanAlbum?.let { visuals.envelopeRects[it.relativePath] }
        if (fanAlbum != null && fanRect != null) {
            val peeking = peek != null
            EnvelopeFanLayer(
                photos = if (peeking) fanAlbum.recent else fanAlbum.recent.take(3),
                rect = fanRect,
                origin = origin,
                peek = peeking,
                progress = { if (peeking) visuals.peekFan.value else visuals.hoverFan.value },
            )
        }

        if (sheetOpen) {
            AlbumSheet(
                albums = state.albums,
                query = query,
                onQuery = { query = it },
                progress = { sheetAnim.value },
                visuals = visuals,
                listState = sheetListState,
                parked = visuals.drag?.takeIf { parked },
                now = now,
                onPick = { album ->
                    if (canFile()) {
                        visuals.sheetPicked = album.relativePath
                        val drag = visuals.drag
                        launch = if (parked && drag != null) {
                            Launch.Stack((visuals.parkAt ?: placement.center(drag.pointer)) - origin, album.count, album.recent.firstOrNull(), intoSheet = true, heldPrints = SheetPrints.of(album.recent))
                        } else {
                            Launch.Frames(circledRects(), album.count, album.recent.firstOrNull(), intoSheet = true, heldPrints = SheetPrints.of(album.recent))
                        }
                        onFile(SheetTarget.Existing(album))
                    }
                },
                // 新袋子页盖在面板上面，面板和停靠的照片叠留着：取消或授权被拒都回到面板（用户 2026-10-27 决定）
                onCreate = { name ->
                    startNaming(name, if (parked) visuals.parkAt else null)
                },
                onDismiss = ::dismissSheet,
            )
        }

        visuals.drag?.let { drag ->
            DragStackLayer(drag, origin, frameSize, placement, hidden = { parked })
            if (!parked) {
                val newLabel = stringResource(R.string.envelope_new)
                val moreLabel = pluralStringResource(R.plurals.tray_more, state.moreCount, state.moreCount)
                // 名签让开浮在指尖上方的照片叠：叠上沿再往上 TagStackGap
                val tagGap = with(density) {
                    TagPlacement.fingerGap(
                        lift = stackLift,
                        stackHalfHeight = TagPlacement.stackHalfHeight(
                            frameW = frameSize.first.toPx(),
                            frameH = frameSize.second.toPx(),
                            scale = FilingMotion.STACK_SCALE,
                            maxRotationDeg = FilingMotion.STACK_ROTATIONS.maxOf { kotlin.math.abs(it) },
                            badgeHalf = 10.dp.toPx(),
                        ),
                        gap = FilingMotion.TagStackGap.toPx(),
                    )
                }
                HoverTagLayer(visuals, drag, origin, screenW, tagGap) { key ->
                    when (key) {
                        NEW_ENVELOPE_KEY -> newLabel
                        MORE_KEY -> moreLabel
                        else -> albumBefore(key)?.name
                    }
                }
            }
        }

        visuals.flight?.let { run ->
            val stackPoint = run.stackAt ?: restingStackCenter(trayTop, screenW, stackLift)
            if (run.intoSheet) {
                visuals.rowHolderRects[run.relativePath]?.let { holder ->
                    // 落点 = 行里最上面那张相片（打开时上浮的位置），缩到相片的图片宽
                    val land = PrintHolder.landCenter(holder, density) - origin
                    val landScale = with(density) { (PrintHolder.PrintWidth - PrintHolder.PrintBorder * 2).toPx() / frameSize.first.toPx() }
                    val sink = with(density) { PrintHolder.OpenLift.toPx() }
                    FlightLayer(run, origin, frameSize, holder, stackPoint, land = land, landScale = landScale, landSink = sink)
                }
            } else {
                val mouth = visuals.envelopeRects[run.relativePath] ?: visuals.envelopeRects[NEW_ENVELOPE_KEY]
                if (mouth != null) FlightLayer(run, origin, frameSize, mouth, stackPoint)
            }
        }

        visuals.preview?.let { photo ->
            Box(Modifier.fillMaxSize().background(DarkroomColors.Room.copy(alpha = 0.94f)), contentAlignment = Alignment.Center) {
                AsyncImage(photo.uri, photo.displayName, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(24.dp))
            }
        }

        val summary = state.summary
        if (summaryVisible && summary != null) {
            SheetSummaryPage(
                summary = summary,
                date = state.date,
                stripWidth = stripWidth,
                cols = cols,
                onNext = onNextDay,
                onBack = onBack,
            )
        }

        SheetToast(state.message, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = EnvelopeGeometry.TrayHeight + 16.dp))

        val undoBottom = when {
            summaryVisible -> 168.dp
            trayVisible -> EnvelopeGeometry.TrayHeight + 16.dp
            else -> 32.dp
        }
        val undo = state.undo
        UndoBar(
            content = undo?.let {
                UndoCapsuleContent(it.seq, it.thumb.id, it.thumb.uri, it.favorite, it.title.asString(), subtitle = null, keepColor = !it.favorite)
            },
            visible = visuals.drag == null && visuals.flight?.released != false && !sheetOpen,
            onUndo = onUndo,
            onExpired = onUndoExpired,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = undoBottom),
        )

        if (namingOpen) {
            NewAlbumSheet(
                photos = state.photos.filter { it.id in state.circled },
                albums = state.albums,
                initialName = namingName,
                onCancel = {
                    namingOpen = false
                    naming = null
                },
                onConfirm = { name ->
                    namingOpen = false
                    if (!canFile()) {
                        naming = null
                        return@NewAlbumSheet
                    }
                    launch = naming?.let { Launch.Stack(it - origin, 0, null) } ?: run {
                        Launch.Frames(circledRects(), 0, null)
                    }
                    naming = null
                    onFile(SheetTarget.New(name))
                },
            )
        }
    }

    BackHandler(namingOpen) {
        namingOpen = false
        naming = null
    }
    BackHandler(sheetOpen && !namingOpen) { dismissSheet() }

    // 贴边自动滚动：拖动期间每帧推进；只让托盘这一行自己滚动布局，滚动中不判定悬停
    val dragging = visuals.drag
    LaunchedEffect(dragging) {
        val drag = dragging ?: return@LaunchedEffect
        val scroller = EdgeScroller()
        val zone = with(density) { FilingMotion.EdgeZone.toPx() }
        val band = with(density) { FilingMotion.EdgeBand.toPx() }
        var last = 0L
        try {
            while (true) {
                val frame = withFrameNanos { it }
                val dt = if (last == 0L) 0f else ((frame - last) / 1_000_000f).coerceAtMost(48f)
                last = frame
                val p = drag.pointer
                val depth = when {
                    drag.returning || parked -> 0f
                    else -> visuals.trayRow?.takeIf { p.y in (it.top - band)..(it.bottom + band) }?.let { EdgeScroll.depth(p.x, it.left, it.right, zone) } ?: 0f
                }
                val dDp = scroller.step(depth, dt)
                var scrolling = false
                if (dDp != 0f) {
                    val consumed = trayRowState.dispatchRawDelta(dDp * density.density)
                    if (kotlin.math.abs(consumed) < 0.01f) scroller.hitEnd() else scrolling = true
                }
                if (scrolling != visuals.autoScrolling) {
                    visuals.autoScrolling = scrolling
                    if (scrolling) visuals.clearHover(this) else if (!drag.returning && !parked) visuals.updateHover(p, this, slop)
                }
            }
        } finally {
            visuals.autoScrolling = false
        }
    }

    // 装袋成功 → 飞入；授权被拒 / 一张没放进去 → 照片叠收起
    LaunchedEffect(flights) {
        flights.collect { e ->
            when (e) {
                is ContactSheetEffect.Flight -> {
                    val l = launch
                    launch = null
                    val intoSheet = l?.intoSheet == true && sheetOpen
                    val run = FlightRun(
                        seq = e.seq,
                        photos = e.photos,
                        total = e.photos.size,
                        relativePath = e.relativePath,
                        starts = e.photos.map { p -> (l as? Launch.Frames)?.rects?.get(p.id) },
                        stackAt = (l as? Launch.Stack)?.at,
                        heldCount = l?.heldCount ?: 0,
                        heldCover = l?.heldCover,
                        intoSheet = intoSheet,
                        heldPrints = l?.heldPrints.orEmpty(),
                    )
                    val closeFirst = sheetOpen && !intoSheet
                    visuals.drag = null
                    parked = false
                    scope.launch {
                        // 从面板里新建的相册飞进托盘：托盘这一行先在面板下面回到开头（新相册排在第一格），面板再降下露出托盘
                        if (closeFirst) {
                            trayRowState.scrollToItem(0)
                            closeSheet()
                        }
                        visuals.flight = run
                        // 面板行 / 托盘袋子停在点下那一刻的张数和相片交给飞入接着管，中间不空一帧
                        visuals.sheetPicked = null
                        visuals.trayPicked = null
                        run.play(onClosed = { Haptics.thresholdActivate(view) })
                        // 面板里装完：翻开后 300ms 面板降下，然后托盘重新排序
                        if (run.intoSheet && sheetOpen) {
                            delay(FilingMotion.SHEET_CLOSE_DELAY.toLong())
                            closeSheet()
                        }
                        if (visuals.flight === run) visuals.flight = null
                        val wasInTray = latest.tray.take(TrayRules.LIMIT).any { it.relativePath == e.relativePath }
                        onFlightDone(e.relativePath)
                        if (!wasInTray) scrollTrayHome(trayRowState, density)
                    }
                }
                ContactSheetEffect.Bounce -> {
                    launch = null
                    visuals.trayPicked = null
                    if (sheetOpen) {
                        // 面板里被拒绝（可能点错了）：面板留着，照片叠仍停靠在标题左边、圈选保留，可以再选或关掉（用户 2026-10-27 决定）
                        visuals.sheetPicked = null
                    } else {
                        visuals.drag?.let(::putBack)
                    }
                }
                else -> error("页面只处理飞入和收起：$e")
            }
        }
    }
}

/** 托盘这一行滚开了：settle 滚回开头，让人看见刚装的相册落到了第一格。 */
private suspend fun scrollTrayHome(state: LazyListState, density: androidx.compose.ui.unit.Density) {
    if (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0) return
    val step = with(density) { (EnvelopeGeometry.Width + EnvelopeGeometry.Gap).toPx() }
    val distance = state.firstVisibleItemIndex * step + state.firstVisibleItemScrollOffset
    state.animateScrollBy(-distance, MotionSprings.Settle.spec())
    state.scrollToItem(0)
}

@Composable
private fun SheetHeader(state: ContactSheetUiState, onBack: () -> Unit, onCircleAll: () -> Unit) {
    val locale = currentLocale()
    val label = state.date?.let { DayDisplay.dayLabel(it, locale) } ?: DayDisplay.unknownDayLabel(stringResource(R.string.date_unknown))
    val allCircled = state.photos.isNotEmpty() && state.circled.size == state.photos.size
    Row(
        Modifier
            .fillMaxWidth()
            .background(DarkroomColors.Room)
            .blockTouchesBelow()
            .statusBarsPadding()
            .height(Dimens.HeaderHeight + 8.dp)
            .padding(start = Dimens.ScreenSide, end = Dimens.ScreenSide, bottom = Dimens.HeaderBottomPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        GlassIconButton(onClick = onBack, modifier = Modifier.padding(bottom = 4.dp)) {
            Icon(DarkroomIcons.Back, stringResource(R.string.cd_back), tint = DarkroomColors.Paper, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label.dayNumeral, style = DarkroomText.CountBig, modifier = Modifier.alignByBaseline())
                Text(label.monthLabel, style = DarkroomText.CountUnit, modifier = Modifier.alignByBaseline())
            }
            Text(
                if (state.circled.isEmpty()) pluralStringResource(R.plurals.sheet_pending, state.photos.size, state.photos.size)
                else pluralStringResource(R.plurals.sheet_circled, state.circled.size, state.circled.size),
                style = DarkroomText.CountSub,
            )
        }
        if (state.photos.isNotEmpty()) {
            TrayTextButton(
                stringResource(if (allCircled) R.string.sheet_clear else R.string.sheet_circle_all),
                onClick = onCircleAll,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
}

/** 一张都没放进去 / 撤回失败的提示：托盘上方的小胶囊，2.5 秒后收起。 */
@Composable
private fun SheetToast(message: SheetMessage?, modifier: Modifier) {
    var shown by remember { mutableStateOf<SheetMessage?>(null) }
    LaunchedEffect(message?.seq) {
        if (message == null) return@LaunchedEffect
        shown = message
        delay(TOAST_MS)
        if (shown?.seq == message.seq) shown = null
    }
    AnimatedVisibility(
        visible = shown != null,
        modifier = modifier,
        enter = fadeIn(tween(MotionDurations.SHORT, easing = MotionEasings.Standard)),
        exit = fadeOut(tween(MotionDurations.SHORT, easing = MotionEasings.Standard)),
    ) {
        val m = shown ?: message ?: return@AnimatedVisibility
        DropChip(m.text.asString())
    }
}

private const val TOAST_MS = 2500L

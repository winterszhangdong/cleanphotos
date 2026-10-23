package com.winters.cleanphotos.feature.allphotos

import com.winters.cleanphotos.core.text.asString
import com.winters.cleanphotos.core.locale.currentLocale
import com.winters.cleanphotos.R
import com.winters.cleanphotos.domain.model.AppLanguage
import com.winters.cleanphotos.core.locale.labelRes
import com.winters.cleanphotos.core.locale.LanguageSheet
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.winters.cleanphotos.core.design.skeletonFill
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import com.winters.cleanphotos.core.design.MotionEasings
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.rememberLazyListState
import com.winters.cleanphotos.core.design.rememberPhotoGridState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import androidx.activity.compose.BackHandler
import com.winters.cleanphotos.core.design.MotionDurations
import com.winters.cleanphotos.core.navigation.AppDockEffect
import com.winters.cleanphotos.core.navigation.DockMode
import com.winters.cleanphotos.core.navigation.DockScrim
import com.winters.cleanphotos.core.design.ClotheslineArt
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomIcons
import com.winters.cleanphotos.core.design.DarkroomMenu
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.Haptics
import androidx.compose.ui.platform.LocalView
import com.winters.cleanphotos.core.design.EmptyStateArea
import com.winters.cleanphotos.core.design.EmptyTitleAndBody
import com.winters.cleanphotos.core.design.GhostButton
import com.winters.cleanphotos.core.design.GlassBackdropHost
import com.winters.cleanphotos.core.design.HeaderCount
import com.winters.cleanphotos.core.design.ListFade
import com.winters.cleanphotos.core.design.LocalAnimationsEnabled
import com.winters.cleanphotos.core.design.MenuOption
import com.winters.cleanphotos.core.design.MenuScrim
import com.winters.cleanphotos.core.design.MenuSection
import com.winters.cleanphotos.core.design.MonoWithBold
import com.winters.cleanphotos.core.design.ScopeChipItem
import com.winters.cleanphotos.core.design.ScopeChipRow
import com.winters.cleanphotos.core.design.ScreenHeader
import com.winters.cleanphotos.core.design.SkeletonBlock
import com.winters.cleanphotos.core.design.SystemBarIcons
import com.winters.cleanphotos.core.design.TileGrid
import com.winters.cleanphotos.core.design.ToolIconButton
import com.winters.cleanphotos.core.design.dockListBottomPadding
import com.winters.cleanphotos.core.design.glassBackdropSource
import com.winters.cleanphotos.core.design.plainClickable
import com.winters.cleanphotos.core.design.rememberScrollSettled
import com.winters.cleanphotos.core.design.gridScrollTapGuard
import com.winters.cleanphotos.core.design.whenSettled
import com.winters.cleanphotos.core.design.skeletonColor
import com.winters.cleanphotos.core.util.FileSizeFormatter
import com.winters.cleanphotos.core.util.NumberFormatter
import com.winters.cleanphotos.domain.model.PhotoSortOrder
import com.winters.cleanphotos.feature.similar.SimilarList
import com.winters.cleanphotos.feature.similar.SimilarUiState
import com.winters.cleanphotos.feature.similar.SimilarUndoBar
import kotlinx.coroutines.delay

/**
 * 照片页。
 * 顶栏和范围按钮叠在列表上，往下翻时一起收起；状态栏留一条暗房底色。
 */
@Composable
fun AllPhotosScreen(
    uiState: AllPhotosUiState,
    permanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
    onPhotoClick: (Long) -> Unit,
    onTrashClick: () -> Unit,
    onSortOrderChanged: (PhotoSortOrder) -> Unit,
    onScopeChanged: (LibraryScope) -> Unit,
    onShowUnseenChanged: (Boolean) -> Unit,
    similarState: SimilarUiState,
    onSimilarOpen: (String, Long) -> Unit,
    onSimilarCommit: (String) -> Unit,
    onSimilarUndo: () -> Unit,
    onSimilarUndoExpired: (Long) -> Unit,
    modifier: Modifier = Modifier,
    selection: PhotoSelectionState = PhotoSelectionState(),
    selectionActions: PhotoSelectionActions = PhotoSelectionActions.None,
    currentLanguage: AppLanguage = AppLanguage.System,
    onLanguageClick: () -> Unit = {},
    showLanguageSheet: Boolean = false,
    onLanguageSelected: (AppLanguage) -> Unit = {},
    onDismissLanguageSheet: () -> Unit = {},
) {
    SystemBarIcons(lightBackground = false)

    if (!uiState.hasPermission) {
        AppDockEffect(DockMode.Hidden)
        PhotoPermissionScreen(permanentlyDenied = permanentlyDenied, onRequest = onRequestPermission, modifier = modifier)
        return
    }

    var menuOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = menuOpen) { menuOpen = false }
    BackHandler(enabled = showLanguageSheet) { onDismissLanguageSheet() }
    val selecting = selection.isSelecting
    BackHandler(enabled = selecting && !menuOpen) { selectionActions.onExit() }
    LaunchedEffect(selecting) { if (selecting) menuOpen = false }
    val loading = uiState.isLoading && uiState.sections.isEmpty() && uiState.scope != LibraryScope.Similar
    val scopeEmpty = !loading && uiState.errorMessage == null && uiState.scope.isNarrowRange && uiState.scopeCount == 0 && uiState.totalCount > 0
    val scopeSeen = !loading && uiState.errorMessage == null && uiState.scope.isNarrowRange &&
        uiState.unseenOnly && uiState.visibleCount == 0 && uiState.scopeCount > 0
    val allSeen = !loading && uiState.scope == LibraryScope.All && uiState.unseenOnly && uiState.visibleCount == 0 && uiState.totalCount > 0
    val showingSimilar = uiState.scope == LibraryScope.Similar
    val sortToast = remember { SortToastState() }
    val locale = currentLocale()
    val sortLabel = stringResource(if (uiState.sortOrder == PhotoSortOrder.OldestFirst) R.string.sort_oldest else R.string.sort_newest)
    val toastedSort = remember { arrayOf(uiState.sortOrder) }
    LaunchedEffect(uiState.sortOrder) {
        if (toastedSort[0] == uiState.sortOrder) return@LaunchedEffect
        toastedSort[0] = uiState.sortOrder
        sortToast.show(sortLabel)
    }

    val gridState = rememberPhotoGridState()
    val similarListState = rememberLazyListState()
    val collapse = remember { PhotoChromeCollapse() }
    val scopeHolder = rememberUpdatedState(uiState.scope)
    val density = LocalDensity.current
    val collapseDistance = rememberUpdatedState(
        with(density) { (Dimens.HeaderHeight + Dimens.ScopeRowHeight).toPx() },
    )
    val animateSettle = rememberUpdatedState(LocalAnimationsEnabled.current)
    val settleScope = rememberCoroutineScope()
    fun listAtTop(list: LazyListState?, grid: LazyGridState): Boolean {
        return if (scopeHolder.value == LibraryScope.Similar) {
            list!!.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0
        } else {
            grid.firstVisibleItemIndex == 0 && grid.firstVisibleItemScrollOffset == 0
        }
    }
    val chromeScroll = remember(collapse, similarListState, gridState) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (listAtTop(similarListState, gridState)) collapse.snapExpanded()
                else collapse.follow(consumed.y, collapseDistance.value)
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                collapse.settle(
                    settleScope,
                    collapseDistance.value,
                    atTop = listAtTop(similarListState, gridState),
                    animate = animateSettle.value,
                )
                return Velocity.Zero
            }
        }
    }
    // 从清理页返回会重新进入组合，滚动位置刚被恢复。只在范围真的变了才回顶部并展开顶栏。
    var handledScope by rememberSaveable { mutableStateOf(uiState.scope.name) }
    LaunchedEffect(uiState.scope) {
        if (handledScope == uiState.scope.name) return@LaunchedEffect
        handledScope = uiState.scope.name
        gridState.scrollToItem(0)
        similarListState.scrollToItem(0)
        collapse.snapExpanded()
    }
    LaunchedEffect(menuOpen, selecting, showLanguageSheet) {
        if (menuOpen || selecting || showLanguageSheet) collapse.snapExpanded()
    }

    val chromeTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + Dimens.HeaderHeight + Dimens.ScopeRowHeight
    val showList = !loading && uiState.errorMessage == null && !allSeen && !scopeEmpty && !scopeSeen &&
        (showingSimilar || uiState.sections.isNotEmpty())

    GlassBackdropHost {
        AppDockEffect(
            mode = if (selecting || showLanguageSheet) DockMode.Hidden else DockMode.Visible,
            scrim = if (menuOpen) {
                DockScrim(DarkroomColors.MenuScrim, MotionDurations.MENU, MotionDurations.SHORT) { menuOpen = false }
            } else null,
        )
        Box(modifier.fillMaxSize().background(DarkroomColors.Room).nestedScroll(chromeScroll)) {
            when {
                loading -> LoadingSkeleton(Modifier.padding(top = chromeTop))
                uiState.errorMessage != null && uiState.sections.isEmpty() -> {
                    Box(Modifier.fillMaxSize().padding(top = chromeTop)) {
                        MessageState(stringResource(R.string.photos_error_title), uiState.errorMessage.asString())
                    }
                }
                showingSimilar -> SimilarList(
                    uiState = similarState,
                    listState = similarListState,
                    contentPadding = PaddingValues(top = chromeTop + Dimens.FreeTop, bottom = dockListBottomPadding()),
                    onOpen = onSimilarOpen,
                    onCommit = onSimilarCommit,
                )
                allSeen || scopeEmpty || scopeSeen -> Unit
                uiState.sections.isEmpty() -> {
                    Box(Modifier.fillMaxSize().padding(top = chromeTop)) {
                        MessageState(stringResource(R.string.photos_empty_title), stringResource(R.string.photos_empty_body))
                    }
                }
                else -> PhotoList(uiState, onPhotoClick, selection, selectionActions, gridState, chromeTop)
            }
            if (allSeen) {
                AllSeenContent(uiState = uiState, onTrashClick = onTrashClick, onShowSeen = { onShowUnseenChanged(false) })
            }
            if (scopeEmpty) {
                SmartFilterEmpty(
                    title = stringResource(uiState.scope.emptyTitleRes()),
                    onShowAll = { onScopeChanged(LibraryScope.All) },
                )
            }
            if (scopeSeen) {
                ScopeSeenEmpty(onShowSeen = { onShowUnseenChanged(false) })
            }
            if (showList && !(showingSimilar && similarState.groups.isEmpty())) ListFade()
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(Dimens.NavHeight + Dimens.NavBottom)
                    .background(DarkroomColors.Room)
                    .glassBackdropSource(),
            )

            MenuScrim(visible = menuOpen, onDismiss = { menuOpen = false })

            // 拨回来时叠在照片上，要有暗房底色
            Column(
                Modifier
                    .graphicsLayer { translationY = collapse.offsetPx }
                    .background(DarkroomColors.Room),
            ) {
                ScreenHeader(
                    leftAlpha = if (menuOpen) 0.5f else 1f,
                    count = {
                        when {
                            selecting -> HeaderCount(
                                selection.count,
                                unit = pluralStringResource(R.plurals.count_unit, selection.count),
                                sub = stringResource(R.string.header_sub_selected),
                            )
                            loading -> {
                                SkeletonBlock(96.dp, 24.dp, modifier = Modifier.align(Alignment.CenterVertically))
                                Text(
                                    stringResource(R.string.photos_loading),
                                    style = DarkroomText.CountSub,
                                    modifier = Modifier.align(Alignment.CenterVertically).padding(start = 12.dp - Dimens.CountGap),
                                )
                            }
                            showingSimilar -> HeaderCount(
                                similarState.groups.size,
                                unit = pluralStringResource(R.plurals.unit_group, similarState.groups.size),
                                sub = pluralStringResource(
                                    R.plurals.header_sub_can_move,
                                    similarState.removableCount,
                                    similarState.removableCount,
                                ),
                            )
                            uiState.scope.showsByteTotal -> HeaderCount(
                                uiState.visibleCount,
                                unit = pluralStringResource(R.plurals.count_unit, uiState.visibleCount),
                                sub = stringResource(R.string.header_sub_bytes, FileSizeFormatter.format(uiState.visibleBytes, locale)),
                            )
                            else -> HeaderCount(
                                uiState.visibleCount,
                                unit = pluralStringResource(R.plurals.count_unit, uiState.visibleCount),
                            )
                        }
                    },
                    actions = {
                        if (selecting) {
                            ToolIconButton(onClick = selectionActions.onExit) { tint ->
                                Icon(DarkroomIcons.Close, contentDescription = stringResource(R.string.cd_exit_selection), tint = tint, modifier = Modifier.size(Dimens.ToolIcon))
                            }
                        } else {
                            ToolIconButton(
                                onClick = { menuOpen = !menuOpen },
                                active = menuOpen,
                                showPip = uiState.unseenOnly,
                            ) { tint ->
                                Icon(DarkroomIcons.More, contentDescription = stringResource(R.string.cd_more), tint = tint, modifier = Modifier.size(Dimens.ToolIcon))
                            }
                        }
                    },
                )
                ScopeChipRow(scopeChips(uiState, onScopeChanged))
            }
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(DarkroomColors.Room),
            )

            SelectionActionBar(
                visible = selecting,
                allFavorite = selection.allSelectedFavorite,
                enabled = !selection.isProcessing,
                onFavorite = selectionActions.onFavorite,
                onMoveToTrash = selectionActions.onMoveToTrash,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            if (showingSimilar) {
                SimilarUndoBar(
                    undo = similarState.undo,
                    visible = similarState.undo != null && !selecting,
                    onUndo = onSimilarUndo,
                    onExpired = onSimilarUndoExpired,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            } else {
                BatchUndoBar(
                    undo = selection.undo,
                    visible = selection.undo != null && !selecting,
                    onUndo = selectionActions.onUndo,
                    onExpired = selectionActions.onUndoExpired,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            SortToast(sortToast)

            DarkroomMenu(
                visible = menuOpen,
                // 顶栏只剩 56dp：菜单紧贴 ⋯ 下方，盖住范围行而不是悬在它下面
                top = Dimens.HeaderHeight,
                sections = listOf(
                    MenuSection(
                        header = stringResource(R.string.menu_sort_header),
                        options = listOf(
                            MenuOption(stringResource(R.string.sort_newest), stringResource(R.string.sort_default), uiState.sortOrder == PhotoSortOrder.NewestFirst) {
                                onSortOrderChanged(PhotoSortOrder.NewestFirst)
                                menuOpen = false
                            },
                            MenuOption(stringResource(R.string.sort_oldest), null, uiState.sortOrder == PhotoSortOrder.OldestFirst) {
                                onSortOrderChanged(PhotoSortOrder.OldestFirst)
                                menuOpen = false
                            },
                        ),
                    ),
                    MenuSection(
                        options = listOf(
                            MenuOption(
                                label = stringResource(R.string.menu_unseen_only),
                                trailing = null,
                                selected = false,
                                onClick = {
                                    onShowUnseenChanged(!uiState.unseenOnly)
                                    menuOpen = false
                                },
                                toggle = uiState.unseenOnly,
                            ),
                        ),
                    ),
                    MenuSection(
                        options = listOf(
                            MenuOption(stringResource(R.string.menu_language), stringResource(currentLanguage.labelRes()), false) {
                                menuOpen = false
                                onLanguageClick()
                            },
                        ),
                    ),
                ),
            )
            LanguageSheet(
                visible = showLanguageSheet,
                current = currentLanguage,
                onSelect = onLanguageSelected,
                onDismiss = onDismissLanguageSheet,
            )
        }
    }
}

@Composable
private fun scopeChips(uiState: AllPhotosUiState, onScopeChanged: (LibraryScope) -> Unit): List<ScopeChipItem> {
    fun chip(label: String, scope: LibraryScope) = ScopeChipItem(label, uiState.scope == scope) { onScopeChanged(scope) }
    return buildList {
        add(chip(stringResource(R.string.scope_all), LibraryScope.All))
        add(chip(stringResource(R.string.scope_similar), LibraryScope.Similar))
        add(chip(stringResource(R.string.filter_large_files), LibraryScope.LargeFiles))
        add(chip(stringResource(R.string.filter_screenshots), LibraryScope.Screenshots))
        add(chip(stringResource(R.string.scope_chat), LibraryScope.ChatApps))
        if (uiState.showOnThisDay) add(chip(stringResource(R.string.filter_on_this_day), LibraryScope.OnThisDay))
    }
}

@Composable
private fun PhotoList(
    uiState: AllPhotosUiState,
    onPhotoClick: (Long) -> Unit,
    selection: PhotoSelectionState,
    actions: PhotoSelectionActions,
    gridState: LazyGridState,
    topPadding: Dp,
) {
    val view = LocalView.current
    val selecting = selection.isSelecting
    val develop = remember { tileDevelopFor(uiState.isInitialLoad) }
    val firstScreen = rememberGridFirstScreen(gridState, sessionKey = "photos")
    val reorder = rememberPhotoReorder(gridState, uiState.sortOrder, uiState.scope to uiState.unseenOnly)
    val tapsSettled = rememberScrollSettled(gridState)
    val openPhoto = tapsSettled.whenSettled(onPhotoClick)
    LazyVerticalGrid(
        columns = GridCells.Fixed(PHOTO_GRID_COLUMNS),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .gridScrollTapGuard()
            .photoGridGestures(
                state = gridState,
                onTap = tapsSettled.whenSettled { id -> if (selecting) actions.onToggle(id) else onPhotoClick(id) },
                onLongPress = { id ->
                    val started = tapsSettled() && actions.onLongPress(id)
                    if (started) Haptics.longPress(view)
                    started
                },
                onDrag = actions.onDrag,
            ),
        horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap),
        contentPadding = PaddingValues(top = topPadding, bottom = dockListBottomPadding()),
    ) {
        photoDaySections(
            sections = uiState.sections,
            right = { section ->
                if (section.totalCount > 0 && section.seenCount >= section.totalCount) {
                    MonoWithBold(null, stringResource(R.string.section_all_seen))
                } else {
                    MonoWithBold(section.seenCount.toString(), stringResource(R.string.section_seen_of, section.totalCount))
                }
            },
            develop = develop,
            firstScreen = firstScreen,
            reorderAnchor = reorder.anchor,
            reorderPlace = reorder.place,
            onPhotoClick = openPhoto,
            selection = GridSelection(selection.selectedIds, selecting, actions.onToggleDay, actions.onToggle, actions.onLongPress),
            showChevron = false,
        )
    }
}

/** 骨架天标题（24×20 + 64×12）与呼吸格，三组 8 / 6 / 4 格，错开 200ms。 */
@Composable
private fun LoadingSkeleton(modifier: Modifier = Modifier) {
    val groups = listOf(
        listOf(0, 200, 400, 600, 800, 1000, 200, 400),
        listOf(0, 200, 400, 600, 800, 1000),
        listOf(300, 500, 700, 900),
    )
    Column(modifier) {
        groups.forEach { delays ->
            Row(
                Modifier.fillMaxWidth().height(Dimens.DayHeight).padding(start = Dimens.ScreenSide, end = Dimens.ScreenSide, top = Dimens.DayTop, bottom = Dimens.DayBottom),
                horizontalArrangement = Arrangement.spacedBy(Dimens.DayGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBlock(24.dp, 20.dp)
                SkeletonBlock(64.dp, 12.dp)
            }
            TileGrid(delays, columns = 4) { _, delay, m ->
                Box(m.aspectRatio(1f).skeletonFill(skeletonColor(delay)))
            }
        }
    }
}

/** 截图 / 大文件 / 聊天 / 那年今天本身 0 张：标题 + 切回全部。 */
@Composable
private fun SmartFilterEmpty(title: String, onShowAll: () -> Unit) {
    EmptyStateArea {
        Text(title, style = DarkroomText.EmptyH4, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.show_all_photos),
            style = DarkroomText.TextButton,
            modifier = Modifier.padding(top = 24.dp).plainClickable(onClick = onShowAll),
        )
    }
}

/** 范围里有照片，但叠上「只看没看过」之后一张不剩。 */
@Composable
private fun ScopeSeenEmpty(onShowSeen: () -> Unit) {
    EmptyStateArea {
        Text(stringResource(R.string.scope_seen_title), style = DarkroomText.EmptyH4, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.show_seen),
            style = DarkroomText.TextButton,
            modifier = Modifier.padding(top = 24.dp).plainClickable(onClick = onShowSeen),
        )
    }
}

private fun LibraryScope.emptyTitleRes(): Int = when (this) {
    LibraryScope.Screenshots -> R.string.filter_empty_screenshots
    LibraryScope.LargeFiles -> R.string.filter_empty_large_files
    LibraryScope.ChatApps -> R.string.filter_empty_chat_apps
    LibraryScope.OnThisDay -> R.string.filter_empty_on_this_day
    LibraryScope.All, LibraryScope.Similar -> error("$this 没有范围空状态")
}

@Composable
private fun MessageState(title: String, body: String) {
    EmptyStateArea(Modifier.padding(top = 0.dp)) {
        EmptyTitleAndBody(title, body)
    }
}

/** 「全部看过了」：晾绳 + 文案 + 收藏 N / 垃圾桶 N 张 / 待释放 X GB。按钮关掉「只看没看过」。 */
@Composable
private fun AllSeenContent(uiState: AllPhotosUiState, onTrashClick: () -> Unit, onShowSeen: () -> Unit) {
    val stats = uiState.allSeenStats
    val locale = currentLocale()
    EmptyStateArea {
        val painters = uiState.hangPhotos.map { rememberAsyncImagePainter(it.uri) }
        ClotheslineArt(painters, Modifier.padding(bottom = 24.dp))
        EmptyTitleAndBody(
            stringResource(R.string.all_seen_title),
            pluralStringResource(R.plurals.all_seen_body, uiState.totalCount, NumberFormatter.formatCount(uiState.totalCount, locale)),
        )
        val (size, unit) = FileSizeFormatter.parts(stats.trashBytes, locale)
        Row(Modifier.padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            DoneStat(NumberFormatter.formatCount(stats.favoriteCount, locale), null, DarkroomColors.Amber, stringResource(R.string.stat_favorites))
            DoneStat(NumberFormatter.formatCount(stats.trashCount, locale), pluralStringResource(R.plurals.count_unit, stats.trashCount), DarkroomColors.FadeText, stringResource(R.string.stat_trash))
            DoneStat(size, unit, DarkroomColors.Paper, stringResource(R.string.stat_to_free))
        }
        if (stats.trashCount > 0) {
            GhostButton(stringResource(R.string.all_seen_view_trash), onClick = onTrashClick, mono = stringResource(R.string.all_seen_trash_mono, FileSizeFormatter.format(stats.trashBytes, locale)))
        }
        Text(
            stringResource(R.string.show_seen),
            style = DarkroomText.TextButton,
            modifier = Modifier.padding(top = 12.dp).plainClickable(onClick = onShowSeen),
        )
    }
}

@Composable
private fun DoneStat(value: String, unit: String?, color: androidx.compose.ui.graphics.Color, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            buildAnnotatedString {
                append(value)
                if (unit != null) {
                    withStyle(SpanStyle(fontSize = 12.sp, color = DarkroomColors.Dim)) { append(" $unit") }
                }
            },
            style = DarkroomText.DoneNum.copy(color = color),
        )
        Text(label, style = DarkroomText.DoneLabel, modifier = Modifier.paddingFromBaseline(top = 18.56.dp, bottom = 4.64.dp))
    }
}

/** 排序提示：减速淡入并上移 → 停留 → 淡出。只在绘制阶段读进度。 */
private class SortToastState {
    var text by mutableStateOf<String?>(null)
    val alpha = Animatable(0f)
    val rise = Animatable(1f)

    suspend fun show(message: String) = coroutineScope {
        text = message
        alpha.snapTo(0f)
        rise.snapTo(1f)
        launch { rise.animateTo(0f, tween(MotionDurations.SORT_TOAST_IN, easing = MotionEasings.Decelerate)) }
        alpha.animateTo(1f, tween(MotionDurations.SORT_TOAST_IN, easing = MotionEasings.Decelerate))
        delay(MotionDurations.SORT_TOAST_HOLD.toLong())
        alpha.animateTo(0f, tween(MotionDurations.SORT_TOAST_OUT, easing = LinearEasing))
        text = null
    }
}

@Composable
private fun SortToast(state: SortToastState) {
    val text = state.text ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val top = maxHeight * Dimens.TOAST_TOP_FRACTION
        val shape = RoundedCornerShape(Dimens.ToastHeight / 2)
        Text(
            text,
            style = DarkroomText.Toast,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = top)
                .graphicsLayer {
                    alpha = state.alpha.value
                    translationY = Dimens.ToastRise.toPx() * state.rise.value
                }
                .height(Dimens.ToastHeight)
                .clip(shape)
                .background(DarkroomColors.ToastFill)
                .border(Dimens.Hairline, DarkroomColors.ToastRim, shape)
                .padding(horizontal = Dimens.ToastPaddingH),
        )
    }
}

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
import com.winters.cleanphotos.core.design.MotionSprings
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import com.winters.cleanphotos.core.design.rememberPhotoGridState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
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
import com.winters.cleanphotos.core.design.Eyebrow
import com.winters.cleanphotos.core.design.GhostButton
import com.winters.cleanphotos.core.design.GlassBackdropHost
import com.winters.cleanphotos.core.design.HeaderCount
import com.winters.cleanphotos.core.design.ListFade
import com.winters.cleanphotos.core.design.MenuOption
import com.winters.cleanphotos.core.design.MenuScrim
import com.winters.cleanphotos.core.design.MonoWithBold
import com.winters.cleanphotos.core.design.ScreenHeader
import com.winters.cleanphotos.core.design.ScreenHeaderSpacer
import com.winters.cleanphotos.core.design.SkeletonBlock
import com.winters.cleanphotos.core.design.SystemBarIcons
import com.winters.cleanphotos.core.design.TileGrid
import com.winters.cleanphotos.core.design.ToolIconButton
import com.winters.cleanphotos.core.design.TrashReminderBar
import com.winters.cleanphotos.core.design.dockListBottomPadding
import com.winters.cleanphotos.core.design.glassBackdropSource
import com.winters.cleanphotos.core.design.plainClickable
import com.winters.cleanphotos.core.design.rememberScrollSettled
import com.winters.cleanphotos.core.design.gridScrollTapGuard
import com.winters.cleanphotos.core.design.whenSettled
import com.winters.cleanphotos.core.design.skeletonColor
import com.winters.cleanphotos.core.util.FileSizeFormatter
import com.winters.cleanphotos.core.util.NumberFormatter
import com.winters.cleanphotos.domain.model.PhotoFilterMode
import com.winters.cleanphotos.domain.model.PhotoSortOrder

private enum class OpenMenu { None, Sort, Filter, More }

/**
 * 照片页。
 * 页头固定在顶部、画在菜单遮罩之上；列表从页头下开始，玻璃导航模糊列表。
 */
@Composable
fun AllPhotosScreen(
    uiState: AllPhotosUiState,
    permanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
    onPhotoClick: (Long) -> Unit,
    onTrashClick: () -> Unit,
    onOpenSimilar: () -> Unit = {},
    onSortOrderChanged: (PhotoSortOrder) -> Unit,
    onFilterModeChanged: (PhotoFilterMode) -> Unit,
    modifier: Modifier = Modifier,
    selection: PhotoSelectionState = PhotoSelectionState(),
    selectionActions: PhotoSelectionActions = PhotoSelectionActions.None,
    currentLanguage: AppLanguage = AppLanguage.System,
    onLanguageClick: () -> Unit = {},
    showLanguageSheet: Boolean = false,
    onLanguageSelected: (AppLanguage) -> Unit = {},
    onDismissLanguageSheet: () -> Unit = {},
    onReviewTrashReminder: () -> Unit = {},
    onDismissTrashReminder: () -> Unit = {},
    onTrashReminderBlocked: (Boolean) -> Unit = {},
) {
    SystemBarIcons(lightBackground = false)

    if (!uiState.hasPermission) {
        AppDockEffect(DockMode.Hidden)
        PhotoPermissionScreen(permanentlyDenied = permanentlyDenied, onRequest = onRequestPermission, modifier = modifier)
        return
    }

    var openMenu by remember { mutableStateOf(OpenMenu.None) }
    val reminderBlocked = openMenu != OpenMenu.None || showLanguageSheet
    LaunchedEffect(reminderBlocked) { onTrashReminderBlocked(reminderBlocked) }
    // 系统返回先关菜单
    BackHandler(enabled = openMenu != OpenMenu.None) { openMenu = OpenMenu.None }
    BackHandler(enabled = showLanguageSheet) { onDismissLanguageSheet() }
    val selecting = selection.isSelecting
    // 多选中返回先退出多选（照片页是根页，没有预测性返回动画可抢）
    BackHandler(enabled = selecting && openMenu == OpenMenu.None) { selectionActions.onExit() }
    LaunchedEffect(selecting) { if (selecting) openMenu = OpenMenu.None }
    val loading = uiState.isLoading && uiState.sections.isEmpty()
    val allSeen = !loading && uiState.filterMode == PhotoFilterMode.UnseenOnly && uiState.visibleCount == 0 && uiState.totalCount > 0
    val smartFilterEmpty = !loading && uiState.errorMessage == null && uiState.filterMode.isSmartFilter && uiState.visibleCount == 0 && uiState.totalCount > 0
    val unseenOnly = uiState.filterMode == PhotoFilterMode.UnseenOnly
    val filterActive = uiState.filterMode != PhotoFilterMode.AllPhotos
    // 切换排序后图标转 180°（点击后 50ms 起，落定弹簧），并用一句提示说明当前顺序
    val sortRotation = remember { Animatable(sortAngle(uiState.sortOrder)) }
    val sortToast = remember { SortToastState() }
    val locale = currentLocale()
    val sortLabel = stringResource(if (uiState.sortOrder == PhotoSortOrder.OldestFirst) R.string.sort_oldest else R.string.sort_newest)
    LaunchedEffect(uiState.sortOrder) {
        val target = sortAngle(uiState.sortOrder)
        if (sortRotation.value == target) return@LaunchedEffect
        launch { sortToast.show(sortLabel) }
        delay(MotionDurations.SORT_ICON_DELAY.toLong())
        sortRotation.animateTo(target, MotionSprings.Settle.spec())
    }

    GlassBackdropHost {
        AppDockEffect(
            // 多选时底部导航让位给「收藏 / 移入垃圾桶」两项操作栏
            mode = if (selecting || showLanguageSheet) DockMode.Hidden else DockMode.Visible,
            scrim = if (openMenu != OpenMenu.None) {
                DockScrim(DarkroomColors.MenuScrim, MotionDurations.MENU, MotionDurations.SHORT) { openMenu = OpenMenu.None }
            } else null,
        )
        Box(modifier.fillMaxSize().background(DarkroomColors.Room)) {
            Column(Modifier.fillMaxSize()) {
                ScreenHeaderSpacer()
                Box(Modifier.weight(1f)) {
                    when {
                        loading -> LoadingSkeleton()
                        uiState.errorMessage != null && uiState.sections.isEmpty() -> MessageState(stringResource(R.string.photos_error_title), uiState.errorMessage.asString())
                        allSeen -> Unit
                        smartFilterEmpty -> Unit
                        uiState.sections.isEmpty() -> MessageState(stringResource(R.string.photos_empty_title), stringResource(R.string.photos_empty_body))
                        else -> PhotoList(uiState, onPhotoClick, selection, selectionActions)
                    }
                }
            }
            if (allSeen) {
                AllSeenContent(
                    uiState = uiState,
                    onTrashClick = onTrashClick,
                    onShowAll = { onFilterModeChanged(PhotoFilterMode.AllPhotos) },
                )
            }
            if (smartFilterEmpty) {
                SmartFilterEmpty(
                    title = stringResource(uiState.filterMode.emptyTitleRes()),
                    onShowAll = { onFilterModeChanged(PhotoFilterMode.AllPhotos) },
                )
            }
            if (!allSeen && !smartFilterEmpty) ListFade()
            // 导航玻璃只取样这一条实色带。Haze 每帧 record 整棵子树，包在网格上会让滚动重画所有可见格子。
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(Dimens.NavHeight + Dimens.NavBottom)
                    .background(DarkroomColors.Room)
                    .glassBackdropSource(),
            )

            MenuScrim(visible = openMenu != OpenMenu.None, onDismiss = { openMenu = OpenMenu.None })

            val menuOpen = openMenu != OpenMenu.None
            ScreenHeader(
                leftAlpha = if (menuOpen) 0.5f else 1f,
                eyebrow = { Eyebrow(stringResource(if (selecting) R.string.eyebrow_selected else if (unseenOnly) R.string.eyebrow_photos_unseen else R.string.tab_photos)) },
                count = {
                    if (selecting) {
                        HeaderCount(selection.count)
                    } else if (loading) {
                        SkeletonBlock(96.dp, 24.dp, modifier = Modifier.align(Alignment.CenterVertically))
                        Text(
                            stringResource(R.string.photos_loading),
                            style = DarkroomText.CountSub,
                            modifier = Modifier.align(Alignment.CenterVertically).padding(start = 12.dp - Dimens.CountGap),
                        )
                    } else if (filterActive) {
                        HeaderCount(uiState.visibleCount, sub = stringResource(R.string.header_sub_total, NumberFormatter.formatCount(uiState.totalCount, locale)))
                    } else {
                        HeaderCount(uiState.totalCount, sub = stringResource(R.string.header_sub_unseen, NumberFormatter.formatCount(uiState.unseenCount, locale)))
                    }
                },
                actions = {
                  if (selecting) {
                    // 只把排序、筛选换成 ✕，左侧和列表位置不动
                    ToolIconButton(onClick = selectionActions.onExit) { tint ->
                        Icon(DarkroomIcons.Close, contentDescription = stringResource(R.string.cd_exit_selection), tint = tint, modifier = Modifier.size(Dimens.ToolIcon))
                    }
                  } else {
                    val enabled = !loading
                    ToolIconButton(
                        onClick = { openMenu = if (openMenu == OpenMenu.Sort) OpenMenu.None else OpenMenu.Sort },
                        active = openMenu == OpenMenu.Sort,
                        enabled = enabled,
                        modifier = Modifier.alpha(
                            when {
                                loading -> 0.4f
                                openMenu == OpenMenu.Filter || openMenu == OpenMenu.More -> 0.5f
                                else -> 1f
                            },
                        ),
                    ) { tint ->
                        Icon(
                            DarkroomIcons.Sort,
                            contentDescription = stringResource(R.string.cd_sort),
                            tint = tint,
                            modifier = Modifier.size(Dimens.ToolIcon).graphicsLayer { rotationZ = sortRotation.value },
                        )
                    }
                    ToolIconButton(
                        onClick = { openMenu = if (openMenu == OpenMenu.Filter) OpenMenu.None else OpenMenu.Filter },
                        active = openMenu == OpenMenu.Filter,
                        showPip = filterActive,
                        enabled = enabled,
                        modifier = Modifier.alpha(
                            when {
                                loading -> 0.4f
                                openMenu == OpenMenu.Sort || openMenu == OpenMenu.More -> 0.5f
                                else -> 1f
                            },
                        ),
                    ) { tint -> Icon(DarkroomIcons.Filter, contentDescription = stringResource(R.string.cd_filter), tint = tint, modifier = Modifier.size(Dimens.ToolIcon)) }
                    // ⋯：目前只有「语言」一项（不单独做设置页）
                    ToolIconButton(
                        onClick = { openMenu = if (openMenu == OpenMenu.More) OpenMenu.None else OpenMenu.More },
                        active = openMenu == OpenMenu.More,
                        modifier = Modifier.alpha(if (openMenu == OpenMenu.Sort || openMenu == OpenMenu.Filter) 0.5f else 1f),
                    ) { tint -> Icon(DarkroomIcons.More, contentDescription = stringResource(R.string.cd_more), tint = tint, modifier = Modifier.size(Dimens.ToolIcon)) }
                  }
                },
            )

            SelectionActionBar(
                visible = selecting,
                allFavorite = selection.allSelectedFavorite,
                enabled = !selection.isProcessing,
                onFavorite = selectionActions.onFavorite,
                onMoveToTrash = selectionActions.onMoveToTrash,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            BatchUndoBar(
                undo = selection.undo,
                visible = !selecting,
                onUndo = selectionActions.onUndo,
                onExpired = selectionActions.onUndoExpired,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            TrashReminderBar(
                message = uiState.trashReminder?.asString(),
                visible = uiState.trashReminder != null,
                onReview = onReviewTrashReminder,
                onDismiss = onDismissTrashReminder,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(
                        start = Dimens.ScreenSide,
                        end = Dimens.ScreenSide,
                        bottom = Dimens.NavBottom + Dimens.NavHeight + Dimens.UndoAboveNav,
                    )
                    .fillMaxWidth(),
            )

            SortToast(sortToast)

            DarkroomMenu(
                visible = openMenu == OpenMenu.Sort,
                header = stringResource(R.string.menu_sort_header),
                options = listOf(
                    MenuOption(stringResource(R.string.sort_newest), stringResource(R.string.sort_default), uiState.sortOrder == PhotoSortOrder.NewestFirst) {
                        onSortOrderChanged(PhotoSortOrder.NewestFirst); openMenu = OpenMenu.None
                    },
                    MenuOption(stringResource(R.string.sort_oldest), null, uiState.sortOrder == PhotoSortOrder.OldestFirst) {
                        onSortOrderChanged(PhotoSortOrder.OldestFirst); openMenu = OpenMenu.None
                    },
                ),
            )
            DarkroomMenu(
                visible = openMenu == OpenMenu.Filter,
                header = stringResource(R.string.menu_filter_header),
                options = buildList {
                    add(MenuOption(stringResource(R.string.filter_all), NumberFormatter.formatCount(uiState.totalCount, locale), uiState.filterMode == PhotoFilterMode.AllPhotos) {
                        onFilterModeChanged(PhotoFilterMode.AllPhotos); openMenu = OpenMenu.None
                    })
                    add(MenuOption(stringResource(R.string.filter_unseen), NumberFormatter.formatCount(uiState.unseenCount, locale), unseenOnly) {
                        onFilterModeChanged(PhotoFilterMode.UnseenOnly); openMenu = OpenMenu.None
                    })
                    add(MenuOption(stringResource(R.string.filter_screenshots), NumberFormatter.formatCount(uiState.screenshotCount, locale), uiState.filterMode == PhotoFilterMode.Screenshots) {
                        onFilterModeChanged(PhotoFilterMode.Screenshots); openMenu = OpenMenu.None
                    })
                    add(MenuOption(stringResource(R.string.filter_large_files), NumberFormatter.formatCount(uiState.largeFileCount, locale), uiState.filterMode == PhotoFilterMode.LargeFiles) {
                        onFilterModeChanged(PhotoFilterMode.LargeFiles); openMenu = OpenMenu.None
                    })
                    add(MenuOption(stringResource(R.string.filter_chat_apps), NumberFormatter.formatCount(uiState.chatAppCount, locale), uiState.filterMode == PhotoFilterMode.ChatApps) {
                        onFilterModeChanged(PhotoFilterMode.ChatApps); openMenu = OpenMenu.None
                    })
                    if (uiState.showOnThisDayMenu) {
                        add(MenuOption(stringResource(R.string.filter_on_this_day), NumberFormatter.formatCount(uiState.onThisDayCount, locale), uiState.filterMode == PhotoFilterMode.OnThisDay) {
                            onFilterModeChanged(PhotoFilterMode.OnThisDay); openMenu = OpenMenu.None
                        })
                    }
                    // 不是筛选：不改 filterMode，关掉菜单后离开本页
                    add(MenuOption(stringResource(R.string.similar_photos), stringResource(R.string.similar_menu_go), false) {
                        openMenu = OpenMenu.None
                        onOpenSimilar()
                    })
                },
            )
            DarkroomMenu(
                visible = openMenu == OpenMenu.More,
                header = stringResource(R.string.menu_more_header),
                options = listOf(
                    MenuOption(stringResource(R.string.menu_language), stringResource(currentLanguage.labelRes()), false) {
                        openMenu = OpenMenu.None
                        onLanguageClick()
                    },
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
private fun PhotoList(
    uiState: AllPhotosUiState,
    onPhotoClick: (Long) -> Unit,
    selection: PhotoSelectionState,
    actions: PhotoSelectionActions,
) {
    val view = LocalView.current
    val selecting = selection.isSelecting
    val develop = remember { tileDevelopFor(uiState.isInitialLoad) }
    val gridState = rememberPhotoGridState()
    val firstScreen = rememberGridFirstScreen(gridState, sessionKey = "photos")
    val anchor = rememberReorderAnchor(gridState, uiState.sortOrder to uiState.filterMode)
    // 甩动中 / 停稳后约 280ms 内不响应点按与长按，避免惯性抬手误开清理页
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
        contentPadding = PaddingValues(bottom = dockListBottomPadding()),
    ) {
        photoDaySections(
            sections = uiState.sections,
            unseen = { it !in uiState.seenIds },
            right = { section ->
                if (section.unseenCount > 0) {
                    MonoWithBold(section.unseenCount.toString(), stringResource(R.string.section_unseen, section.totalCount))
                } else {
                    MonoWithBold(null, stringResource(R.string.section_done, section.totalCount))
                }
            },
            develop = develop,
            firstScreen = firstScreen,
            reorderAnchor = anchor,
            onPhotoClick = openPhoto,
            selection = GridSelection(selection.selectedIds, selecting, actions.onToggleDay, actions.onToggle, actions.onLongPress),
        )
    }
}

/** 骨架天标题（24×20 + 64×12）与呼吸格，三组 8 / 6 / 4 格，错开 200ms。 */
@Composable
private fun LoadingSkeleton() {
    val groups = listOf(
        listOf(0, 200, 400, 600, 800, 1000, 200, 400),
        listOf(0, 200, 400, 600, 800, 1000),
        listOf(300, 500, 700, 900),
    )
    Column {
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

/** 截图 / 大文件 / 聊天软件存图 / 那年今天筛到 0 张：标题 + 切回全部。不用「全部看过了」那一套。 */
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

private val PhotoFilterMode.isSmartFilter: Boolean
    get() = this == PhotoFilterMode.Screenshots ||
        this == PhotoFilterMode.LargeFiles ||
        this == PhotoFilterMode.ChatApps ||
        this == PhotoFilterMode.OnThisDay

private fun PhotoFilterMode.emptyTitleRes(): Int = when (this) {
    PhotoFilterMode.Screenshots -> R.string.filter_empty_screenshots
    PhotoFilterMode.LargeFiles -> R.string.filter_empty_large_files
    PhotoFilterMode.ChatApps -> R.string.filter_empty_chat_apps
    PhotoFilterMode.OnThisDay -> R.string.filter_empty_on_this_day
    PhotoFilterMode.AllPhotos, PhotoFilterMode.UnseenOnly -> error("$this 没有筛选空状态")
}

@Composable
private fun MessageState(title: String, body: String) {
    EmptyStateArea(Modifier.padding(top = 0.dp)) {
        EmptyTitleAndBody(title, body)
    }
}

/** 「全部看过了」：晾绳 + 文案 + 收藏 N / 垃圾桶 N 张 / 待释放 X GB（决定 11）。 */
@Composable
private fun AllSeenContent(uiState: AllPhotosUiState, onTrashClick: () -> Unit, onShowAll: () -> Unit) {
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
            stringResource(R.string.show_all_photos),
            style = DarkroomText.TextButton,
            modifier = Modifier.padding(top = 12.dp).plainClickable(onClick = onShowAll),
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
        // 11px 的 span 落在 16px × 1.45 的父行盒里：行高 23.2dp，基线在 18.56dp（16 × 1.16）
        Text(label, style = DarkroomText.DoneLabel, modifier = Modifier.paddingFromBaseline(top = 18.56.dp, bottom = 4.64.dp))
    }
}

private fun sortAngle(order: PhotoSortOrder): Float = if (order == PhotoSortOrder.OldestFirst) 180f else 0f

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

package com.winters.cleanphotos.feature.allphotos

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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import com.winters.cleanphotos.core.design.dockListBottomPadding
import com.winters.cleanphotos.core.design.glassBackdropSource
import com.winters.cleanphotos.core.design.plainClickable
import com.winters.cleanphotos.core.design.skeletonColor
import com.winters.cleanphotos.core.util.FileSizeFormatter
import com.winters.cleanphotos.core.util.NumberFormatter
import com.winters.cleanphotos.domain.model.PhotoFilterMode
import com.winters.cleanphotos.domain.model.PhotoSortOrder

private enum class OpenMenu { None, Sort, Filter }

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
    onSortOrderChanged: (PhotoSortOrder) -> Unit,
    onFilterModeChanged: (PhotoFilterMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    SystemBarIcons(lightBackground = false)

    if (!uiState.hasPermission) {
        AppDockEffect(DockMode.Hidden)
        PhotoPermissionScreen(permanentlyDenied = permanentlyDenied, onRequest = onRequestPermission, modifier = modifier)
        return
    }

    var openMenu by remember { mutableStateOf(OpenMenu.None) }
    // 系统返回先关菜单
    BackHandler(enabled = openMenu != OpenMenu.None) { openMenu = OpenMenu.None }
    val loading = uiState.isLoading && uiState.sections.isEmpty()
    val allSeen = !loading && uiState.filterMode == PhotoFilterMode.UnseenOnly && uiState.visibleCount == 0 && uiState.totalCount > 0
    val unseenOnly = uiState.filterMode == PhotoFilterMode.UnseenOnly
    // 切换排序后图标转 180°（点击后 50ms 起，落定弹簧），并用一句提示说明当前顺序
    val sortRotation = remember { Animatable(sortAngle(uiState.sortOrder)) }
    val sortToast = remember { SortToastState() }
    LaunchedEffect(uiState.sortOrder) {
        val target = sortAngle(uiState.sortOrder)
        if (sortRotation.value == target) return@LaunchedEffect
        launch { sortToast.show(if (uiState.sortOrder == PhotoSortOrder.OldestFirst) "最早优先" else "最新优先") }
        delay(MotionDurations.SORT_ICON_DELAY.toLong())
        sortRotation.animateTo(target, MotionSprings.Settle.spec())
    }

    GlassBackdropHost {
        AppDockEffect(
            mode = DockMode.Visible,
            scrim = if (openMenu != OpenMenu.None) {
                DockScrim(DarkroomColors.MenuScrim, MotionDurations.MENU, MotionDurations.SHORT) { openMenu = OpenMenu.None }
            } else null,
        )
        Box(modifier.fillMaxSize().background(DarkroomColors.Room)) {
            Column(Modifier.fillMaxSize().glassBackdropSource()) {
                ScreenHeaderSpacer()
                Box(Modifier.weight(1f)) {
                    when {
                        loading -> LoadingSkeleton()
                        uiState.errorMessage != null && uiState.sections.isEmpty() -> MessageState("没能读取相册", uiState.errorMessage)
                        allSeen -> Unit
                        uiState.sections.isEmpty() -> MessageState("相册里还没有照片", "拍的照片会出现在这里。")
                        else -> PhotoList(uiState, onPhotoClick)
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
            if (!allSeen) ListFade()

            MenuScrim(visible = openMenu != OpenMenu.None, onDismiss = { openMenu = OpenMenu.None })

            val menuOpen = openMenu != OpenMenu.None
            ScreenHeader(
                leftAlpha = if (menuOpen) 0.5f else 1f,
                eyebrow = { Eyebrow(if (unseenOnly) "照片 · 仅未看过" else "照片") },
                count = {
                    if (loading) {
                        SkeletonBlock(96.dp, 24.dp, modifier = Modifier.align(Alignment.CenterVertically))
                        Text(
                            "正在读取相册…",
                            style = DarkroomText.CountSub,
                            modifier = Modifier.align(Alignment.CenterVertically).padding(start = 12.dp - Dimens.CountGap),
                        )
                    } else if (unseenOnly) {
                        HeaderCount(uiState.visibleCount, sub = "· 共 ${NumberFormatter.formatCount(uiState.totalCount)}")
                    } else {
                        HeaderCount(uiState.totalCount, sub = "· ${NumberFormatter.formatCount(uiState.unseenCount)} 未看")
                    }
                },
                actions = {
                    val enabled = !loading
                    ToolIconButton(
                        onClick = { openMenu = if (openMenu == OpenMenu.Sort) OpenMenu.None else OpenMenu.Sort },
                        active = openMenu == OpenMenu.Sort,
                        enabled = enabled,
                        modifier = Modifier.alpha(
                            when {
                                loading -> 0.4f
                                openMenu == OpenMenu.Filter -> 0.5f
                                else -> 1f
                            },
                        ),
                    ) { tint ->
                        Icon(
                            DarkroomIcons.Sort,
                            contentDescription = "排序",
                            tint = tint,
                            modifier = Modifier.size(Dimens.ToolIcon).graphicsLayer { rotationZ = sortRotation.value },
                        )
                    }
                    ToolIconButton(
                        onClick = { openMenu = if (openMenu == OpenMenu.Filter) OpenMenu.None else OpenMenu.Filter },
                        active = openMenu == OpenMenu.Filter,
                        showPip = unseenOnly,
                        enabled = enabled,
                        modifier = Modifier.alpha(
                            when {
                                loading -> 0.4f
                                openMenu == OpenMenu.Sort -> 0.5f
                                else -> 1f
                            },
                        ),
                    ) { tint -> Icon(DarkroomIcons.Filter, contentDescription = "筛选", tint = tint, modifier = Modifier.size(Dimens.ToolIcon)) }
                },
            )

            SortToast(sortToast)

            DarkroomMenu(
                visible = openMenu == OpenMenu.Sort,
                header = "排序",
                options = listOf(
                    MenuOption("最新优先", "默认", uiState.sortOrder == PhotoSortOrder.NewestFirst) {
                        onSortOrderChanged(PhotoSortOrder.NewestFirst); openMenu = OpenMenu.None
                    },
                    MenuOption("最早优先", null, uiState.sortOrder == PhotoSortOrder.OldestFirst) {
                        onSortOrderChanged(PhotoSortOrder.OldestFirst); openMenu = OpenMenu.None
                    },
                ),
            )
            DarkroomMenu(
                visible = openMenu == OpenMenu.Filter,
                header = "显示",
                options = listOf(
                    MenuOption("全部照片", NumberFormatter.formatCount(uiState.totalCount), !unseenOnly) {
                        onFilterModeChanged(PhotoFilterMode.AllPhotos); openMenu = OpenMenu.None
                    },
                    MenuOption("仅未看过", NumberFormatter.formatCount(uiState.unseenCount), unseenOnly) {
                        onFilterModeChanged(PhotoFilterMode.UnseenOnly); openMenu = OpenMenu.None
                    },
                ),
            )
        }
    }
}

@Composable
private fun PhotoList(uiState: AllPhotosUiState, onPhotoClick: (Long) -> Unit) {
    val develop = remember { tileDevelopFor(uiState.isInitialLoad) }
    val gridState = rememberLazyGridState()
    val anchor = rememberReorderAnchor(gridState, uiState.sortOrder to uiState.filterMode)
    LazyVerticalGrid(
        columns = GridCells.Fixed(PHOTO_GRID_COLUMNS),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap),
        contentPadding = PaddingValues(bottom = dockListBottomPadding()),
    ) {
        photoDaySections(
            sections = uiState.sections,
            unseen = { it !in uiState.seenIds },
            right = { section ->
                if (section.unseenCount > 0) {
                    MonoWithBold(section.unseenCount.toString(), " / ${section.totalCount} 未看")
                } else {
                    MonoWithBold(null, "已看完 · ${section.totalCount}")
                }
            },
            develop = develop,
            reorderAnchor = anchor,
            onPhotoClick = onPhotoClick,
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
    EmptyStateArea {
        val painters = uiState.hangPhotos.map { rememberAsyncImagePainter(it.uri) }
        ClotheslineArt(painters, Modifier.padding(bottom = 24.dp))
        EmptyTitleAndBody(
            "全部看过了",
            "${NumberFormatter.formatCount(uiState.totalCount)} 张照片都过了一遍。新拍的照片会出现在这里。",
        )
        val (size, unit) = FileSizeFormatter.parts(stats.trashBytes)
        Row(Modifier.padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            DoneStat(NumberFormatter.formatCount(stats.favoriteCount), null, DarkroomColors.Amber, "收藏")
            DoneStat(NumberFormatter.formatCount(stats.trashCount), "张", DarkroomColors.FadeText, "垃圾桶")
            DoneStat(size, unit, DarkroomColors.Paper, "待释放")
        }
        if (stats.trashCount > 0) {
            GhostButton("看看垃圾桶", onClick = onTrashClick, mono = "${FileSizeFormatter.format(stats.trashBytes)} 待清空")
        }
        Text(
            "显示全部照片",
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

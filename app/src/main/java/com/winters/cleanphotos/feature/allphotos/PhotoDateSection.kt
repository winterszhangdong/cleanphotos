package com.winters.cleanphotos.feature.allphotos

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.onClick
import com.winters.cleanphotos.core.locale.currentLocale
import com.winters.cleanphotos.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.blockTouchesBelow
import com.winters.cleanphotos.core.design.plainClickable
import androidx.compose.material3.Text
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.request.ImageRequest
import com.winters.cleanphotos.core.image.GridThumb
import com.winters.cleanphotos.core.design.DayHeaderRow
import com.winters.cleanphotos.core.design.DelayedSpec
import com.winters.cleanphotos.core.design.DevelopProfile
import com.winters.cleanphotos.core.design.DevelopProfiles
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.MotionDurations
import com.winters.cleanphotos.core.design.MotionEasings
import com.winters.cleanphotos.core.design.MotionSprings
import com.winters.cleanphotos.domain.model.isRawFileName
import com.winters.cleanphotos.domain.model.photoMarkLine
import com.winters.cleanphotos.core.design.PhotoTile
import com.winters.cleanphotos.core.design.MonoWithBold
import com.winters.cleanphotos.core.design.SelectionCheck
import com.winters.cleanphotos.core.design.animateSelectPick
import com.winters.cleanphotos.core.design.selectedShrink
import com.winters.cleanphotos.core.design.YearDivider
import com.winters.cleanphotos.core.design.photoThumbKey
import com.winters.cleanphotos.core.navigation.sharedPhoto
import com.winters.cleanphotos.core.util.DayDisplay
import com.winters.cleanphotos.domain.model.MediaType
import com.winters.cleanphotos.domain.model.PhotoDaySection
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.SectionHeader

/** 四列照片网格的列数。 */
const val PHOTO_GRID_COLUMNS = 4

/**
 * 首屏缩略图的显影参数与错开。
 * [delayFor] 给出某一格（全列表第 [row] 行、第 [col] 列）的起步延迟。
 */
data class TileDevelop(val profile: DevelopProfile, private val byDiagonal: Boolean, private val staggerMs: Int, private val maxSteps: Int) {
    fun delayFor(row: Int, col: Int): Int {
        // 沿对角线（行 + 列）一批批显影，最多 12 步；同一行内按列错开 40ms
        val step = if (byDiagonal) row + col else col
        return step.coerceAtMost(maxSteps) * staggerMs
    }
}

fun tileDevelopFor(initialLoad: Boolean): TileDevelop =
    if (initialLoad) {
        TileDevelop(DevelopProfiles.Loading, byDiagonal = false, DevelopProfiles.LOADING_ROW_STAGGER, DevelopProfiles.GRID_MAX_STEPS)
    } else {
        TileDevelop(DevelopProfiles.GridFirstScreen, byDiagonal = true, DevelopProfiles.GRID_STAGGER, DevelopProfiles.GRID_MAX_STEPS)
    }

/** 进程内已经播过首屏显影的列表（照片 / 收藏 / 相似各一次）；切 Tab、进清理页再回来都不重播。 */
internal object GridFirstScreenSession {
    private val played = mutableSetOf<String>()

    /** 本进程第一次出现这个网格时返回 true，并记下已播放。 */
    fun claim(key: String): Boolean = played.add(key)
}

/**
 * 网格是否还在首屏：只有本进程第一次出现 [sessionKey] 网格、且第一次滚动之前为 true。
 * 首屏格子走错开显影；之后（或再次回到这个网格时）绑定的格子不再显影，命中内存缓存则直接显示。
 */
@Composable
fun rememberGridFirstScreen(state: LazyGridState, sessionKey: String): () -> Boolean =
    rememberFirstScreen(state, sessionKey) { state.isScrollInProgress || state.firstVisibleItemIndex > 0 }

/** 同 [rememberGridFirstScreen]，[scrolled] 第一次为 true 时首屏结束。 */
@Composable
fun rememberFirstScreen(state: Any, sessionKey: String, scrolled: () -> Boolean): () -> Boolean {
    val first = remember(state) { mutableStateOf(GridFirstScreenSession.claim(sessionKey)) }
    LaunchedEffect(state) {
        if (!first.value) return@LaunchedEffect
        snapshotFlow(scrolled).first { it }
        first.value = false
    }
    return remember(first) { { first.value } }
}

/**
 * 重排：每一格用 animateItem 从旧位置平滑滑到新位置（落定弹簧），相对首个可见项按 14ms 步长错开。
 * [reorderAnchor] 是排序切换那一刻首个可见项的下标。
 * [place] 为 false 时不挂 animateItem。范围和「只看没看过」会换掉一整批格子，位移会让
 * 远处的照片和日期从屏幕下沿飞入；切走时这一层不跟滚动，要等弹簧走完才离开。
 */
fun LazyGridItemScope.reorderModifier(index: Int, reorderAnchor: Int, place: Boolean = true): Modifier {
    if (!place) return Modifier
    val delay = (index - reorderAnchor).coerceAtLeast(0) * MotionDurations.REORDER_STAGGER
    return Modifier.animateItem(
        fadeInSpec = tween(MotionDurations.SHORT, easing = MotionEasings.Standard),
        placementSpec = DelayedSpec(delay, MotionSprings.Settle.spec(IntOffset.VisibilityThreshold)),
        fadeOutSpec = tween(MotionDurations.SHORT, easing = MotionEasings.Standard),
    )
}

/**
 * 列表最近一次变化（排序切换、照片移走）那一刻的首个可见项下标（不观察滚动，避免每次滚动都重组列表）。
 * 收藏页一直做位移。[ordering] 按实例比较，传分组列表即可：排序变了它也跟着换。
 * 只在排序时记的话，滚到很深再移走一张，下面每格要等「下标 × 14ms」才动，能停好几秒。
 */
@Composable
fun rememberReorderAnchor(state: LazyGridState, ordering: Any): () -> Int {
    val holder = remember { IntArray(1) }
    val last = remember { arrayOf(ordering) }
    if (last[0] !== ordering) {
        last[0] = ordering
        holder[0] = Snapshot.withoutReadObservation { state.firstVisibleItemIndex }
    }
    return remember { { holder[0] } }
}

/** 照片页网格：只有排序才位移；范围或「只看没看过」变化时关掉。 */
class PhotoReorder(val anchor: () -> Int, val place: () -> Boolean)

@Composable
fun rememberPhotoReorder(state: LazyGridState, sortOrder: Any, membership: Any, items: Any): PhotoReorder {
    val motion = remember { ReorderMotion(sortOrder, membership) }
    val index = if (motion.membership != membership || motion.sort != sortOrder || motion.items !== items) {
        Snapshot.withoutReadObservation { state.firstVisibleItemIndex }
    } else {
        motion.anchor
    }
    motion.advance(sortOrder, membership, index, items)
    return remember(motion) { PhotoReorder({ motion.anchor }, { motion.place }) }
}

internal data class ReorderMotion(
    var sort: Any,
    var membership: Any,
    var anchor: Int = 0,
    var place: Boolean = false,
    /** 上一次的分组列表（按实例比较）。 */
    var items: Any? = null,
)

/**
 * [items] 是分组列表：同一排序、同一范围里列表变了（移走、收藏），错开仍从此刻的首个可见项算起，
 * 否则锚点停在上次排序那一刻，滚远后每格要等「下标差 × 14ms」才动。
 */
internal fun ReorderMotion.advance(sort: Any, membership: Any, firstVisibleItemIndex: Int, items: Any? = null) {
    val itemsChanged = this.items !== items
    this.items = items
    when {
        this.membership != membership -> {
            this.membership = membership
            this.sort = sort
            place = false
        }
        this.sort != sort -> {
            this.sort = sort
            anchor = firstVisibleItemIndex
            place = true
        }
        itemsChanged -> anchor = firstVisibleItemIndex
    }
}

/**
 * 照片页多选：网格外层的手势接管点按（[photoGridGestures]），所以格子不再挂点击；
 * 天标题右侧换成「全选」/「取消全选」。
 */
class GridSelection(
    val selectedIds: Set<Long>,
    val selecting: Boolean,
    val onToggleDay: (dayKey: String) -> Unit,
    /** 读屏：格子的点按 / 长按（手势本身挂在网格外层，格子上没有可点击语义）。 */
    val onToggle: (Long) -> Unit = {},
    val onLongPress: (Long) -> Boolean = { false },
)

/**
 * 年份分隔 + 天标题 + 四列缩略图（LazyVerticalGrid，每张缩略图一个 item，key = photoId，
 * 这样切换排序时每一格都能 animateItem 重排）。范围和「只看没看过」不位移。
 * 只有天标题右侧（数字 + 箭头）进入这一天的第一张；缩略图点击进入那一张。
 */
fun LazyGridScope.photoDaySections(
    sections: List<PhotoDaySection>,
    right: @Composable (PhotoDaySection) -> Unit,
    develop: TileDevelop,
    /** 网格是否还在首屏（还没滚动过）：首屏格子用 [develop] 错开显影，之后进入的格子不显影（直接出图）。 */
    firstScreen: () -> Boolean,
    reorderAnchor: () -> Int,
    reorderPlace: () -> Boolean = { true },
    onPhotoClick: (Long) -> Unit,
    selection: GridSelection? = null,
    showChevron: Boolean = true,
    /** 平铺格子左下角的文件大小。不传则不画。 */
    sizeLabel: ((PhotoItem) -> String)? = null,
    /** 照片页点按按格子的实际位置命中。收藏页用格子自己的点击，不传。 */
    hitRects: PhotoHitRects? = null,
    /** 点天标题进入这一天的方式；不传时进入这一天第一张（清理页）。收藏页传印样表。 */
    onDayEnter: ((PhotoDaySection) -> Unit)? = null,
    /** 照片页展开的那天。收藏页不传，只画前 8 张。「全部」不截断，不传。 */
    expandedDayKey: String? = null,
    /** 点「还有 N 张」或「收起」。不传则不画展开入口（收藏页、「全部」）。 */
    onToggleExpanded: ((String) -> Unit)? = null,
    /** 沉浸视图只铺照片，不画年份、日期行和「还有 N 张」。 */
    showHeaders: Boolean = true,
) {
    var itemIndex = 0
    var rowOffset = 0
    sections.forEachIndexed { index, section ->
        val year = section.yearHeader
        if (showHeaders && year != null) {
            val i = itemIndex++
            item(key = "year-$year-${section.dayKey}", span = { GridItemSpan(maxLineSpan) }, contentType = "year") {
                // 第二个及以后的年份分隔上方多 8dp
                YearDivider(year, reorderModifier(i, reorderAnchor(), reorderPlace()).padding(top = if (index == 0) 0.dp else 8.dp))
            }
        }
        if (showHeaders && section.header != SectionHeader.None) {
            val headerIndex = itemIndex++
            item(key = section.dayKey, span = { GridItemSpan(maxLineSpan) }, contentType = "day") {
                val locale = currentLocale()
                val label = when (section.header) {
                    SectionHeader.Day -> section.date?.let { DayDisplay.dayLabel(it, locale) }
                    SectionHeader.Month -> section.date?.let { DayDisplay.monthTitle(it, locale) }
                    SectionHeader.None -> error("平铺分组不画标题")
                } ?: DayDisplay.unknownDayLabel(stringResource(R.string.date_unknown))
                val expanded = onToggleExpanded != null &&
                    expandedDayKey == section.dayKey &&
                    section.overflow.isNotEmpty()
                val collapseLabel = if (expanded) stringResource(R.string.day_collapse) else null
                val onCollapse = if (expanded) {
                    { onToggleExpanded!!(section.dayKey) }
                } else {
                    null
                }
                if (selection != null && selection.selecting) {
                    val ids = section.selectableIds
                    val allPicked = ids.isNotEmpty() && ids.all { it in selection.selectedIds }
                    DayHeaderRow(
                        numeral = label.dayNumeral,
                        meta = label.monthLabel,
                        right = { MonoWithBold(stringResource(if (allPicked) R.string.deselect_all else R.string.select_all), "") },
                        onEnter = { selection.onToggleDay(section.dayKey) },
                        modifier = reorderModifier(headerIndex, reorderAnchor(), reorderPlace()),
                        showChevron = false,
                        collapseLabel = collapseLabel,
                        onCollapse = onCollapse,
                    )
                } else {
                    DayHeaderRow(
                        numeral = label.dayNumeral,
                        meta = label.monthLabel,
                        right = { right(section) },
                        onEnter = onDayEnter?.let { enter -> { enter(section) } }
                            ?: section.photos.firstOrNull()?.let { first -> { onPhotoClick(first.id) } },
                        modifier = reorderModifier(headerIndex, reorderAnchor(), reorderPlace()),
                        showChevron = showChevron,
                        collapseLabel = collapseLabel,
                        onCollapse = onCollapse,
                    )
                }
            }
        }
        val expanded = onToggleExpanded != null && expandedDayKey == section.dayKey && section.overflow.isNotEmpty()
        val shown = DayExpand.displayedPhotos(section, expanded)
        val firstRow = rowOffset
        shown.forEachIndexed { i, photo ->
            val tileIndex = itemIndex++
            val row = i / PHOTO_GRID_COLUMNS
            val col = i % PHOTO_GRID_COLUMNS
            item(key = photo.id, contentType = "photo") {
                if (hitRects != null) {
                    DisposableEffect(photo.id) {
                        onDispose { hitRects.remove(photo.id) }
                    }
                }
                val context = LocalContext.current
                // 系统缩略图（GridThumb）+ 固定内存键（清理页占位）+ RGB_565；显影已负责过渡，关掉 Crossfade
                val model = remember(photo.id, photo.uri) {
                    ImageRequest.Builder(context)
                        .data(GridThumb(photo.uri))
                        .memoryCacheKey(photoThumbKey(photo.id))
                        .placeholderMemoryCacheKey(photoThumbKey(photo.id))
                        .allowRgb565(true)
                        .crossfade(false)
                        .build()
                }
                // 绑定时定一次：只有首屏才显影；滚动 / 再次进入直接出图（命中内存缓存更是瞬间）
                val onFirstScreen = remember { firstScreen() }
                val tileDevelop = if (onFirstScreen) develop else null
                val selecting = selection?.selecting == true
                val picked = selecting && photo.id in selection!!.selectedIds
                // 非多选时不挂选中动画 / 缩层 / 勾，避免每格常驻 animateFloatAsState + graphicsLayer
                val pick = if (selecting) animateSelectPick(picked, label = "grid-pick") else null
                val corner = photoMarkLine(
                    rawLabel = stringResource(R.string.mark_raw).takeIf { isRawFileName(photo.displayName) },
                    motionLabel = null,
                    sizeLabel = sizeLabel?.invoke(photo),
                )
                PhotoTile(
                    model = model,
                    contentDescription = photo.displayName,
                    isVideo = photo.mediaType == MediaType.Video,
                    durationMs = photo.durationMs,
                    sizeLabel = corner,
                    // 同一天第二行起上方 2dp 间隔（.grid4 gap 2px）；天与天之间没有额外间隔
                    modifier = hitRectModifier(hitRects, photo.id)
                        .then(reorderModifier(tileIndex, reorderAnchor(), reorderPlace()))
                        .padding(top = if (row == 0) 0.dp else Dimens.GridGap)
                        .then(
                            if (selection == null) Modifier
                            else Modifier.semantics {
                                // 网格手势在外层 pointerInput 上，TalkBack 双击 / 双击长按要在格子上声明
                                onClick { if (selecting) selection.onToggle(photo.id) else onPhotoClick(photo.id); true }
                                onLongClick { selection.onLongPress(photo.id) }
                                if (selecting) selected = picked
                            },
                        ),
                    // 滚动中不注册共享元素；停稳后挂上，点进清理页仍能配对
                    sizedModifier = Modifier.sharedPhoto(photo.id, expandsHere = false),
                    developProfile = tileDevelop?.profile,
                    developDelayMs = tileDevelop?.delayFor(firstRow + row, col) ?: 0,
                    imageModifier = if (pick != null) Modifier.selectedShrink { pick.value } else Modifier,
                    onClick = if (selection != null) null else ({ onPhotoClick(photo.id) }),
                ) {
                    if (selecting) SelectionCheck(picked)
                }
            }
        }
        if (showHeaders && !expanded && section.overflow.isNotEmpty() && section.header == SectionHeader.Day && onToggleExpanded != null) {
            val moreIndex = itemIndex++
            item(key = DayExpand.moreKey(section.dayKey), span = { GridItemSpan(maxLineSpan) }, contentType = "more") {
                MoreDayRow(
                    count = section.overflow.size,
                    onClick = { onToggleExpanded(section.dayKey) },
                    modifier = reorderModifier(moreIndex, reorderAnchor(), reorderPlace()),
                )
            }
        }
        rowOffset += (shown.size + PHOTO_GRID_COLUMNS - 1) / PHOTO_GRID_COLUMNS
    }
}

/**
 * 展开的那一天，日期行滑到顶栏下面之后叠在列表上。
 * 位置在布局阶段读滚动和顶栏偏移，不因此重组。滑出这一天就挪到屏幕外。
 */
@Composable
internal fun StuckDayHeader(
    section: PhotoDaySection,
    gridState: LazyGridState,
    statusBar: androidx.compose.ui.unit.Dp,
    chromeHeight: androidx.compose.ui.unit.Dp,
    collapseOffsetPx: () -> Float,
    right: @Composable () -> Unit,
    onEnter: (() -> Unit)?,
    onCollapse: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val headerHeightPx = with(density) { Dimens.DayHeight.roundToPx() }
    val shownIds = remember(section.photos, section.overflow) {
        (section.photos + section.overflow).mapTo(HashSet()) { it.id }
    }
    val lastId = (if (section.overflow.isNotEmpty()) section.overflow else section.photos).lastOrNull()?.id
    val locale = currentLocale()
    val label = section.date?.let { DayDisplay.dayLabel(it, locale) }
        ?: DayDisplay.unknownDayLabel(stringResource(R.string.date_unknown))
    Box(
        modifier
            .fillMaxWidth()
            .offset {
                val info = gridState.layoutInfo
                val header = info.visibleItemsInfo.find { it.key == section.dayKey }
                val last = lastId?.let { id -> info.visibleItemsInfo.find { it.key == id } }
                val visible = header != null || info.visibleItemsInfo.any { item ->
                    val key = item.key
                    key is Long && key in shownIds
                }
                val pin = dayHeaderPinY(
                    statusBarPx = with(density) { statusBar.toPx() },
                    chromeHeightPx = with(density) { chromeHeight.toPx() },
                    collapseOffsetPx = collapseOffsetPx(),
                ).toInt()
                val y = dayHeaderStickY(
                    pinY = pin,
                    headerHeight = headerHeightPx,
                    headerTop = header?.let { gridItemVisualTop(it.offset.y, info.viewportStartOffset) },
                    lastItemTop = last?.let { gridItemVisualTop(it.offset.y, info.viewportStartOffset) },
                    lastItemHeight = last?.size?.height,
                    sectionVisible = visible,
                ) ?: -1_000_000
                IntOffset(0, y)
            }
            .background(DarkroomColors.Room)
            .blockTouchesBelow(),
    ) {
        DayHeaderRow(
            numeral = label.dayNumeral,
            meta = label.monthLabel,
            right = right,
            onEnter = onEnter,
            showChevron = false,
            collapseLabel = if (onCollapse != null) stringResource(R.string.day_collapse) else null,
            onCollapse = onCollapse,
        )
    }
}

/**
 * 「全部」的日期分组：视口里的每一天都可以吸顶。
 * 吸住哪一天、画在哪，在布局阶段读滚动，不因此重组；只有进出视口的那几天变了才重组。
 */
@Composable
internal fun StickyDayHeaders(
    sections: List<PhotoDaySection>,
    gridState: LazyGridState,
    statusBar: androidx.compose.ui.unit.Dp,
    chromeHeight: androidx.compose.ui.unit.Dp,
    collapseOffsetPx: () -> Float,
    selecting: Boolean,
    selectedIds: Set<Long>,
    onToggleDay: (String) -> Unit,
    onOpenPhoto: (Long) -> Unit,
    right: @Composable (PhotoDaySection) -> Unit,
) {
    val ordered = remember(sections) { sections.map { it.dayKey } }
    val photoToDay = remember(sections) {
        buildMap {
            for (section in sections) {
                for (photo in section.photos) put(photo.id, section.dayKey)
                for (photo in section.overflow) put(photo.id, section.dayKey)
            }
        }
    }
    var candidates by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(gridState, ordered, photoToDay) {
        snapshotFlow {
            stickyCandidateDayKeys(
                gridState.layoutInfo.visibleItemsInfo.map { it.key },
                ordered,
                photoToDay,
            )
        }.distinctUntilChanged().collect { candidates = it }
    }
    val byKey = remember(sections) { sections.associateBy { it.dayKey } }
    candidates.forEach { dayKey ->
        val section = byKey[dayKey] ?: return@forEach
        key(dayKey) {
            StuckDayHeader(
                section = section,
                gridState = gridState,
                statusBar = statusBar,
                chromeHeight = chromeHeight,
                collapseOffsetPx = collapseOffsetPx,
                right = {
                    if (selecting) {
                        val ids = section.selectableIds
                        val allPicked = ids.isNotEmpty() && ids.all { it in selectedIds }
                        MonoWithBold(stringResource(if (allPicked) R.string.deselect_all else R.string.select_all), "")
                    } else {
                        right(section)
                    }
                },
                onEnter = if (selecting) {
                    { onToggleDay(section.dayKey) }
                } else {
                    section.photos.firstOrNull()?.let { first -> { onOpenPhoto(first.id) } }
                },
                onCollapse = null,
            )
        }
    }
}

/** 8 张下面那一行。点开这一天被挡住的照片。 */
@Composable
private fun MoreDayRow(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(Dimens.MinTouch)
            .plainClickable(onClick = onClick)
            .padding(horizontal = Dimens.ScreenSide),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.day_more, count), style = DarkroomText.DayRight)
    }
}

/** 最外层记下整格边界（含行距），这样点在缝上仍算这一格。 */
private fun hitRectModifier(hitRects: PhotoHitRects?, id: Long): Modifier {
    if (hitRects == null) return Modifier
    return Modifier.onGloballyPositioned { hitRects.put(id, it.boundsInRoot()) }
}

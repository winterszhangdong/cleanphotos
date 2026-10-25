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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
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
) {
    var itemIndex = 0
    var rowOffset = 0
    sections.forEachIndexed { index, section ->
        val year = section.yearHeader
        if (year != null) {
            val i = itemIndex++
            item(key = "year-$year-${section.dayKey}", span = { GridItemSpan(maxLineSpan) }, contentType = "year") {
                // 第二个及以后的年份分隔上方多 8dp
                YearDivider(year, reorderModifier(i, reorderAnchor(), reorderPlace()).padding(top = if (index == 0) 0.dp else 8.dp))
            }
        }
        if (section.header != SectionHeader.None) {
            val headerIndex = itemIndex++
            item(key = section.dayKey, span = { GridItemSpan(maxLineSpan) }, contentType = "day") {
                val locale = currentLocale()
                val label = when (section.header) {
                    SectionHeader.Day -> section.date?.let { DayDisplay.dayLabel(it, locale) }
                    SectionHeader.Month -> section.date?.let { DayDisplay.monthTitle(it, locale) }
                    SectionHeader.None -> error("平铺分组不画标题")
                } ?: DayDisplay.unknownDayLabel(stringResource(R.string.date_unknown))
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
                    )
                } else {
                    DayHeaderRow(
                        numeral = label.dayNumeral,
                        meta = label.monthLabel,
                        right = { right(section) },
                        onEnter = section.photos.firstOrNull()?.let { first -> { onPhotoClick(first.id) } },
                        modifier = reorderModifier(headerIndex, reorderAnchor(), reorderPlace()),
                        showChevron = showChevron,
                    )
                }
            }
        }
        val firstRow = rowOffset
        section.photos.forEachIndexed { i, photo ->
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
                PhotoTile(
                    model = model,
                    contentDescription = photo.displayName,
                    isVideo = photo.mediaType == MediaType.Video,
                    durationMs = photo.durationMs,
                    sizeLabel = sizeLabel?.invoke(photo),
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
        rowOffset += (section.photos.size + PHOTO_GRID_COLUMNS - 1) / PHOTO_GRID_COLUMNS
    }
}

/** 最外层记下整格边界（含行距），这样点在缝上仍算这一格。 */
private fun hitRectModifier(hitRects: PhotoHitRects?, id: Long): Modifier {
    if (hitRects == null) return Modifier
    return Modifier.onGloballyPositioned { hitRects.put(id, it.boundsInRoot()) }
}

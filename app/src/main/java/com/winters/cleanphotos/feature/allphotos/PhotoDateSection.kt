package com.winters.cleanphotos.feature.allphotos

import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.request.ImageRequest
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
import com.winters.cleanphotos.domain.model.PhotoDaySection

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

/**
 * 重排：每一格用 animateItem 从旧位置平滑滑到新位置（落定弹簧），相对首个可见项按 14ms 步长错开。
 * [reorderAnchor] 是排序切换那一刻首个可见项的下标。
 */
fun LazyGridItemScope.reorderModifier(index: Int, reorderAnchor: Int): Modifier {
    val delay = (index - reorderAnchor).coerceAtLeast(0) * MotionDurations.REORDER_STAGGER
    return Modifier.animateItem(
        fadeInSpec = tween(MotionDurations.SHORT, easing = MotionEasings.Standard),
        placementSpec = DelayedSpec(delay, MotionSprings.Settle.spec(IntOffset.VisibilityThreshold)),
        fadeOutSpec = tween(MotionDurations.SHORT, easing = MotionEasings.Standard),
    )
}

/**
 * 排序 / 筛选切换那一刻的首个可见项下标（不观察滚动，避免每次滚动都重组列表）。
 */
@Composable
fun rememberReorderAnchor(state: LazyGridState, ordering: Any): () -> Int {
    val holder = remember { IntArray(1) }
    val last = remember { arrayOf(ordering) }
    if (last[0] != ordering) {
        last[0] = ordering
        holder[0] = Snapshot.withoutReadObservation { state.firstVisibleItemIndex }
    }
    return remember { { holder[0] } }
}

/**
 * 照片页多选：网格外层的手势接管点按（[photoGridGestures]），所以格子不再挂点击；
 * 天标题右侧换成「全选」/「取消全选」。
 */
class GridSelection(
    val selectedIds: Set<Long>,
    val selecting: Boolean,
    val onToggleDay: (dayKey: String) -> Unit,
)

/**
 * 年份分隔 + 天标题 + 四列缩略图（LazyVerticalGrid，每张缩略图一个 item，key = photoId，
 * 这样切换排序时每一格都能 animateItem 重排）。
 * 只有天标题右侧（数字 + 箭头）进入这一天的第一张；缩略图点击进入那一张。
 */
fun LazyGridScope.photoDaySections(
    sections: List<PhotoDaySection>,
    unseen: (Long) -> Boolean,
    right: @Composable (PhotoDaySection) -> Unit,
    develop: TileDevelop,
    reorderAnchor: () -> Int,
    onPhotoClick: (Long) -> Unit,
    selection: GridSelection? = null,
) {
    var itemIndex = 0
    var rowOffset = 0
    sections.forEachIndexed { index, section ->
        val year = section.yearHeader
        if (year != null) {
            val i = itemIndex++
            item(key = "year-$year-${section.dayKey}", span = { GridItemSpan(maxLineSpan) }, contentType = "year") {
                // 第二个及以后的年份分隔上方多 8dp
                YearDivider(year, reorderModifier(i, reorderAnchor()).padding(top = if (index == 0) 0.dp else 8.dp))
            }
        }
        val headerIndex = itemIndex++
        item(key = section.dayKey, span = { GridItemSpan(maxLineSpan) }, contentType = "day") {
            val label = section.date?.let(DayDisplay::dayLabel) ?: DayDisplay.unknownDayLabel
            if (selection != null && selection.selecting) {
                val allPicked = section.photos.isNotEmpty() && section.photos.all { it.id in selection.selectedIds }
                DayHeaderRow(
                    numeral = label.dayNumeral,
                    meta = label.monthLabel,
                    right = { MonoWithBold(if (allPicked) "取消全选" else "全选", "") },
                    onEnter = { selection.onToggleDay(section.dayKey) },
                    modifier = reorderModifier(headerIndex, reorderAnchor()),
                    showChevron = false,
                )
            } else {
                DayHeaderRow(
                    numeral = label.dayNumeral,
                    meta = label.monthLabel,
                    right = { right(section) },
                    onEnter = section.photos.firstOrNull()?.let { first -> { onPhotoClick(first.id) } },
                    modifier = reorderModifier(headerIndex, reorderAnchor()),
                )
            }
        }
        val firstRow = rowOffset
        section.photos.forEachIndexed { i, photo ->
            val tileIndex = itemIndex++
            val row = i / PHOTO_GRID_COLUMNS
            val col = i % PHOTO_GRID_COLUMNS
            item(key = photo.id, contentType = "photo") {
                val context = LocalContext.current
                // 固定内存缓存键：进入清理页时大图解码完成前先用这张缩略图顶上，避免空帧
                val model = remember(photo.id, photo.uri) {
                    ImageRequest.Builder(context).data(photo.uri).memoryCacheKey(photoThumbKey(photo.id)).build()
                }
                val picked = selection != null && photo.id in selection.selectedIds
                val pick = animateSelectPick(picked, label = "grid-pick")
                PhotoTile(
                    model = model,
                    contentDescription = photo.displayName,
                    // 同一天第二行起上方 2dp 间隔（.grid4 gap 2px）；天与天之间没有额外间隔
                    modifier = reorderModifier(tileIndex, reorderAnchor()).padding(top = if (row == 0) 0.dp else Dimens.GridGap),
                    // 与清理页大图配对（必须在正方形尺寸确定之后）
                    sizedModifier = Modifier.sharedPhoto(photo.id, expandsHere = false),
                    unseen = unseen(photo.id),
                    developProfile = develop.profile,
                    developDelayMs = develop.delayFor(firstRow + row, col),
                    imageModifier = if (selection != null) Modifier.selectedShrink { pick.value } else Modifier,
                    onClick = if (selection != null) null else ({ onPhotoClick(photo.id) }),
                ) {
                    if (selection != null) SelectionCheck(picked)
                }
            }
        }
        rowOffset += (section.photos.size + PHOTO_GRID_COLUMNS - 1) / PHOTO_GRID_COLUMNS
    }
}

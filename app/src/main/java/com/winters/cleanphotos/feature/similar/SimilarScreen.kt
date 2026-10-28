package com.winters.cleanphotos.feature.similar

import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.request.ImageRequest
import com.winters.cleanphotos.R
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.DevelopProfile
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.GlassSpecs
import com.winters.cleanphotos.core.design.MotionDurations
import com.winters.cleanphotos.core.design.MotionEasings
import com.winters.cleanphotos.core.design.MotionSprings
import com.winters.cleanphotos.core.design.PhotoTile
import com.winters.cleanphotos.core.design.SimilarRemoveStamp
import com.winters.cleanphotos.core.design.SkeletonBlock
import com.winters.cleanphotos.core.design.UndoBar
import com.winters.cleanphotos.core.design.UndoCapsuleContent
import com.winters.cleanphotos.core.design.glassBlur
import com.winters.cleanphotos.core.design.photoThumbKey
import com.winters.cleanphotos.core.design.skeletonColor
import com.winters.cleanphotos.core.design.skeletonFill
import com.winters.cleanphotos.core.design.plainClickable
import com.winters.cleanphotos.core.image.GridThumb
import com.winters.cleanphotos.core.locale.currentLocale
import com.winters.cleanphotos.core.text.asString
import com.winters.cleanphotos.core.util.DayDisplay
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.SimilarGroup
import com.winters.cleanphotos.domain.model.SimilarMark
import com.winters.cleanphotos.feature.allphotos.TileDevelop
import com.winters.cleanphotos.feature.allphotos.rememberFirstScreen
import com.winters.cleanphotos.feature.allphotos.tileDevelopFor
import java.util.Locale

/**
 * 相似分组列表。顶栏、范围按钮和底部导航由照片页提供。
 * 撤回胶囊也由照片页画在导航上方。
 * 本进程第一次出组时，首屏缩略图按「卡片序号 + 列」错开显影，命中内存缓存也显影；组的增减用 animateItem 淡入淡出、其余卡片落定弹簧让位。
 */
@Composable
fun SimilarList(
    uiState: SimilarUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onOpen: (groupKey: String, photoId: Long) -> Unit,
    onCommit: (groupKey: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        uiState.groups.isNotEmpty() || uiState.phase != SimilarPhase.Ready -> {
            val firstScreen = if (uiState.groups.isNotEmpty()) {
                rememberFirstScreen(listState, "similar") { listState.isScrollInProgress || listState.firstVisibleItemIndex > 0 }
            } else {
                null
            }
            val tileDevelop = if (firstScreen?.invoke() == true) tileDevelopFor(initialLoad = false) else null
            // 新组排在最前。停在顶部时跟着回顶，否则列表按原首项定位，新组插在视口上面看不到
            val lastGroups = remember { arrayOf(uiState.groups) }
            SideEffect {
                if (lastGroups[0] !== uiState.groups) {
                    lastGroups[0] = uiState.groups
                    if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
                        listState.requestScrollToItem(0)
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(Dimens.DayGap),
            ) {
                // 第一次出结果前：骨架卡呼吸，进度写在顶栏。出组时骨架淡出、真卡片淡入并显影
                if (uiState.phase != SimilarPhase.Ready) {
                    items(SKELETON_CARDS, key = { "skeleton-$it" }, contentType = { "skeleton" }) { card ->
                        SimilarSkeletonCard(card, modifier = similarItemMotion())
                    }
                }
                itemsIndexed(uiState.groups, key = { _, group -> group.key }, contentType = { _, _ -> "group" }) { index, group ->
                    SimilarGroupCard(
                        group = group,
                        marks = uiState.marks[group.key].orEmpty(),
                        enabled = !uiState.moving,
                        develop = tileDevelop,
                        row = index,
                        onOpen = onOpen,
                        onCommit = onCommit,
                        modifier = similarItemMotion(),
                    )
                }
            }
        }
        uiState.phase == SimilarPhase.Ready -> {
            Box(modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.similar_empty),
                    style = DarkroomText.EmptyH4,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Dimens.ScreenSide),
                )
            }
        }
    }
}

private const val SKELETON_CARDS = 3
private const val SKELETON_THUMBS = 4
private const val SKELETON_STAGGER_MS = 200

/** 比对中的占位卡：和 [SimilarGroupCard] 同样的托盘、日期条、一排方格和胶囊，按「卡片 + 列」错开 200ms 呼吸。 */
@Composable
private fun SimilarSkeletonCard(card: Int, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Dimens.FreeCorner)
    val delay = card * SKELETON_STAGGER_MS
    Column(
        modifier
            .padding(horizontal = Dimens.ScreenSide)
            .fillMaxWidth()
            .clip(shape)
            .background(DarkroomColors.Tray)
            .border(Dimens.Hairline, DarkroomColors.Rim, shape)
            .padding(Dimens.FreePadding),
    ) {
        SkeletonBlock(120.dp, 14.dp, delayMs = delay, modifier = Modifier.padding(vertical = 3.dp))
        Row(Modifier.padding(top = Dimens.DayGap), horizontalArrangement = Arrangement.spacedBy(Dimens.DayGap)) {
            repeat(SKELETON_THUMBS) { col ->
                Box(Modifier.size(Dimens.SimilarThumb).skeletonFill(skeletonColor(delay + (col + 1) * SKELETON_STAGGER_MS)))
            }
        }
        Box(
            Modifier
                .padding(top = Dimens.DayGap)
                .fillMaxWidth()
                .height(Dimens.SmallButtonHeight)
                .clip(RoundedCornerShape(Dimens.SmallButtonHeight / 2))
                .skeletonFill(skeletonColor(delay)),
        )
    }
}

/** 组出现 / 消失淡入淡出，其余卡片用落定弹簧让位（ui-design 3.18）。 */
private fun LazyItemScope.similarItemMotion(): Modifier = Modifier.animateItem(
    fadeInSpec = tween(MotionDurations.SHORT, easing = MotionEasings.Standard),
    placementSpec = MotionSprings.Settle.spec(IntOffset.VisibilityThreshold),
    fadeOutSpec = tween(MotionDurations.SHORT, easing = MotionEasings.Standard),
)

/**
 * 缩略图这一行还滚得动时，把剩下的横向滚动和 fling 吃掉。
 * 漏给范围 pager 的话，pager 会吃掉速度却不滚动，页停在两页中间。
 * 这一行放得下时不挂它，横滑直接切范围。竖向照常交出去。
 */
private val eatHorizontalLeftover = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        Offset(available.x, 0f)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(available.x, 0f)
}

@Composable
private fun SimilarGroupCard(
    group: SimilarGroup,
    marks: Map<Long, SimilarMark>,
    enabled: Boolean,
    develop: TileDevelop?,
    row: Int,
    onOpen: (groupKey: String, photoId: Long) -> Unit,
    onCommit: (groupKey: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Dimens.FreeCorner)
    val locale = currentLocale()
    val removeCount = group.photos.count { marks[it.id] == SimilarMark.Remove }
    Column(
        modifier
            .padding(horizontal = Dimens.ScreenSide)
            .fillMaxWidth()
            .clip(shape)
            .background(DarkroomColors.Tray)
            .border(Dimens.Hairline, DarkroomColors.Rim, shape)
            .padding(Dimens.FreePadding),
    ) {
        Text(similarTakenLabel(group.photos.first(), locale), style = DarkroomText.Body)
        val rowState = rememberLazyListState()
        val rowScrollable = rowState.canScrollForward || rowState.canScrollBackward
        LazyRow(
            state = rowState,
            userScrollEnabled = rowScrollable,
            modifier = Modifier
                .padding(top = Dimens.DayGap)
                .fillMaxWidth()
                .height(Dimens.SimilarThumb)
                .then(if (rowScrollable) Modifier.nestedScroll(eatHorizontalLeftover) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(Dimens.DayGap),
        ) {
            itemsIndexed(group.photos, key = { _, photo -> photo.id }) { col, photo ->
                val mark = marks[photo.id] ?: SimilarMark.Keep
                SimilarThumb(
                    photo = photo,
                    mark = mark,
                    contentDescription = stringResource(
                        R.string.similar_film_cd,
                        photo.displayName ?: photo.id.toString(),
                        similarMarkLabel(mark),
                    ),
                    onClick = { onOpen(group.key, photo.id) },
                    developProfile = develop?.profile,
                    developDelayMs = develop?.delayFor(row, col) ?: 0,
                )
            }
        }
        val buttonShape = RoundedCornerShape(Dimens.SmallButtonHeight / 2)
        Box(
            Modifier
                .padding(top = Dimens.DayGap)
                .fillMaxWidth()
                .height(Dimens.SmallButtonHeight)
                .clip(buttonShape)
                .glassBlur(GlassSpecs.Button)
                .border(Dimens.Hairline, DarkroomColors.GlassRim, buttonShape)
                .plainClickable(enabled = enabled, onClick = { onCommit(group.key) }),
            contentAlignment = Alignment.Center,
        ) {
            Text(similarCommitLabel(removeCount), style = DarkroomText.Button13)
        }
    }
}

@Composable
internal fun SimilarThumb(
    photo: PhotoItem,
    mark: SimilarMark,
    contentDescription: String?,
    onClick: (() -> Unit)?,
    framed: Boolean = false,
    developProfile: DevelopProfile? = null,
    developDelayMs: Int = 0,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val model = remember(photo.id, photo.uri) {
        ImageRequest.Builder(context)
            .data(GridThumb(photo.uri))
            .memoryCacheKey(photoThumbKey(photo.id))
            .placeholderMemoryCacheKey(photoThumbKey(photo.id))
            .allowRgb565(true)
            .crossfade(false)
            .build()
    }
    Box(modifier.width(Dimens.SimilarThumb)) {
        PhotoTile(
            model = model,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxWidth(),
            developProfile = developProfile,
            developDelayMs = developDelayMs,
            developCached = developProfile != null,
            onClick = onClick,
        ) {
            if (mark == SimilarMark.Remove) SimilarRemoveStamp(visible = true)
        }
        if (framed) {
            Box(
                Modifier
                    .matchParentSize()
                    .border(Dimens.SimilarFilmStroke, DarkroomColors.Paper, RoundedCornerShape(Dimens.SimilarFilmStroke)),
            )
        }
    }
}

@Composable
internal fun similarMarkLabel(mark: SimilarMark): String = stringResource(
    when (mark) {
        SimilarMark.Keep -> R.string.similar_mark_keep
        SimilarMark.Remove -> R.string.similar_mark_remove
    },
)

@Composable
internal fun similarCommitLabel(removeCount: Int): String =
    if (removeCount > 0) {
        pluralStringResource(R.plurals.similar_move_rest, removeCount, removeCount)
    } else {
        stringResource(R.string.similar_keep_all)
    }

/** 相似提交后的撤回胶囊。位置和样式与照片页批量撤回相同。 */
@Composable
fun SimilarUndoBar(
    undo: SimilarUndo?,
    visible: Boolean,
    onUndo: () -> Unit,
    onExpired: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    UndoBar(
        content = undo?.let {
            UndoCapsuleContent(
                seq = it.seq,
                thumbKey = it.thumbId,
                thumbModel = it.thumbModel,
                favorite = false,
                title = it.title.asString(),
                subtitle = null,
            )
        },
        visible = visible,
        onUndo = onUndo,
        onExpired = onExpired,
        modifier = modifier
            .navigationBarsPadding()
            .padding(start = Dimens.ScreenSide, end = Dimens.ScreenSide, bottom = Dimens.NavBottom + Dimens.NavHeight + Dimens.UndoAboveNav)
            .fillMaxWidth(),
    )
}

/** 「9月21日 · 周一 · 07:42」/「Sep 21 · Mon · 07:42」。没有时刻时只写日期。 */
internal fun similarTakenLabel(photo: PhotoItem, locale: Locale): String {
    val date = DayDisplay.cleanPageDateFor(photo, locale)
    val meta = DayDisplay.reviewMeta(photo, locale)
    return if (meta.isEmpty()) date else "$date · $meta"
}

package com.winters.cleanphotos.feature.similar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import coil.request.ImageRequest
import com.winters.cleanphotos.R
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomIcons
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.GlassSpecs
import com.winters.cleanphotos.core.design.PhotoTile
import com.winters.cleanphotos.core.design.SelectionCheck
import com.winters.cleanphotos.core.design.UndoBar
import com.winters.cleanphotos.core.design.UndoCapsuleContent
import com.winters.cleanphotos.core.design.glassBlur
import com.winters.cleanphotos.core.design.photoThumbKey
import com.winters.cleanphotos.core.design.plainClickable
import com.winters.cleanphotos.core.design.saturateBrightness
import com.winters.cleanphotos.core.image.GridThumb
import com.winters.cleanphotos.core.locale.currentLocale
import com.winters.cleanphotos.core.text.asString
import com.winters.cleanphotos.core.util.DayDisplay
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.SimilarGroup
import com.winters.cleanphotos.domain.model.SimilarMark
import java.util.Locale

/** 标成移走的缩略图：去色再压暗，和垃圾桶小样同一套退影处理。 */
internal const val SIMILAR_REST_BRIGHTNESS = 0.7f
internal val SIMILAR_REST_FILTER: ColorFilter = saturateBrightness(0f, SIMILAR_REST_BRIGHTNESS)

/**
 * 相似分组列表。顶栏、范围按钮和底部导航由照片页提供。
 * 撤回胶囊也由照片页画在导航上方。
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
        uiState.groups.isNotEmpty() || uiState.phase == SimilarPhase.Comparing -> {
            LazyColumn(
                state = listState,
                modifier = modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(Dimens.DayGap),
            ) {
                if (uiState.phase == SimilarPhase.Comparing) {
                    item(key = "comparing", contentType = "comparing") {
                        Text(
                            stringResource(R.string.similar_comparing, uiState.compared, uiState.compareTotal),
                            style = DarkroomText.Caption,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Dimens.ScreenSide, vertical = Dimens.EyebrowGap),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                items(uiState.groups, key = { it.key }, contentType = { "group" }) { group ->
                    SimilarGroupCard(
                        group = group,
                        marks = uiState.marks[group.key].orEmpty(),
                        enabled = !uiState.moving,
                        onOpen = onOpen,
                        onCommit = onCommit,
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

@Composable
private fun SimilarGroupCard(
    group: SimilarGroup,
    marks: Map<Long, SimilarMark>,
    enabled: Boolean,
    onOpen: (groupKey: String, photoId: Long) -> Unit,
    onCommit: (groupKey: String) -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.FreeCorner)
    val locale = currentLocale()
    val removeCount = group.photos.count { marks[it.id] == SimilarMark.Remove }
    Column(
        Modifier
            .padding(horizontal = Dimens.ScreenSide)
            .fillMaxWidth()
            .clip(shape)
            .background(DarkroomColors.Tray)
            .border(Dimens.Hairline, DarkroomColors.Rim, shape)
            .padding(Dimens.FreePadding),
    ) {
        Text(similarTakenLabel(group.photos.first(), locale), style = DarkroomText.Body)
        LazyRow(
            modifier = Modifier
                .padding(top = Dimens.DayGap)
                .fillMaxWidth()
                .height(Dimens.SimilarThumb),
            horizontalArrangement = Arrangement.spacedBy(Dimens.DayGap),
        ) {
            items(group.photos, key = { it.id }) { photo ->
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
    PhotoTile(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier.width(Dimens.SimilarThumb),
        developProfile = null,
        colorFilter = if (mark == SimilarMark.Remove) SIMILAR_REST_FILTER else null,
        onClick = onClick,
    ) {
        when (mark) {
            SimilarMark.Keep -> SelectionCheck(visible = true)
            SimilarMark.Favorite -> SimilarStar()
            SimilarMark.Remove -> Unit
        }
    }
}

@Composable
internal fun BoxScope.SimilarStar() {
    Icon(
        DarkroomIcons.StarSolid,
        contentDescription = null,
        tint = DarkroomColors.Amber,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(Dimens.CheckInset)
            .size(Dimens.CheckBadge),
    )
}

@Composable
internal fun similarMarkLabel(mark: SimilarMark): String = stringResource(
    when (mark) {
        SimilarMark.Keep -> R.string.similar_mark_keep
        SimilarMark.Remove -> R.string.similar_mark_remove
        SimilarMark.Favorite -> R.string.similar_mark_favorite
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
                favorite = it.favoriteStyle,
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

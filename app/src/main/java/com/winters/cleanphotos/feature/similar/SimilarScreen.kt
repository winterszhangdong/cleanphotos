package com.winters.cleanphotos.feature.similar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.GlassSpecs
import com.winters.cleanphotos.core.design.PhotoTile
import com.winters.cleanphotos.core.design.SelectionCheck
import com.winters.cleanphotos.core.design.glassBlur
import com.winters.cleanphotos.core.design.photoThumbKey
import com.winters.cleanphotos.core.design.plainClickable
import com.winters.cleanphotos.core.design.saturateBrightness
import com.winters.cleanphotos.core.image.GridThumb
import com.winters.cleanphotos.core.locale.currentLocale
import com.winters.cleanphotos.core.util.DayDisplay
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.SimilarGroup
import java.util.Locale

/** 没被留下的缩略图：去色再压暗，和垃圾桶小样同一套退影处理。 */
private val REST_FILTER: ColorFilter = saturateBrightness(0f, 0.7f)

/**
 * 相似分组列表。顶栏、范围按钮和底部导航由照片页提供。
 * 撤回胶囊也由照片页画在导航上方。
 */
@Composable
fun SimilarList(
    uiState: SimilarUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onKeep: (groupKey: String, photoId: Long) -> Unit,
    onMoveRest: (groupKey: String) -> Unit,
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
                        enabled = !uiState.moving,
                        onKeep = onKeep,
                        onMoveRest = onMoveRest,
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
    enabled: Boolean,
    onKeep: (groupKey: String, photoId: Long) -> Unit,
    onMoveRest: (groupKey: String) -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.FreeCorner)
    val locale = currentLocale()
    val rest = group.photos.size - 1
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
                SimilarThumb(
                    photo = photo,
                    kept = photo.id == group.keepId,
                    onClick = { onKeep(group.key, photo.id) },
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
                .plainClickable(enabled = enabled, onClick = { onMoveRest(group.key) }),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                pluralStringResource(R.plurals.similar_move_rest, rest, rest),
                style = DarkroomText.Button13,
            )
        }
    }
}

@Composable
private fun SimilarThumb(photo: PhotoItem, kept: Boolean, onClick: () -> Unit) {
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
        contentDescription = photo.displayName,
        modifier = Modifier.width(Dimens.SimilarThumb),
        developProfile = null,
        colorFilter = if (kept) null else REST_FILTER,
        onClick = onClick,
    ) {
        if (kept) SelectionCheck(visible = true)
    }
}

/** 「9月21日 · 周一 · 07:42」/「Sep 21 · Mon · 07:42」。没有时刻时只写日期。 */
private fun similarTakenLabel(photo: PhotoItem, locale: Locale): String {
    val date = DayDisplay.cleanPageDateFor(photo, locale)
    val meta = DayDisplay.reviewMeta(photo, locale)
    return if (meta.isEmpty()) date else "$date · $meta"
}

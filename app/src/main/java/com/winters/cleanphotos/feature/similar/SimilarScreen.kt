package com.winters.cleanphotos.feature.similar

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import com.winters.cleanphotos.core.design.CappedFontScale
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomIcons
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.Eyebrow
import com.winters.cleanphotos.core.design.FontScaleCaps
import com.winters.cleanphotos.core.design.GlassBackdropHost
import com.winters.cleanphotos.core.design.GlassIconButton
import com.winters.cleanphotos.core.design.GlassSpecs
import com.winters.cleanphotos.core.design.ListFade
import com.winters.cleanphotos.core.design.PhotoTile
import com.winters.cleanphotos.core.design.SelectionCheck
import com.winters.cleanphotos.core.design.SystemBarIcons
import com.winters.cleanphotos.core.design.UndoBar
import com.winters.cleanphotos.core.design.UndoCapsuleContent
import com.winters.cleanphotos.core.design.glassBlur
import com.winters.cleanphotos.core.design.photoThumbKey
import com.winters.cleanphotos.core.design.plainClickable
import com.winters.cleanphotos.core.design.saturateBrightness
import com.winters.cleanphotos.core.image.GridThumb
import com.winters.cleanphotos.core.locale.currentLocale
import com.winters.cleanphotos.core.navigation.AppDockEffect
import com.winters.cleanphotos.core.navigation.DockMode
import com.winters.cleanphotos.core.text.asString
import com.winters.cleanphotos.core.util.DayDisplay
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.SimilarGroup
import java.util.Locale

/** 没被留下的缩略图：去色再压暗，和垃圾桶小样同一套退影处理。 */
private val REST_FILTER: ColorFilter = saturateBrightness(0f, 0.7f)

/**
 * 相似照片。没有底部导航；返回是普通 pop（这一页没有预测性返回跟手）。
 */
@Composable
fun SimilarScreen(
    uiState: SimilarUiState,
    onBack: () -> Unit,
    onKeep: (groupKey: String, photoId: Long) -> Unit,
    onMoveRest: (groupKey: String) -> Unit,
    onUndo: () -> Unit,
    onUndoExpired: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    SystemBarIcons(lightBackground = false)
    GlassBackdropHost {
        AppDockEffect(DockMode.Hidden)
        Box(modifier.fillMaxSize().background(DarkroomColors.Room)) {
            Column(Modifier.fillMaxSize()) {
                SimilarHeader(groupCount = uiState.groups.size, onBack = onBack)
                if (uiState.phase == SimilarPhase.Comparing) {
                    Text(
                        stringResource(R.string.similar_comparing, uiState.compared, uiState.compareTotal),
                        style = DarkroomText.Caption,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Dimens.ScreenSide, vertical = Dimens.EyebrowGap),
                        textAlign = TextAlign.Center,
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        uiState.groups.isNotEmpty() -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(top = Dimens.FreeTop, bottom = Dimens.ListFade),
                                verticalArrangement = Arrangement.spacedBy(Dimens.DayGap),
                            ) {
                                items(uiState.groups, key = { it.key }) { group ->
                                    SimilarGroupCard(
                                        group = group,
                                        enabled = !uiState.moving,
                                        onKeep = onKeep,
                                        onMoveRest = onMoveRest,
                                    )
                                }
                            }
                            ListFade()
                        }
                        uiState.phase == SimilarPhase.Ready -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
            }
            val undo = uiState.undo
            UndoBar(
                content = undo?.let {
                    val first = it.action.photos.first()
                    UndoCapsuleContent(
                        seq = it.seq,
                        thumbKey = first.id,
                        thumbModel = first.uri,
                        favorite = false,
                        title = it.title.asString(),
                        subtitle = null,
                    )
                },
                visible = undo != null,
                onUndo = onUndo,
                onExpired = onUndoExpired,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = Dimens.ScreenSide, end = Dimens.ScreenSide, bottom = Dimens.NavBottom)
                    .fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SimilarHeader(groupCount: Int, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(Dimens.HeaderHeight)
            .padding(start = Dimens.ScreenSide, end = Dimens.ScreenSide, bottom = Dimens.HeaderBottomPadding),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Dimens.HeaderActionGap),
    ) {
        GlassIconButton(onClick = onBack) {
            Icon(
                DarkroomIcons.Back,
                contentDescription = stringResource(R.string.cd_back),
                tint = DarkroomColors.Paper,
                modifier = Modifier.width(Dimens.ToolIcon).height(Dimens.ToolIcon),
            )
        }
        CappedFontScale(FontScaleCaps.HEADER) {
            Column(Modifier.weight(1f)) {
                Eyebrow(stringResource(R.string.similar_photos))
                Text(
                    pluralStringResource(R.plurals.similar_group_count, groupCount, groupCount),
                    style = DarkroomText.CountSub,
                    modifier = Modifier.padding(top = Dimens.EyebrowGap),
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

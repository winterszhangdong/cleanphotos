package com.winters.cleanphotos.feature.favorites

import com.winters.cleanphotos.core.locale.currentLocale
import com.winters.cleanphotos.core.text.asString
import com.winters.cleanphotos.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import com.winters.cleanphotos.core.design.rememberPhotoGridState
import com.winters.cleanphotos.feature.allphotos.PHOTO_GRID_COLUMNS
import com.winters.cleanphotos.feature.allphotos.rememberReorderAnchor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.winters.cleanphotos.core.navigation.AppDockEffect
import com.winters.cleanphotos.core.navigation.DockMode
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.EmptyStateArea
import com.winters.cleanphotos.core.design.EmptyTitleAndBody
import com.winters.cleanphotos.core.design.FavPip
import com.winters.cleanphotos.core.design.FavoritePileArt
import com.winters.cleanphotos.core.design.GhostButton
import com.winters.cleanphotos.core.design.GlassBackdropHost
import com.winters.cleanphotos.core.design.HeaderCount
import com.winters.cleanphotos.core.design.ListFade
import com.winters.cleanphotos.core.design.MonoWithBold
import com.winters.cleanphotos.core.design.ScreenHeader
import com.winters.cleanphotos.core.design.SystemBarIcons
import com.winters.cleanphotos.core.design.dockListBottomPadding
import com.winters.cleanphotos.core.design.glassBackdropSource
import com.winters.cleanphotos.core.util.NumberFormatter
import com.winters.cleanphotos.feature.allphotos.photoDaySections
import com.winters.cleanphotos.feature.allphotos.tileDevelopFor
import com.winters.cleanphotos.feature.allphotos.rememberGridFirstScreen
import com.winters.cleanphotos.core.design.rememberScrollSettled
import com.winters.cleanphotos.core.design.gridScrollTapGuard
import com.winters.cleanphotos.core.design.whenSettled

/**
 * 收藏页与空状态。没有排序和筛选；单位后面一颗琥珀点（左距 6dp）。
 */
@Composable
fun FavoritesScreen(
    uiState: FavoritesUiState,
    onPhotoClick: (Long) -> Unit,
    onNavigateBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    SystemBarIcons(lightBackground = false)
    val empty = !uiState.isLoading && uiState.sections.isEmpty()

    GlassBackdropHost {
        AppDockEffect(DockMode.Visible)
        Box(modifier.fillMaxSize().background(DarkroomColors.Room)) {
            Column(Modifier.fillMaxSize()) {
                ScreenHeader(
                    count = {
                        // 内层不再吃外层的 CountGap，琥珀点左距只剩 FavPip 自己的 6dp，贴在单位后面。
                        Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.Bottom) {
                            HeaderCount(
                                uiState.totalCount,
                                unit = pluralStringResource(R.plurals.favorites_count_unit, uiState.totalCount),
                            )
                            if (!empty) FavPip()
                        }
                    },
                )
                if (!empty) {
                    val develop = remember { tileDevelopFor(initialLoad = false) }
                    val gridState = rememberPhotoGridState()
                    val anchor = rememberReorderAnchor(gridState, uiState.sortOrder)
                    val firstScreen = rememberGridFirstScreen(gridState, sessionKey = "favorites")
                    val tapsSettled = rememberScrollSettled(gridState)
                    val openPhoto = tapsSettled.whenSettled(onPhotoClick)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(PHOTO_GRID_COLUMNS),
                        state = gridState,
                        modifier = Modifier.weight(1f).fillMaxSize().gridScrollTapGuard(),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap),
                        contentPadding = PaddingValues(bottom = dockListBottomPadding()),
                    ) {
                        photoDaySections(
                            sections = uiState.sections,
                            right = { section -> MonoWithBold(null, pluralStringResource(R.plurals.favorites_section_count, section.totalCount, section.totalCount)) },
                            develop = develop,
                            firstScreen = firstScreen,
                            reorderAnchor = anchor,
                            onPhotoClick = openPhoto,
                        )
                    }
                }
            }
            if (empty) {
                EmptyStateArea {
                    if (uiState.errorMessage != null) {
                        EmptyTitleAndBody(stringResource(R.string.favorites_error_title), uiState.errorMessage.asString())
                    } else {
                        FavoritePileArt(Modifier.padding(bottom = 32.dp))
                        EmptyTitleAndBody(stringResource(R.string.favorites_empty_title), stringResource(R.string.favorites_empty_body))
                        GhostButton(stringResource(R.string.go_review), onClick = onNavigateBack, mono = pluralStringResource(R.plurals.unseen_count, uiState.unseenCount, NumberFormatter.formatCount(uiState.unseenCount, currentLocale())))
                    }
                }
            } else {
                ListFade()
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .height(Dimens.NavHeight + Dimens.NavBottom)
                        .background(DarkroomColors.Room)
                        .glassBackdropSource(),
                )
            }

        }
    }
}

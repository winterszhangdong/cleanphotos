package com.winters.cleanphotos.feature.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import com.winters.cleanphotos.core.design.Eyebrow
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

/**
 * 收藏页与空状态。没有排序和筛选；eyebrow 旁一颗琥珀点。
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
            Column(Modifier.fillMaxSize().glassBackdropSource()) {
                ScreenHeader(
                    eyebrow = {
                        Eyebrow("收藏")
                        if (!empty) FavPip()
                    },
                    count = { HeaderCount(uiState.totalCount) },
                )
                if (!empty) {
                    val develop = remember { tileDevelopFor(initialLoad = false) }
                    val gridState = rememberLazyGridState()
                    val anchor = rememberReorderAnchor(gridState, uiState.sortOrder)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(PHOTO_GRID_COLUMNS),
                        state = gridState,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.GridGap),
                        contentPadding = PaddingValues(bottom = dockListBottomPadding()),
                    ) {
                        photoDaySections(
                            sections = uiState.sections,
                            unseen = { false },
                            right = { section -> MonoWithBold(null, "${section.totalCount} 张") },
                            develop = develop,
                            reorderAnchor = anchor,
                            onPhotoClick = onPhotoClick,
                        )
                    }
                }
            }
            if (empty) {
                EmptyStateArea {
                    if (uiState.errorMessage != null) {
                        EmptyTitleAndBody("没能读取收藏", uiState.errorMessage)
                    } else {
                        FavoritePileArt(Modifier.padding(bottom = 32.dp))
                        EmptyTitleAndBody("还没有收藏", "在清理页把好照片向上一推，它就会留在这里。")
                        GhostButton("去清理", onClick = onNavigateBack, mono = "${NumberFormatter.formatCount(uiState.unseenCount)} 张未看")
                    }
                }
            } else {
                ListFade()
            }

        }
    }
}

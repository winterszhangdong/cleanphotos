package com.winters.cleanphotos.feature.favorites

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.winters.cleanphotos.domain.model.PhotoSortOrder

@Composable
fun FavoritesRoute(
    onNavigateToReview: (photoId: Long, sortOrder: String) -> Unit,
    onNavigateBack: () -> Unit = {},
    onOpenDay: (dayKey: String) -> Unit = {},
    viewModel: FavoritesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    FavoritesScreen(
        uiState = uiState,
        onPhotoClick = { photoId ->
            onNavigateToReview(photoId, uiState.sortOrder.name)
        },
        onNavigateBack = onNavigateBack,
        onOpenDay = onOpenDay,
    )
}

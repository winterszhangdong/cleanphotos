package com.winters.cleanphotos.feature.allphotos

import com.winters.cleanphotos.R
import com.winters.cleanphotos.TestPhotoData
import com.winters.cleanphotos.core.text.UiText
import com.winters.cleanphotos.domain.model.PhotoDaySection
import com.winters.cleanphotos.domain.model.PhotoSortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AllPhotosContentTest {

    private val error = UiText.Res(R.string.error_load_photos)

    private fun section() = PhotoDaySection(
        dayKey = "2024-05-20",
        date = LocalDate.of(2024, 5, 20),
        yearHeader = 2024,
        photos = listOf(TestPhotoData.createTestPhoto(1)),
        totalCount = 1,
        unseenCount = 1,
    )

    private fun content(state: AllPhotosUiState) = state.content

    @Test
    fun loading_when_empty_and_not_similar() {
        assertEquals(LibraryContent.Loading, content(AllPhotosUiState(isLoading = true)))
    }

    @Test
    fun similar_stays_similar_while_loading() {
        assertEquals(
            LibraryContent.Similar,
            content(AllPhotosUiState(isLoading = true, scope = LibraryScope.Similar)),
        )
    }

    @Test
    fun error_on_empty_list_beats_similar_and_all_seen_and_scope_empty() {
        assertEquals(
            LibraryContent.Error(error),
            content(AllPhotosUiState(scope = LibraryScope.Similar, errorMessage = error)),
        )
        assertEquals(
            LibraryContent.Error(error),
            content(
                AllPhotosUiState(
                    unseenOnly = true,
                    visibleCount = 0,
                    totalCount = 3,
                    errorMessage = error,
                ),
            ),
        )
        assertEquals(
            LibraryContent.Error(error),
            content(
                AllPhotosUiState(
                    scope = LibraryScope.Screenshots,
                    scopeCount = 0,
                    totalCount = 4,
                    errorMessage = error,
                ),
            ),
        )
    }

    @Test
    fun all_seen_ignores_error_when_sections_are_present() {
        assertEquals(
            LibraryContent.AllSeen,
            content(
                AllPhotosUiState(
                    unseenOnly = true,
                    visibleCount = 0,
                    totalCount = 3,
                    errorMessage = error,
                    sections = listOf(section()),
                ),
            ),
        )
    }

    @Test
    fun similar_when_scope_is_similar_and_there_is_no_empty_error() {
        assertEquals(
            LibraryContent.Similar,
            content(AllPhotosUiState(scope = LibraryScope.Similar, sections = listOf(section()), totalCount = 1)),
        )
    }

    @Test
    fun all_seen_scope_empty_scope_seen_library_empty_and_grid() {
        assertEquals(
            LibraryContent.AllSeen,
            content(AllPhotosUiState(unseenOnly = true, visibleCount = 0, totalCount = 3)),
        )
        assertEquals(
            LibraryContent.ScopeEmpty,
            content(AllPhotosUiState(scope = LibraryScope.Screenshots, scopeCount = 0, totalCount = 4)),
        )
        assertEquals(
            LibraryContent.ScopeSeen,
            content(
                AllPhotosUiState(
                    scope = LibraryScope.ChatApps,
                    unseenOnly = true,
                    visibleCount = 0,
                    scopeCount = 3,
                    totalCount = 10,
                ),
            ),
        )
        assertEquals(LibraryContent.LibraryEmpty, content(AllPhotosUiState()))
        assertEquals(
            LibraryContent.LibraryEmpty,
            content(AllPhotosUiState(scope = LibraryScope.LargeFiles, scopeCount = 0, totalCount = 0)),
        )
        val grid = AllPhotosUiState(
            totalCount = 1,
            scopeCount = 1,
            visibleCount = 1,
            sections = listOf(section()),
        )
        assertEquals(LibraryContent.Grid, content(grid))
        assertEquals(LibraryContent.Grid, content(grid.copy(errorMessage = error)))
    }

    @Test
    fun review_sort_and_sort_menu_follow_layout() {
        val large = AllPhotosUiState(scope = LibraryScope.LargeFiles, sortOrder = PhotoSortOrder.OldestFirst)
        assertEquals(PhotoSortOrder.LargestFirst, large.reviewSortOrder)
        assertFalse(large.scope.showsSortMenu)
        val shots = large.copy(scope = LibraryScope.Screenshots)
        assertEquals(PhotoSortOrder.OldestFirst, shots.reviewSortOrder)
        assertTrue(shots.scope.showsSortMenu)
        assertTrue(LibraryScope.Similar.showsSortMenu)
        assertEquals(R.string.filter_empty_screenshots, LibraryScope.Screenshots.emptyTitleRes())
        assertEquals(R.string.filter_empty_large_files, LibraryScope.LargeFiles.emptyTitleRes())
        assertEquals(R.string.filter_empty_chat_apps, LibraryScope.ChatApps.emptyTitleRes())
        assertEquals(R.string.filter_empty_on_this_day, LibraryScope.OnThisDay.emptyTitleRes())
    }

    @Test(expected = IllegalStateException::class)
    fun all_scope_has_no_empty_title() {
        LibraryScope.All.emptyTitleRes()
    }

    @Test
    fun missing_page_is_skeleton_and_similar_stays_similar() {
        val state = AllPhotosUiState(scope = LibraryScope.All, isLoading = true)
        assertEquals(LibraryContent.Loading, state.pageContent(LibraryScope.LargeFiles))
        assertEquals(LibraryContent.Similar, state.pageContent(LibraryScope.Similar))
        assertEquals(state.content, state.pageContent(LibraryScope.All))
        val ready = state.copy(
            isLoading = false,
            pages = mapOf(LibraryScope.Screenshots to ScopePage(emptyList(), scopeCount = 0, visibleCount = 0, visibleBytes = 0)),
            totalCount = 3,
        )
        assertEquals(LibraryContent.ScopeEmpty, ready.pageContent(LibraryScope.Screenshots))
    }

    @Test(expected = IllegalStateException::class)
    fun neighbor_scope_missing_from_visible_list_fails() {
        neighborLibraryScopes(LibraryScope.OnThisDay, visibleLibraryScopes(showOnThisDay = false))
    }
}

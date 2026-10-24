package com.winters.cleanphotos.feature.allphotos

import com.winters.cleanphotos.R
import com.winters.cleanphotos.core.text.UiText
import com.winters.cleanphotos.domain.model.AllSeenStats
import com.winters.cleanphotos.domain.model.PhotoDaySection
import com.winters.cleanphotos.domain.model.PhotoFilterMode
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoSortOrder
import com.winters.cleanphotos.domain.model.ScopeLayout

/** 照片页范围。单选，不持久化。相似不是 [PhotoFilterMode]，列表换成分组卡片。 */
enum class LibraryScope {
    All,
    Similar,
    LargeFiles,
    Screenshots,
    ChatApps,
    OnThisDay,
}

fun LibraryScope.toFilterMode(): PhotoFilterMode = when (this) {
    LibraryScope.All, LibraryScope.Similar -> PhotoFilterMode.AllPhotos
    LibraryScope.LargeFiles -> PhotoFilterMode.LargeFiles
    LibraryScope.Screenshots -> PhotoFilterMode.Screenshots
    LibraryScope.ChatApps -> PhotoFilterMode.ChatApps
    LibraryScope.OnThisDay -> PhotoFilterMode.OnThisDay
}

/** 网格怎么分组。相似不画这张网格，属性仍是按天。 */
val LibraryScope.layout: ScopeLayout
    get() = when (this) {
        LibraryScope.All, LibraryScope.OnThisDay, LibraryScope.Similar -> ScopeLayout.Day
        LibraryScope.LargeFiles -> ScopeLayout.Flat
        LibraryScope.Screenshots, LibraryScope.ChatApps -> ScopeLayout.Month
    }

/** 大文件固定按大小排，⋯ 菜单不显示排序。用户选的 sortOrder 仍留着。 */
val LibraryScope.showsSortMenu: Boolean
    get() = layout != ScopeLayout.Flat

/** 大文件 / 截图 / 聊天：顶栏副文字是可见照片的大小合计。 */
val LibraryScope.showsByteTotal: Boolean
    get() = this == LibraryScope.LargeFiles || this == LibraryScope.Screenshots || this == LibraryScope.ChatApps

/** 除「全部」和「相似」以外的范围：本身 0 张时用范围空状态。 */
val LibraryScope.isNarrowRange: Boolean
    get() = this != LibraryScope.All && this != LibraryScope.Similar

/** 范围本身 0 张时的标题。全部和相似没有这个空状态。 */
fun LibraryScope.emptyTitleRes(): Int = when (this) {
    LibraryScope.Screenshots -> R.string.filter_empty_screenshots
    LibraryScope.LargeFiles -> R.string.filter_empty_large_files
    LibraryScope.ChatApps -> R.string.filter_empty_chat_apps
    LibraryScope.OnThisDay -> R.string.filter_empty_on_this_day
    LibraryScope.All, LibraryScope.Similar -> error("$this 没有范围空状态")
}

/** 照片页中间区域画什么。判断在状态上，界面只 when。 */
sealed interface LibraryContent {
    data object Loading : LibraryContent
    data class Error(val message: UiText) : LibraryContent
    data object Similar : LibraryContent
    data object AllSeen : LibraryContent
    data object ScopeEmpty : LibraryContent
    data object ScopeSeen : LibraryContent
    data object LibraryEmpty : LibraryContent
    data object Grid : LibraryContent
}

data class AllPhotosUiState(
    /** 照片页张数（不含收藏、垃圾桶）。 */
    val totalCount: Int = 0,
    /** 全部照片里「那年今天」命中的张数（不受「看过」影响）。 */
    val onThisDayCount: Int = 0,
    /** 当前范围、还没叠「只看没看过」的张数。相似范围不看这个数。 */
    val scopeCount: Int = 0,
    /** 当前范围叠上「只看没看过」之后的张数。 */
    val visibleCount: Int = 0,
    /** [visibleCount] 这些照片的 size 之和，null 记 0。 */
    val visibleBytes: Long = 0L,
    val sortOrder: PhotoSortOrder = PhotoSortOrder.NewestFirst,
    val scope: LibraryScope = LibraryScope.All,
    /** 和范围叠加。不持久化。相似范围不吃这个开关。 */
    val unseenOnly: Boolean = false,
    val sections: List<PhotoDaySection> = emptyList(),
    val seenIds: Set<Long> = emptySet(),
    /** 「全部看过了」三个数字（决定 11）。 */
    val allSeenStats: AllSeenStats = AllSeenStats(favoriteCount = 0, trashCount = 0, trashBytes = 0L),
    /** 晾绳上的三张照片。 */
    val hangPhotos: List<PhotoItem> = emptyList(),
    val isLoading: Boolean = false,
    /** 冷启动第一次读相册：用加载显影参数，之后用网格首屏参数。 */
    val isInitialLoad: Boolean = true,
    val hasPermission: Boolean = false,
    val errorMessage: UiText? = null,
) {
    /** 数量为 0 且当前不是这个范围时，不显示「那年今天」。 */
    val showOnThisDay: Boolean
        get() = onThisDayCount > 0 || scope == LibraryScope.OnThisDay

    /**
     * 进清理页用的排序。大文件固定 [PhotoSortOrder.LargestFirst]，
     * 菜单里的 [sortOrder] 不动，切回别的范围照常生效。
     */
    val reviewSortOrder: PhotoSortOrder
        get() = if (scope.layout == ScopeLayout.Flat) PhotoSortOrder.LargestFirst else sortOrder

    /**
     * 中间区域。顺序与原来屏幕上的布尔判断一致：
     * 加载（相似范围除外）→ 空列表上的错误 → 相似 → 全部看过了 → 范围为空 → 范围都看过 → 相册为空 → 网格。
     */
    val content: LibraryContent
        get() {
            val loading = isLoading && sections.isEmpty() && scope != LibraryScope.Similar
            if (loading) return LibraryContent.Loading
            if (errorMessage != null && sections.isEmpty()) return LibraryContent.Error(errorMessage)
            if (scope == LibraryScope.Similar) return LibraryContent.Similar
            if (scope == LibraryScope.All && unseenOnly && visibleCount == 0 && totalCount > 0) {
                return LibraryContent.AllSeen
            }
            if (errorMessage == null && scope.isNarrowRange && scopeCount == 0 && totalCount > 0) {
                return LibraryContent.ScopeEmpty
            }
            if (errorMessage == null && scope.isNarrowRange && unseenOnly && visibleCount == 0 && scopeCount > 0) {
                return LibraryContent.ScopeSeen
            }
            if (sections.isEmpty()) return LibraryContent.LibraryEmpty
            return LibraryContent.Grid
        }
}

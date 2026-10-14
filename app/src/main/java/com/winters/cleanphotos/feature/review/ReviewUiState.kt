package com.winters.cleanphotos.feature.review

import com.winters.cleanphotos.core.text.UiText
import com.winters.cleanphotos.domain.model.DaySummary
import com.winters.cleanphotos.domain.model.PhotoActionType
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.ReviewPage
import com.winters.cleanphotos.domain.model.ReviewScope
import com.winters.cleanphotos.domain.model.SessionDayProgress

/** 撤回胶囊：seq 每次有效操作递增，UI 用它重启 4 秒倒计时。 */
data class UndoInfo(
    val seq: Long,
    val photo: PhotoItem,
    val type: PhotoActionType,
    val title: UiText,
    val subtitle: UiText,
)

data class ReviewUiState(
    val isLoading: Boolean = true,
    val scope: ReviewScope = ReviewScope.AllPhotos,
    /** 翻页序列：还没移走的照片 + 小结插页，按会话顺序。 */
    val pages: List<ReviewPage> = emptyList(),
    val currentKey: String? = null,
    /** 当前页及左右相邻页各自作为「当前」时的当天进度（UI 本地先切页时不必等下一帧）。 */
    val progress: Map<String, SessionDayProgress> = emptyMap(),
    /** 序列里小结插页的内容，按 dayKey。 */
    val summaries: Map<String, DaySummary> = emptyMap(),
    val showDirectionHints: Boolean = false,
    val showTutorial: Boolean = false,
    val undo: UndoInfo? = null,
    val trashCount: Int = 0,
    val errorMessage: UiText? = null,
    /** 照片全部处理完且没有小结：回到入口页。 */
    val isCompleted: Boolean = false,
)

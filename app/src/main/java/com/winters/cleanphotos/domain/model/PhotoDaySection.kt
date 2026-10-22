package com.winters.cleanphotos.domain.model

import java.time.LocalDate

/**
 * 照片页 / 收藏页的一天。
 *
 * @property date 解析后的日期；"Unknown date" 为 null（不画年份分隔、不画星期）。
 * @property yearHeader 这一天上方要画的年份分隔（.year），与上一天同年时为 null。
 * @property photos 当前顺序下露出的缩略图（最多 [MAX_VISIBLE_PHOTOS] 张）。
 * @property totalCount 这一天在统计池里的总张数（照片页 = 相册里这一天的全部，收藏页 = 这一天的收藏）。
 * @property unseenCount 这一天还没看过的张数（统计池，不是当前范围的可见张数）。
 */
data class PhotoDaySection(
    val dayKey: String,
    val date: LocalDate?,
    val yearHeader: Int?,
    val photos: List<PhotoItem>,
    val totalCount: Int,
    val unseenCount: Int,
) {
    /** 这一天已经看过的张数（总数 − 未看）。日期行右侧「3 / 7」。 */
    val seenCount: Int get() = totalCount - unseenCount
    companion object {
        const val MAX_VISIBLE_PHOTOS = 8
    }
}

package com.winters.cleanphotos.domain.model

import java.time.LocalDate

/** 一组标题怎么画。平铺没有标题。 */
enum class SectionHeader {
    Day,
    Month,
    None,
}

/**
 * 照片页 / 收藏页的一组（一天、一月，或平铺的整表）。
 *
 * @property date 解析后的日期；按月时是该月 1 日；未知日期和平铺为 null。
 * @property yearHeader 这一组上方要画的年份分隔，与上一组同年时为 null。
 * @property photos 当前顺序下露出的缩略图。按天最多 [MAX_VISIBLE_PHOTOS] 张；按月和平铺不截断。
 * @property totalCount 这一组在统计池里的总张数（照片页按当前范围、未叠「只看没看过」；收藏页是这一天的收藏）。
 * @property unseenCount 统计池里还没看过的张数。
 * @property selectableIds 这一组里可以选中的全部 id（当前范围叠上「只看没看过」之后，按显示顺序，含没露出的）。
 */
data class PhotoDaySection(
    val dayKey: String,
    val date: LocalDate?,
    val yearHeader: Int?,
    val photos: List<PhotoItem>,
    val totalCount: Int,
    val unseenCount: Int,
    val header: SectionHeader = SectionHeader.Day,
    val selectableIds: List<Long> = photos.map { it.id },
) {
    /** 已经看过的张数（总数 − 未看）。标题右侧「3 / 7」。 */
    val seenCount: Int get() = totalCount - unseenCount
    companion object {
        const val MAX_VISIBLE_PHOTOS = 8
    }
}

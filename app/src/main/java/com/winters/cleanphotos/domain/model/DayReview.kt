package com.winters.cleanphotos.domain.model

import java.time.LocalDate
import java.time.YearMonth

/**
 * 暗房重构 P2：清理会话里「按天」的进度、小结与月份条所需的数据。
 * 规格：docs/interaction.md「当天进度条」「小结插页」，docs/ui-design.md 决定 2 / 11 / 12。
 */

/** 本次会话中某张照片的处理结果。 */
enum class ReviewOutcome { Favorited, Unfavorited, Trashed }

/**
 * 会话账本：保存会话**初始**顺序（继承入口排序与筛选）以及本次会话里看过 / 处理过的记录。
 * ReviewSession.photos 会随处理移除照片，按天进度和小结必须基于初始顺序，所以单独记账。
 */
data class ReviewLedger(
    val orderedPhotos: List<PhotoItem>,
    val viewedIds: Set<Long> = emptySet(),
    val outcomes: Map<Long, ReviewOutcome> = emptyMap(),
    val summarizedDays: Set<String> = emptySet(),
) {
    /** 每次滑动都会 copy 账本好几次；索引只依赖 orderedPhotos，同一份列表复用同一份索引，copy 不再 O(n) 重建。 */
    private val index: LedgerIndex = LedgerIndex.of(orderedPhotos)

    fun indexOf(photoId: Long): Int = index.indexById[photoId] ?: error("照片 $photoId 不在本次会话中")

    fun photo(photoId: Long): PhotoItem = orderedPhotos[indexOf(photoId)]

    /** 会话中这一天的照片，保持会话顺序。 */
    fun photosOfDay(dayKey: String): List<PhotoItem> = index.photosByDay[dayKey].orEmpty()

    /** 会话中出现的日期，按会话顺序去重。 */
    val dayKeysInOrder: List<String> get() = index.dayKeysInOrder

    fun markViewed(photoId: Long): ReviewLedger = copy(viewedIds = viewedIds + photo(photoId).id)

    fun recordOutcome(photoId: Long, outcome: ReviewOutcome): ReviewLedger =
        copy(outcomes = outcomes + (photo(photoId).id to outcome), viewedIds = viewedIds + photoId)

    /** 撤回：去掉处理结果，照片仍算看过。 */
    fun undoOutcome(photoId: Long): ReviewLedger = copy(outcomes = outcomes - photo(photoId).id)

    fun markSummarized(dayKey: String): ReviewLedger = copy(summarizedDays = summarizedDays + dayKey)

    /** 看过或处理过。 */
    fun isTouched(photoId: Long): Boolean = photoId in viewedIds || photoId in outcomes
}

/** 账本里只依赖照片顺序的索引。按列表实例缓存最近一份（账本 copy 时 orderedPhotos 是同一个实例）。 */
private class LedgerIndex(photos: List<PhotoItem>) {
    val indexById: Map<Long, Int> = HashMap<Long, Int>(photos.size * 2).also { map ->
        photos.forEachIndexed { i, p -> map[p.id] = i }
        require(map.size == photos.size) { "会话照片 id 重复" }
    }
    val photosByDay: Map<String, List<PhotoItem>> = photos.groupBy { it.dateGroup }
    val dayKeysInOrder: List<String> = photosByDay.keys.toList()

    companion object {
        /** ViewModel 在主线程处理手势、在后台线程拼界面状态，两边都会读。 */
        @Volatile
        private var last: Pair<List<PhotoItem>, LedgerIndex>? = null

        fun of(photos: List<PhotoItem>): LedgerIndex {
            last?.let { (list, index) -> if (list === photos) return index }
            return LedgerIndex(photos).also { last = photos to it }
        }
    }
}

/** 进度条每一格的状态。 */
enum class DayCellState { Favorited, Unfavorited, Trashed, Kept, Current, Pending }

data class SessionDayProgress(
    val dayKey: String,
    val cells: List<DayCellState>,
    /** 超过 40 张改为连续细线比例条。 */
    val useContinuousBar: Boolean,
)

/** 月份条一天的状态。 */
enum class MonthDayState {
    /** 这一天所有照片都看过或处理过（跨会话，读持久化的看过 / 收藏 / 垃圾桶状态）。 */
    Done,
    /** 小结所对应的这一天（发光）。 */
    Highlight,
    /** 有照片但没整理完（半透明）。 */
    Pending,
    /** 没照片或还没到的天（只剩 4dp 底座）。 */
    Empty,
}

data class MonthProgress(
    val month: YearMonth,
    val days: List<MonthDayState>,
    val doneDays: Int,
    val daysWithPhotos: Int,
)

data class SummaryPreview(val photo: PhotoItem, val outcome: ReviewOutcome?)

data class NextDayInfo(
    val dayKey: String,
    val cover: PhotoItem,
    val unseenCount: Int,
)

data class DaySummary(
    val dayKey: String,
    val date: LocalDate,
    val favoritedCount: Int,
    val unfavoritedCount: Int,
    val trashedCount: Int,
    /** 看过未处理（含被跳过的）都算保留。 */
    val keptCount: Int,
    /** 本次会话移到垃圾桶照片的 size 之和。 */
    val freedBytes: Long,
    /** 前 8 张。 */
    val previews: List<SummaryPreview>,
    val month: MonthProgress,
    /** 会话顺序里的下一组日期；会话结束为 null（隐藏「下一天」）。 */
    val nextDay: NextDayInfo?,
)

/** 垃圾桶「可释放」、全部看过了「待释放」。 */
data class TrashSummary(val count: Int, val totalBytes: Long)

/** 「全部看过了」三个数字（决定 11：只用现有数据）。 */
data class AllSeenStats(val favoriteCount: Int, val trashCount: Int, val trashBytes: Long)

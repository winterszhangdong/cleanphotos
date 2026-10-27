package com.winters.cleanphotos.feature.contactsheet

import com.winters.cleanphotos.core.text.UiText
import com.winters.cleanphotos.domain.model.PhotoAlbum
import com.winters.cleanphotos.domain.model.PhotoItem
import java.time.LocalDate

/**
 * 印样表（收藏里某一天的整理页）。
 *
 * @property photos 这一天还没整理的收藏，按拍摄时间正序（像一卷胶片）。
 * @property circled 油性笔圈中的照片。
 * @property albums 全部相册，按最后装进去的时间排（面板列表、同名判断用）。
 * @property tray 托盘顺序（本次进入固定，见 [TrayRules]）；托盘露出前 [TrayRules.LIMIT] 只。
 * @property moreCount 「还有 N 个」的 N；0 时没有这张卡片。
 * @property systemFavorite 托盘「加系统收藏」，默认关，只在本次进入有效。
 * @property summary 这一天整理完（本次进入至少做过一次动作、且已经没有待整理的）时的小结。
 */
data class ContactSheetUiState(
    val dayKey: String,
    val date: LocalDate?,
    val photos: List<PhotoItem> = emptyList(),
    val circled: Set<Long> = emptySet(),
    val albums: List<PhotoAlbum> = emptyList(),
    val tray: List<PhotoAlbum> = emptyList(),
    val moreCount: Int = 0,
    val systemFavorite: Boolean = false,
    val systemFavoriteSupported: Boolean = false,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val undo: SheetUndo? = null,
    val summary: SheetSummary? = null,
    val message: SheetMessage? = null,
)

/** [favorite] 为 true 是取消收藏（琥珀小样），否则是装袋（中性、保留颜色）。 */
data class SheetUndo(val seq: Long, val thumb: PhotoItem, val title: UiText, val favorite: Boolean)

data class SheetMessage(val seq: Long, val text: UiText)

data class SheetSummary(
    val albums: List<SummaryAlbum>,
    val unfavorited: Int,
    val next: NextDay?,
)

/** 小结里一个袋子：本次放进 [added] 张，袋子现在共 [total] 张；[created] 是本次新建的。 */
data class SummaryAlbum(val relativePath: String, val name: String, val added: Int, val total: Int, val cover: PhotoItem?, val created: Boolean)

data class NextDay(val dayKey: String, val date: LocalDate?, val count: Int, val cover: PhotoItem)

/** 托盘里点 / 拖到的目标：已有的袋子，或新袋子写好的名字。 */
sealed interface SheetTarget {
    data class Existing(val album: PhotoAlbum) : SheetTarget
    data class New(val name: String) : SheetTarget
}

sealed interface ContactSheetEffect {
    data class LaunchSystemAction(val request: com.winters.cleanphotos.domain.model.SystemMediaActionRequest) : ContactSheetEffect
    data object RequestWritePermission : ContactSheetEffect
    /** 装袋成功：照片叠飞进 [relativePath] 的袋子。 */
    data class Flight(val seq: Long, val photos: List<PhotoItem>, val relativePath: String) : ContactSheetEffect
    /** 授权被拒或一张都没放进去：照片叠弹回原位。 */
    data object Bounce : ContactSheetEffect
}

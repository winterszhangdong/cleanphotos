package com.winters.cleanphotos.feature.trash

import com.winters.cleanphotos.core.text.UiText
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PlaybackTarget

data class TrashUiState(
    val photos: List<PhotoItem> = emptyList(),
    val screenMode: TrashScreenMode = TrashScreenMode.Grid,
    val selectedPhotoIds: Set<Long> = emptySet(),
    val previewPhotoId: Long? = null,
    val showEmptyTrashConfirmDialog: Boolean = false,
    val isLoading: Boolean = false,
    val isProcessingAction: Boolean = false,
    val actionMessage: UiText? = null,
    val errorMessage: UiText? = null,
    /** 垃圾桶总大小（「清空后可释放」）。 */
    val totalBytes: Long = 0L,
    /**
     * 最早一张已放的整天数。满 30 天才有值，卡片副文字换成天数；
     * null 时保持原来的「只在 App 内标记」副文字。
     */
    val oldestDays: Int? = null,
    /** photoId → 移入时间。 */
    val movedAt: Map<Long, Long> = emptyMap(),
    /** 清空成功后正在依次溶解的那一批格子（清空前的快照）；播完由界面回调清掉。 */
    val dissolving: TrashDissolve? = null,
    /** 本次停留期间刚清空释放的大小，空状态写「刚刚释放了 X」。 */
    val justFreedBytes: Long? = null,
    /** 累计已确认释放的字节。0 不显示。 */
    val totalFreedBytes: Long = 0L,
    /** 当前预览可播放的视频或动态照片。 */
    val playback: PlaybackTarget? = null,
    /** 这次停留垃圾桶页的静音。离开预览不清。 */
    val playbackMuted: Boolean = true,
)

/** 清空前的网格快照：照片按阅读顺序，[totalBytes] 是「可释放」数字滚动归零的起点。 */
data class TrashDissolve(val photos: List<PhotoItem>, val totalBytes: Long)

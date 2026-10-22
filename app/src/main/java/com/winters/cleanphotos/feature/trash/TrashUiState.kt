package com.winters.cleanphotos.feature.trash

import com.winters.cleanphotos.core.text.UiText
import com.winters.cleanphotos.domain.model.PhotoItem

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
    /** photoId → 移入时间。 */
    val movedAt: Map<Long, Long> = emptyMap(),
    /** 清空成功后正在依次溶解的那一批格子（清空前的快照）；播完由界面回调清掉。 */
    val dissolving: TrashDissolve? = null,
    /** 本次停留期间刚清空释放的大小，空状态写「刚刚释放了 X」。 */
    val justFreedBytes: Long? = null,
    /** 累计已确认释放的字节。0 不显示。 */
    val totalFreedBytes: Long = 0L,
)

/** 清空前的网格快照：照片按阅读顺序，[totalBytes] 是「可释放」数字滚动归零的起点。 */
data class TrashDissolve(val photos: List<PhotoItem>, val totalBytes: Long)

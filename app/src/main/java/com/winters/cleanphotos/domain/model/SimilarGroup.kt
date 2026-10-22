package com.winters.cleanphotos.domain.model

/**
 * 一组连拍。[photos] 按拍摄时间升序，至少两张。[keepId] 是默认留下的那张，用户可以改。
 */
data class SimilarGroup(
    val photos: List<PhotoItem>,
    val keepId: Long,
) {
    init {
        require(photos.size >= 2) { "少于两张不成组" }
        require(photos.any { it.id == keepId }) { "保留的照片不在组里：$keepId" }
    }

    /** 同一批照片的组身份。保留哪张不影响 key。 */
    val key: String get() = photos.joinToString(",") { it.id.toString() }
}

/** 哈希计算进度。未完成时 [hashes] 为空，完成时是这一批算出来的全部。 */
data class SimilarHashProgress(
    val done: Int,
    val total: Int,
    val hashes: Map<Long, Long>,
    val finished: Boolean,
)

/** 相似页一次扫描：比对中只报进度，算完一次性给出分组。 */
sealed interface SimilarScan {
    data class Comparing(val done: Int, val total: Int) : SimilarScan
    data class Ready(val groups: List<SimilarGroup>) : SimilarScan
}

package com.winters.cleanphotos.domain.model

/**
 * 一组连拍。[photos] 按拍摄时间升序，至少两张。
 * [recommendedId] 是推荐的那张（最清晰，没有清晰度时退回像素数），不是唯一能留下的。
 * [sharpness] 只含这组里算出来的拉普拉斯方差；取图失败的 id 不在里面。
 */
data class SimilarGroup(
    val photos: List<PhotoItem>,
    val recommendedId: Long,
    val sharpness: Map<Long, Double> = emptyMap(),
) {
    init {
        require(photos.size >= 2) { "少于两张不成组" }
        require(photos.any { it.id == recommendedId }) { "推荐的照片不在组里：$recommendedId" }
    }

    /** 同一批照片的组身份。标记哪张不影响 key。 */
    val key: String get() = photos.joinToString(",") { it.id.toString() }
}

/** 哈希计算进度。未完成时 [hashes] 为空，完成时是这一批算出来的全部。 */
data class SimilarHashProgress(
    val done: Int,
    val total: Int,
    val hashes: Map<Long, Long>,
    val finished: Boolean,
)

/** 清晰度计算进度。未完成时 [scores] 为空，完成时是这一批算出来的全部。 */
data class SimilarSharpnessProgress(
    val done: Int,
    val total: Int,
    val scores: Map<Long, Double>,
    val finished: Boolean,
)

/** 相似页一次扫描：比对中只报进度，算完一次性给出分组。 */
sealed interface SimilarScan {
    data class Comparing(val done: Int, val total: Int) : SimilarScan
    data class Ready(val groups: List<SimilarGroup>) : SimilarScan
}

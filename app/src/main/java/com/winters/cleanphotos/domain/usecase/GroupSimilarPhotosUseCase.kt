package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.DHash
import com.winters.cleanphotos.domain.model.MediaType
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.SimilarGroup
import com.winters.cleanphotos.domain.model.SimilarRules
import javax.inject.Inject

/**
 * 按拍摄时间升序，相邻两张间隔 ≤ [SimilarRules.MAX_GAP_MS] 且汉明距离 ≤ [SimilarRules.MAX_HAMMING]
 * 则连成一组（只和前一张比）。取图失败、没有哈希的照片不参与比较，两侧若仍落在时间窗内可以连上。
 * 视频、没有拍摄时间的、凑不满两张的都不成组。组按最早一张的时间倒序，新的在前。
 */
class GroupSimilarPhotosUseCase @Inject constructor() {
    operator fun invoke(photos: List<PhotoItem>, hashes: Map<Long, Long>): List<SimilarGroup> {
        val hashed = imagesForSimilar(photos).filter { it.id in hashes }
        if (hashed.size < 2) return emptyList()
        val runs = mutableListOf<MutableList<PhotoItem>>()
        var run: MutableList<PhotoItem>? = null
        for (i in hashed.indices) {
            val photo = hashed[i]
            val prev = hashed.getOrNull(i - 1)
            val join = prev != null &&
                photo.dateTaken!! - prev.dateTaken!! <= SimilarRules.MAX_GAP_MS &&
                DHash.hamming(hashes.getValue(photo.id), hashes.getValue(prev.id)) <= SimilarRules.MAX_HAMMING
            if (join) {
                run!!.add(photo)
            } else {
                run = mutableListOf(photo)
                runs += run
            }
        }
        return runs
            .filter { it.size >= 2 }
            .map { group -> SimilarGroup(group, chooseSimilarKeep(group).id) }
            .sortedByDescending { it.photos.first().dateTaken }
    }

    /** 10 秒内有邻居的图片。孤立的一张不算哈希。 */
    fun hashCandidates(photos: List<PhotoItem>): List<PhotoItem> {
        val sorted = imagesForSimilar(photos)
        if (sorted.size < 2) return emptyList()
        val out = ArrayList<PhotoItem>()
        for (i in sorted.indices) {
            val t = sorted[i].dateTaken!!
            val prevClose = i > 0 && t - sorted[i - 1].dateTaken!! <= SimilarRules.MAX_GAP_MS
            val nextClose = i < sorted.lastIndex && sorted[i + 1].dateTaken!! - t <= SimilarRules.MAX_GAP_MS
            if (prevClose || nextClose) out += sorted[i]
        }
        return out
    }
}

/** 有效拍摄时间：dateTaken 已由数据层落到「拍摄时间，否则入库时间」。≤0 视为没有。 */
internal fun imagesForSimilar(photos: List<PhotoItem>): List<PhotoItem> =
    photos
        .filter { it.mediaType == MediaType.Image && (it.dateTaken ?: 0L) > 0L }
        .sortedWith(compareBy({ it.dateTaken }, { it.id }))

/**
 * 默认留下像素数最大的一张。像素相同留文件更大的，再相同留更早的，仍相同留 id 更小的。
 * 宽或高缺失算 0 像素，文件大小缺失算 0。
 */
fun chooseSimilarKeep(photos: List<PhotoItem>): PhotoItem {
    require(photos.isNotEmpty())
    return photos.reduce { best, photo -> if (keepBeats(photo, best)) photo else best }
}

private fun pixels(photo: PhotoItem): Long {
    val w = photo.width ?: return 0L
    val h = photo.height ?: return 0L
    if (w <= 0 || h <= 0) return 0L
    return w.toLong() * h
}

private fun keepBeats(photo: PhotoItem, best: PhotoItem): Boolean {
    val pp = pixels(photo)
    val bp = pixels(best)
    if (pp != bp) return pp > bp
    val ps = photo.size ?: 0L
    val bs = best.size ?: 0L
    if (ps != bs) return ps > bs
    val pt = photo.dateTaken ?: Long.MAX_VALUE
    val bt = best.dateTaken ?: Long.MAX_VALUE
    if (pt != bt) return pt < bt
    return photo.id < best.id
}

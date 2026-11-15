package com.winters.cleanphotos.feature.similar

import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.SimilarGroup
import com.winters.cleanphotos.domain.model.SimilarMark
import com.winters.cleanphotos.domain.usecase.chooseSimilarRecommended

/** 扫描把组拿掉之后，对比页还留着的最后一张，以及它当时的标记。 */
data class CompareRemainder(val photo: PhotoItem, val mark: SimilarMark)

/**
 * 对比页正在看的这一组。路由上的 key 在下滑删掉一张之后会变，这里跟到还剩的那一组。
 * [sourceKey] 是标记表用的组 key。
 */
internal data class CompareSession(
    val sourceKey: String,
    val photos: List<PhotoItem>,
    val recommendedId: Long,
    val sharpness: Map<Long, Double>,
)

/**
 * 路由上的组还在就用它；扫描换成少了已删照片的新组时，跟过去。
 * 看得见的只剩一张也留下。一张都不剩，才没有会话。
 * 组已经不在结果里时，用 [remainder] 把最后一张留在这一页。
 */
internal fun compareSession(
    routeKey: String,
    groups: List<SimilarGroup>,
    hiddenIds: Set<Long>,
    remainder: CompareRemainder? = null,
): CompareSession? {
    val source = groups.firstOrNull { it.key == routeKey } ?: similarSuccessor(routeKey, groups)
    if (source != null) {
        val photos = source.photos.filter { it.id !in hiddenIds }
        if (photos.isEmpty()) return null
        val recommended = if (photos.any { it.id == source.recommendedId }) {
            source.recommendedId
        } else {
            chooseSimilarRecommended(photos, source.sharpness).id
        }
        return CompareSession(source.key, photos, recommended, source.sharpness)
    }
    val photo = remainder?.photo?.takeIf { it.id !in hiddenIds } ?: return null
    return CompareSession(routeKey, listOf(photo), photo.id, emptyMap())
}

/**
 * 路由上的组 key 可能还是最初那一串。最后一张若记在后来变短的组 key 上，也能对上。
 */
internal fun remainderForRoute(
    routeKey: String,
    remainder: Map<String, CompareRemainder>,
): CompareRemainder? = remainderStored(routeKey, remainder)?.second

/** [remainderForRoute] 对上的那一条实际记在哪个 key 上。路由对得上就用路由 key。 */
internal fun remainderStorageKey(
    routeKey: String,
    remainder: Map<String, CompareRemainder>,
): String? = remainderStored(routeKey, remainder)?.first

private fun remainderStored(
    routeKey: String,
    remainder: Map<String, CompareRemainder>,
): Pair<String, CompareRemainder>? {
    remainder[routeKey]?.let { return routeKey to it }
    val ids = routeKey.split(',').mapNotNull { it.toLongOrNull() }.toSet()
    val entry = remainder.entries
        .filter { it.value.photo.id in ids }
        .maxByOrNull { (key, _) -> key.split(',').count { it.toLongOrNull() in ids } }
        ?: return null
    return entry.key to entry.value
}

/** 下滑删掉当前张之后要停住的下一张。已经是最后一张时没有下一张。 */
internal fun nextComparePhotoId(photos: List<PhotoItem>, removedId: Long): Long? {
    val index = photos.indexOfFirst { it.id == removedId }
    if (index < 0 || photos.size <= 1) return null
    return if (index < photos.lastIndex) photos[index + 1].id else photos[index - 1].id
}

/**
 * 扫描之后，从列表上消失、又没有并进新组的最后一张。
 * 对比页用它继续停在这一张上。已经下滑删掉的不算。
 */
internal fun soleRemainder(
    previous: List<SimilarGroup>,
    next: List<SimilarGroup>,
    hiddenIds: Set<Long>,
    marks: Map<String, Map<Long, SimilarMark>>,
    kept: Map<String, CompareRemainder>,
): Map<String, CompareRemainder> {
    val present = next.flatMapTo(HashSet()) { group -> group.photos.map { it.id } }
    val result = HashMap<String, CompareRemainder>()
    for ((key, remainder) in kept) {
        if (remainder.photo.id !in hiddenIds && remainder.photo.id !in present) result[key] = remainder
    }
    for (old in previous) {
        if (next.any { it.key == old.key } || similarSuccessor(old.key, next) != null) continue
        val still = old.photos.filter { it.id !in hiddenIds && it.id !in present }
        if (still.size != 1) continue
        val photo = still.first()
        val mark = marks[old.key]?.get(photo.id)
            ?: if (photo.id == old.recommendedId) SimilarMark.Keep else SimilarMark.Remove
        result[old.key] = CompareRemainder(photo, mark)
    }
    return result
}

/** 列表上这一组还看不看得见：被下滑删掉的不算，剩下不足两张就先从列表拿掉。 */
internal fun similarGroupStillVisible(group: SimilarGroup, hiddenIds: Set<Long>): Boolean =
    group.photos.count { it.id !in hiddenIds } >= 2

/**
 * 扫描把组 key 换成少了几张的新组时，把还在的标记带过去。
 * 没有一组旧标记能盖住至少两张时返回 null，调用方用默认标记。
 */
internal fun migratedSimilarMarks(
    previous: Map<String, Map<Long, SimilarMark>>,
    group: SimilarGroup,
): Map<Long, SimilarMark>? {
    val ids = group.photos.map { it.id }
    val donor = previous.values
        .map { marks -> marks to ids.count { it in marks } }
        .filter { (_, covered) -> covered >= 2 }
        .maxWithOrNull(compareBy<Pair<Map<Long, SimilarMark>, Int>> { it.second }.thenBy { it.first.size })
        ?.first
        ?: return null
    val defaults = group.photos.associate { photo ->
        photo.id to if (photo.id == group.recommendedId) SimilarMark.Keep else SimilarMark.Remove
    }
    return group.photos.associate { photo -> photo.id to (donor[photo.id] ?: defaults.getValue(photo.id)) }
}

private fun similarSuccessor(routeKey: String, groups: List<SimilarGroup>): SimilarGroup? {
    val routeIds = routeKey.split(',').mapNotNull { it.toLongOrNull() }.toSet()
    if (routeIds.size < 2) return null
    return groups
        .map { group -> group to group.photos.count { it.id in routeIds } }
        .filter { (_, overlap) -> overlap >= 2 }
        .maxWithOrNull(
            compareBy<Pair<SimilarGroup, Int>> { (group, overlap) -> overlap.toDouble() / group.photos.size }
                .thenBy { (_, overlap) -> overlap },
        )
        ?.first
}

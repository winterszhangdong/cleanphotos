package com.winters.cleanphotos.feature.contactsheet

import com.winters.cleanphotos.domain.model.AlbumRules
import com.winters.cleanphotos.domain.model.PhotoAlbum
import com.winters.cleanphotos.domain.model.PhotoItem
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * 托盘一行（ui-design 3.20「托盘里的袋子」）：最多露出 [LIMIT] 只相册袋子，其余收进「还有 N 个」。
 *
 * 本次进入印样表时按 [com.winters.cleanphotos.domain.usecase.BuildAlbumsUseCase] 的顺序（最后装进去的在前）定下每只袋子的位置，
 * 这次进入里不再按最近使用重排（2026-10-27 用户决定：来回装两个相册时袋子不来回换位）；下次进入重新排。
 * 例外：从面板装进去的（原来不在前 [LIMIT] 只里）相册临时插到第一格，把第 [LIMIT] 只挤进「还有 N 个」；
 * 这次进入里新出现的相册（新袋子建的）也排在最前。
 */
internal object TrayRules {
    const val LIMIT = 8

    /** 「还有 N 个」里的 N；0 表示没有这张卡片。 */
    fun moreCount(total: Int): Int = (total - LIMIT).coerceAtLeast(0)

    /**
     * 托盘顺序（全部相册，前 [LIMIT] 只露在托盘上）。
     * [session] 是进入时定下的路径顺序；[promoted] 是这次进入里插到最前的路径（最近的在前）。
     * 已经不在图库里的路径跳过；[session] 里没有的新相册排在最前（按 [albums] 的顺序）。
     */
    fun arrange(albums: List<PhotoAlbum>, session: List<String>, promoted: List<String>): List<PhotoAlbum> {
        val byPath = albums.associateBy { it.relativePath }
        val known = HashSet(session)
        val fresh = albums.filter { it.relativePath !in known && it.relativePath !in promoted }
        val head = promoted.mapNotNull(byPath::get)
        val headPaths = promoted.toHashSet()
        val rest = session.filter { it !in headPaths }.mapNotNull(byPath::get)
        return fresh + head + rest
    }

    /** 装完 [path] 之后的插队名单：已经露在托盘上的不动；不在前 [LIMIT] 只里的插到第一格。 */
    fun promote(tray: List<PhotoAlbum>, promoted: List<String>, path: String): List<String> {
        if (tray.take(LIMIT).any { it.relativePath == path }) return promoted
        return listOf(path) + promoted.filter { it != path }
    }
}

/** 相册面板的搜索（不含拼音：2026-10-27 用户决定）。 */
internal object AlbumSearch {
    /** 全角 / 半角统一（NFKC）+ 忽略大小写。 */
    fun normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    /** 按名字或路径包含匹配；空串返回全部（顺序不变）。 */
    fun filter(albums: List<PhotoAlbum>, query: String): List<PhotoAlbum> {
        val q = normalize(query.trim())
        if (q.isEmpty()) return albums
        return albums.filter { normalize(it.name).contains(q) || normalize(it.relativePath).contains(q) }
    }

    /** 「+ 新建「xxx」」那一行的名字：输入合法、且没有同名相册（同新袋子的同名规则）时才有。 */
    fun createName(albums: List<PhotoAlbum>, query: String): String? {
        val name = AlbumRules.sanitizeInput(query).trim()
        if (!AlbumRules.isValidName(name)) return null
        return if (AlbumRules.existingFor(name, albums) == null) name else null
    }
}

/** 面板行右侧「最后装进去的时间」。 */
internal sealed interface LastFiled {
    data object Today : LastFiled
    data class DaysAgo(val days: Int) : LastFiled
    data class On(val date: LocalDate) : LastFiled

    companion object {
        /** 今天 → 「今天」；1–6 天前 → 「N 天前」；更早 → 日期。没装过的是 null。 */
        fun of(filedAt: Long?, now: Long, zone: ZoneId): LastFiled? {
            filedAt ?: return null
            val day = Instant.ofEpochMilli(filedAt).atZone(zone).toLocalDate()
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val d = ChronoUnit.DAYS.between(day, today)
            return when {
                d <= 0 -> Today
                d < 7 -> DaysAgo(d.toInt())
                else -> On(day)
            }
        }
    }
}

/**
 * 贴边自动滚动（ui-design 3.20）：进入贴边区 [DELAY_MS] 后开始，目标速度 = [MAX_SPEED] × 深度²；
 * 实际速度以 [RAMP_MS] 时间常数追上去（缓入），目标变小时以 [STOP_MS] 收（缓出，约 150ms 停）。速度单位 dp/s。
 */
internal object EdgeScroll {
    const val DELAY_MS = 150f
    const val RAMP_MS = 120f
    const val STOP_MS = 40f
    const val MAX_SPEED = 600f
    /** 目标是 0 时低于这个速度就直接停。 */
    const val SNAP_SPEED = 20f

    /**
     * 手指在 [start]..[end] 这一段上的贴边深度：靠近 start 为负、靠近 end 为正，绝对值 0..1；不在贴边区为 0。
     */
    fun depth(pos: Float, start: Float, end: Float, zone: Float): Float = when {
        zone <= 0f || end - start <= 2 * zone -> 0f
        pos < start + zone -> -((start + zone - pos) / zone).coerceIn(0f, 1f)
        pos > end - zone -> ((pos - (end - zone)) / zone).coerceIn(0f, 1f)
        else -> 0f
    }

    fun targetSpeed(depth: Float): Float = sign(depth) * MAX_SPEED * depth * depth

    /** 实际速度追目标速度一步（[dtMs] 毫秒）。 */
    fun approach(v: Float, target: Float, dtMs: Float): Float {
        val speedingUp = abs(target) >= abs(v) && (v == 0f || sign(v) == sign(target))
        val tau = if (speedingUp) RAMP_MS else STOP_MS
        val next = v + (target - v) * (1f - exp(-dtMs / tau))
        return if (target == 0f && abs(next) < SNAP_SPEED) 0f else next
    }
}

/** 一次拖动里的贴边滚动状态：记进入贴边区的时刻、当前速度。 */
internal class EdgeScroller {
    var velocity = 0f
        private set
    private var inZoneMs = 0f

    /** 推进 [dtMs]；[depth] 见 [EdgeScroll.depth]。返回这一帧该滚的 dp（带方向）。 */
    fun step(depth: Float, dtMs: Float): Float {
        inZoneMs = if (depth == 0f) 0f else inZoneMs + dtMs
        val target = if (depth != 0f && inZoneMs >= EdgeScroll.DELAY_MS) EdgeScroll.targetSpeed(depth) else 0f
        velocity = EdgeScroll.approach(velocity, target, dtMs)
        return velocity * dtMs / 1000f
    }

    /** 滚到头了：速度清零（不出边缘回弹），还在贴边区就继续等。 */
    fun hitEnd() {
        velocity = 0f
    }
}

/** 名签位置（ui-design 3.20「名签弹出」），单位 px。 */
internal object TagPlacement {
    /** 名签下沿 = min(手指 − fingerGap, 袋子上沿 − fanClear)。 */
    fun bottom(fingerY: Float, envelopeTop: Float, fingerGap: Float, fanClear: Float): Float =
        minOf(fingerY - fingerGap, envelopeTop - fanClear)

    /**
     * 手指到名签下沿至少多远：照片叠浮在指尖上方 [lift]，叠的上沿再往上 [stackHalfHeight]（转过的扇形和张数角标取高的那个），再留 [gap]。
     */
    fun fingerGap(lift: Float, stackHalfHeight: Float, gap: Float): Float = lift + stackHalfHeight + gap

    /**
     * 照片叠的半高：[frameW] × [frameH] 的照片缩到 [scale]、最多转 [maxRotationDeg]° 后外接框的半高，
     * 和右上角张数角标（中心在叠上沿、半高 [badgeHalf]）露出的高度，取大的。
     */
    fun stackHalfHeight(frameW: Float, frameH: Float, scale: Float, maxRotationDeg: Float, badgeHalf: Float): Float {
        val r = Math.toRadians(maxRotationDeg.toDouble())
        val w = frameW * scale / 2f
        val h = frameH * scale / 2f
        val rotated = (h * kotlin.math.cos(r) + w * kotlin.math.sin(r)).toFloat()
        return maxOf(rotated, h + badgeHalf)
    }

    /** 横向对准袋子中心，左右离屏幕边至少 [margin]。 */
    fun left(centerX: Float, width: Float, screenWidth: Float, margin: Float): Float =
        (centerX - width / 2f).coerceIn(margin, (screenWidth - margin - width).coerceAtLeast(margin))

    fun width(content: Float, min: Float, max: Float): Float = content.coerceIn(min, max)
}

/** 面板行左边插在牛皮纸卡座里的相片（ui-design 3.20「相册面板」方案 A）。 */
internal object SheetPrints {
    const val COUNT = 3

    /**
     * 相册里按拍摄时间最新的 [COUNT] 张，第一张在最上面（封面）。拍摄时间相同按 id 大的在前，没有拍摄时间的排最后；
     * 不到 3 张就少画几张，空相册一张不画（只剩卡座和虚线空位）。
     */
    fun of(recent: List<PhotoItem>): List<PhotoItem> =
        recent.sortedWith(compareByDescending<PhotoItem> { it.dateTaken ?: Long.MIN_VALUE }.thenByDescending { it.id }).take(COUNT)
}

/** 袋子上停住的张数和露角（[path] 是哪只袋子）。 */
internal data class HeldEnvelope(val path: String, val count: Int, val cover: PhotoItem?) {
    companion object {
        fun of(album: PhotoAlbum) = HeldEnvelope(album.relativePath, album.count, album.recent.firstOrNull())

        /**
         * 袋子现在该显示什么：飞入合盖之前停在 [inFlight]；还没起飞（等授权、等移动）时停在点下那一刻的 [picked]；
         * 都没有就是相册现在的数据。授权通过后相册数据会先于飞入更新，没有这一步袋子会先跳到新张数、起飞时退回、合盖再变。
         */
        fun shown(album: PhotoAlbum, picked: HeldEnvelope?, inFlight: HeldEnvelope?): HeldEnvelope =
            inFlight ?: picked?.takeIf { it.path == album.relativePath } ?: of(album)
    }
}

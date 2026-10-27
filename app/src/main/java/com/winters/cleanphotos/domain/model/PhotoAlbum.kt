package com.winters.cleanphotos.domain.model

/**
 * 印样表托盘里的一个底片袋 = 系统相册里的一个文件夹（DCIM/ 或 Pictures/ 下）。
 *
 * @property relativePath 与 MediaStore RELATIVE_PATH 同格式，以 `/` 结尾，如 `Pictures/旅行/`。
 * @property count 文件夹里的照片和视频张数（不含 App 垃圾桶里的）。
 * @property recent 按拍摄时间倒序最多 [AlbumRules.PEEK_COUNT] 张；第一张是袋口露出的那一角，长按偷看时扇形滑出。
 */
data class PhotoAlbum(
    val relativePath: String,
    val name: String,
    val count: Int,
    val recent: List<PhotoItem>,
    /** 最后一次装进去的时间（`app_filing_log` 里这个相册最新一条）；没装过是 null。 */
    val lastFiledAt: Long? = null,
)

/**
 * 装进相册的一条记录（收藏页底部「已冲洗」）。一张照片只留最近一次。
 *
 * @property dayKey 这张照片在收藏页属于哪一天（[PhotoItem.dateGroup]）。
 */
data class FilingRecord(
    val photoId: Long,
    val relativePath: String,
    val dayKey: String,
    val filedAt: Long,
) {
    val albumName: String get() = AlbumRules.nameOf(relativePath)
}

/** 一次装袋里移动成功的一张；撤回时移回 [fromPath]。 */
data class MovedPhoto(val photo: PhotoItem, val fromPath: String)

/**
 * 一次装袋的结果。[favoritedAt] 是移出收藏前的收藏时间，撤回时原样写回。
 * [failed] 没有移动（文件已不在、系统拒绝、目录不允许），仍留在收藏里。目标里有同名文件不算失败：另起「名字 (1).jpg」。
 */
data class FilingOutcome(
    val relativePath: String,
    val moved: List<MovedPhoto>,
    val failed: List<PhotoItem>,
    val favoritedAt: Map<Long, Long>,
)

/** 收藏页「已冲洗」里的一天：放去了哪几个袋子、各几张；[photos] 是还在图库里的那些（只读展开）。 */
data class FiledDay(
    val dayKey: String,
    val albums: List<FiledAlbumCount>,
    val photos: List<PhotoItem>,
    val lastFiledAt: Long,
) {
    val count: Int get() = albums.sumOf { it.count }
}

data class FiledAlbumCount(val name: String, val count: Int)

object AlbumRules {
    /** 只有这两个根目录下的文件夹能放照片和视频（Android 10+ 的 Images / Video 表都允许）。 */
    private val ROOTS = listOf("DCIM/", "Pictures/")
    /** 新袋子建在这里。 */
    const val NEW_ALBUM_ROOT = "Pictures/"
    const val PEEK_COUNT = 5
    const val MAX_NAME_LENGTH = 64
    /** 文件夹名不允许的字符；输入时直接拦掉。 */
    private const val FORBIDDEN = "/\\:*?\"<>|"

    /** 能当作底片袋的文件夹：DCIM/ 或 Pictures/ 下的子文件夹（根目录本身不算）。含 `|` 的不算：记录里用它分隔字段。 */
    fun isAlbumPath(path: String): Boolean =
        ROOTS.any { path.startsWith(it) && path.length > it.length } && '|' !in path

    fun nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

    fun pathFor(name: String): String {
        require(isValidName(name)) { "相册名不合法：'$name'" }
        return "$NEW_ALBUM_ROOT${name.trim()}/"
    }

    /** 输入框里去掉不允许的字符，并截到最大长度。 */
    fun sanitizeInput(raw: String): String = raw.filterNot { it in FORBIDDEN || it == '\n' }.take(MAX_NAME_LENGTH)

    fun isValidName(name: String): Boolean {
        val t = name.trim()
        return t.isNotEmpty() && t != "." && t != ".." && !t.startsWith(".") &&
            t.length <= MAX_NAME_LENGTH && t.none { it in FORBIDDEN || it == '\n' }
    }

    /**
     * 新袋子写的名字和已有袋子同名时，直接放进那只袋子，不另建文件夹。
     * 按文件夹名比（去掉首尾空白、忽略大小写：外部存储不区分大小写），不管它在 DCIM/ 还是 Pictures/ 下，
     * 所以写「camera」会放进 DCIM/Camera/。好几个同名时先取 Pictures/<名字>/（新袋子本来会建在那里），
     * 否则按托盘顺序（最近装过的在前）取第一只。
     */
    fun existingFor(name: String, albums: List<PhotoAlbum>): PhotoAlbum? {
        val wanted = name.trim()
        val same = albums.filter { nameOf(it.relativePath).trim().equals(wanted, ignoreCase = true) }
        return same.firstOrNull { it.relativePath.equals("$NEW_ALBUM_ROOT$wanted/", ignoreCase = true) } ?: same.firstOrNull()
    }
}

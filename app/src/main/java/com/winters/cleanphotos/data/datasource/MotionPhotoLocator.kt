package com.winters.cleanphotos.data.datasource

import com.winters.cleanphotos.domain.model.MotionPhotoVideo
import java.nio.charset.Charset

/**
 * 动态照片识别。只依赖 [RandomBytes]，不碰 ContentResolver。
 * 识别不到返回 null。调用方传入的读接口若抛 IO 异常，这里不吞。
 */
internal interface RandomBytes {
    val size: Long
    fun read(position: Long, length: Int): ByteArray
}

internal class ArrayBytes(private val data: ByteArray) : RandomBytes {
    override val size: Long = data.size.toLong()

    override fun read(position: Long, length: Int): ByteArray {
        val start = position.toInt()
        if (position != start.toLong() || start < 0 || length < 0 || start + length > data.size) {
            throw IllegalArgumentException("越界读 position=$position length=$length size=$size")
        }
        return data.copyOfRange(start, start + length)
    }
}

internal object MotionPhotoLocator {
    /** SOS 之前的段只读这么多，避免一张大图把头读进内存。 */
    private const val HEAD_LIMIT = 512 * 1024
    private const val SCAN_WINDOW = 64 * 1024
    private const val LIVE_SLACK = 64L
    private val LATIN1: Charset = Charsets.ISO_8859_1
    private val FTYP = "ftyp".toByteArray(LATIN1)
    private val MOOV = "moov".toByteArray(LATIN1)
    private val MPVD = "mpvd".toByteArray(LATIN1)
    private val SEFT = "SEFT".toByteArray(LATIN1)
    private val SEFH = "SEFH".toByteArray(LATIN1)
    private val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(LATIN1)
    private val EXIF_HEADER = "Exif\u0000\u0000".toByteArray(LATIN1)
    private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "heim", "heis", "avif", "avis")
    private val SEMANTIC = Regex(
        """Item:Semantic\s*=\s*"([^"]*)"|<(?:[\w.]+:)?Semantic>([^<]*)</(?:[\w.]+:)?Semantic>""",
    )
    private val LENGTH = Regex(
        """Item:Length\s*=\s*"(\d+)"|<(?:[\w.]+:)?Length>(\d+)</(?:[\w.]+:)?Length>""",
    )
    private val NAMED_VALUE = { local: String ->
        Regex(
            """(?:^|[\s<])(?:[\w.]+:)?$local\s*=\s*"([^"]*)"|<(?:[\w.]+:)?$local>([^<]*)</(?:[\w.]+:)?$local>""",
        )
    }
    private val motionPhotoValue = NAMED_VALUE("MotionPhoto")
    private val microVideoValue = NAMED_VALUE("MicroVideo")
    private val microOffsetValue = NAMED_VALUE("MicroVideoOffset")
    private val oliveVersionValue = NAMED_VALUE("OLivePhotoVersion")
    private val videoLengthValue = NAMED_VALUE("VideoLength")
    private val liveFooter = Regex("""LIVE_(\d+)""")
    private val DIRECTORY_BLOCK = Regex(
        """(?s)<(?:[\w.]+:)?Directory\b[^>]*>(.*?)</(?:[\w.]+:)?Directory>""",
    )

    fun locate(source: RandomBytes): MotionPhotoVideo? {
        if (source.size < 12) return null
        val headLen = minOf(source.size, HEAD_LIMIT.toLong()).toInt()
        val head = source.read(0, headLen)
        if (head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()) {
            return locateJpeg(source, head)
        }
        return locateHeif(source, head)
    }

    private fun locateJpeg(source: RandomBytes, head: ByteArray): MotionPhotoVideo? {
        val parsed = parseJpegHead(head)
        var oppoMarked = false
        for (xmp in parsed.xmps) {
            // vivo 会把同一份 XMP 写进 Exif 0x02BC 和 APP1。拼在一起再加 Length 会把视频长度算两遍。
            // 每一份 Container:Directory 单独倒推，校验通过就用。
            for (block in directoryBlocks(xmp)) {
                googleOffset(xmp, block, source.size)?.let { offset ->
                    validate(source, offset)?.let { return it }
                }
            }
            microOffset(xmp, source.size)?.let { offset ->
                validate(source, offset)?.let { return it }
            }
            if (isOppo(xmp)) {
                oppoMarked = true
                val offset = oppoOffset(xmp, source.size)
                if (offset != null) {
                    validate(source, offset)?.let { return it }
                }
            }
        }
        samsungOffset(source)?.let { offset ->
            validate(source, offset)?.let { return it }
        }
        honorOffset(source)?.let { offset ->
            // 华为样本的 LIVE_ 长度比实际视频多算 20 字节，起点落在 ftyp 之后；就近往回扫
            (validate(source, offset) ?: scanFtyp(source, maxOf(0L, offset - LIVE_SLACK)))?.let { return it }
        }
        val make = parsed.make?.trim()?.uppercase()
        val scan = oppoMarked || make == "HUAWEI" || make == "HONOR"
        if (!scan) return null
        val sos = parsed.sosContent ?: return null
        val eoi = findEoi(source, sos) ?: return null
        return scanFtyp(source, eoi)
    }

    private fun locateHeif(source: RandomBytes, head: ByteArray): MotionPhotoVideo? {
        if (head.size < 12 || !head.copyOfRange(4, 8).contentEquals(FTYP)) return null
        val brand = head.copyOfRange(8, 12).toString(LATIN1)
        if (brand !in HEIF_BRANDS) return null
        var pos = 0L
        while (pos + 8 <= source.size) {
            val hdr = source.read(pos, 8)
            val box = readBox(source, pos, hdr) ?: return null
            if (hdr.copyOfRange(4, 8).contentEquals(MPVD)) {
                return validate(source, pos + box.headerSize)
            }
            if (box.extendsToEof) return null
            pos += box.size
        }
        return null
    }

    /** 从 [offset] 走顶层 box。见到完整的 moov 后，非法尾巴截掉；moov 之前就越界则失败。 */
    fun validate(source: RandomBytes, offset: Long): MotionPhotoVideo? {
        if (offset < 0 || offset + 8 > source.size) return null
        val first = source.read(offset, 8)
        if (!first.copyOfRange(4, 8).contentEquals(FTYP)) return null
        var pos = offset
        var end = offset
        var sawMoov = false
        while (pos + 8 <= source.size) {
            val hdr = if (pos == offset) first else source.read(pos, 8)
            if (!printable(hdr.copyOfRange(4, 8))) break
            val box = readBox(source, pos, hdr) ?: break
            if (hdr.copyOfRange(4, 8).contentEquals(MOOV)) sawMoov = true
            pos += box.size
            end = pos
            if (box.extendsToEof) break
        }
        if (!sawMoov || end <= offset) return null
        return MotionPhotoVideo(offset, end - offset)
    }

    private data class BoxHeader(val size: Long, val headerSize: Int, val extendsToEof: Boolean)

    private fun readBox(source: RandomBytes, pos: Long, hdr: ByteArray): BoxHeader? {
        val size32 = beU32(hdr, 0)
        return when (size32) {
            0L -> BoxHeader(source.size - pos, 8, true)
            1L -> {
                if (pos + 16 > source.size) return null
                val large = beU64(source.read(pos + 8, 8), 0)
                if (large < 16 || pos + large > source.size) return null
                BoxHeader(large, 16, false)
            }
            else -> {
                if (size32 < 8 || pos + size32 > source.size) return null
                BoxHeader(size32, 8, false)
            }
        }
    }

    private class JpegHead(val xmps: List<String>, val make: String?, val sosContent: Long?)

    private fun parseJpegHead(head: ByteArray): JpegHead {
        val xmps = ArrayList<String>()
        var make: String? = null
        var pos = 2
        var sos: Long? = null
        while (pos + 3 < head.size) {
            if (head[pos] != 0xFF.toByte()) break
            while (pos < head.size && head[pos] == 0xFF.toByte()) pos++
            if (pos >= head.size) break
            val marker = head[pos].toInt() and 0xFF
            pos++
            if (marker == 0xDA) {
                if (pos + 2 > head.size) break
                val len = be16(head, pos)
                if (len < 2) break
                sos = (pos + len).toLong()
                break
            }
            if (marker == 0xD9) break
            if (marker == 0x01 || marker in 0xD0..0xD8) continue
            if (pos + 2 > head.size) break
            val len = be16(head, pos)
            if (len < 2 || pos + len > head.size) break
            if (marker == 0xE1) {
                val payload = head.copyOfRange(pos + 2, pos + len)
                xmpFromApp1(payload)?.let { xmps += it }
                val exif = exifFields(payload)
                if (make == null) make = exif?.first
                exif?.second?.let { xmps += it }
            }
            pos += len
        }
        return JpegHead(xmps, make, sos)
    }

    private fun xmpFromApp1(payload: ByteArray): String? {
        if (payload.size <= XMP_HEADER.size || !payload.copyOfRange(0, XMP_HEADER.size).contentEquals(XMP_HEADER)) {
            return null
        }
        return payload.copyOfRange(XMP_HEADER.size, payload.size).toString(LATIN1)
    }

    /** Exif APP1：Make（0x010F）和 XMP（0x02BC，vivo 把动态照片 XMP 写在这里）。 */
    private fun exifFields(payload: ByteArray): Pair<String?, String?>? {
        if (payload.size <= EXIF_HEADER.size + 8 || !payload.copyOfRange(0, EXIF_HEADER.size).contentEquals(EXIF_HEADER)) {
            return null
        }
        val tiff = payload.copyOfRange(EXIF_HEADER.size, payload.size)
        if (tiff.size < 8) return null
        val le = tiff[0] == 'I'.code.toByte() && tiff[1] == 'I'.code.toByte()
        val be = tiff[0] == 'M'.code.toByte() && tiff[1] == 'M'.code.toByte()
        if (!le && !be) return null
        fun u16(o: Int): Int = if (le) le16(tiff, o) else be16(tiff, o)
        fun u32(o: Int): Long = if (le) leU32(tiff, o) else beU32(tiff, o)
        val ifd = u32(4).toInt()
        if (ifd < 8 || ifd + 2 > tiff.size) return null
        val count = u16(ifd)
        var make: String? = null
        var xmp: String? = null
        for (i in 0 until count) {
            val entry = ifd + 2 + i * 12
            if (entry + 12 > tiff.size) break
            val tag = u16(entry)
            val type = u16(entry + 2)
            val n = u32(entry + 4)
            val width = typeWidth(type) ?: continue
            if (n > Int.MAX_VALUE / width) continue
            val bytes = (n * width).toInt()
            val at = if (bytes <= 4) entry + 8 else u32(entry + 8).toInt()
            if (at < 0 || bytes < 0 || at + bytes > tiff.size) continue
            when (tag) {
                0x010F -> if (type == 2) make = tiff.copyOfRange(at, at + bytes).toString(LATIN1).substringBefore('\u0000').trim()
                0x02BC -> xmp = tiff.copyOfRange(at, at + bytes).toString(LATIN1)
            }
        }
        return make to xmp
    }

    private fun typeWidth(type: Int): Int? = when (type) {
        1, 2, 7 -> 1
        3 -> 2
        4, 9 -> 4
        5, 10 -> 8
        else -> null
    }

    private fun named(xml: String, pattern: Regex): String? {
        val match = pattern.find(xml) ?: return null
        val raw = match.groupValues[1].ifEmpty { match.groupValues[2] }
        return raw.trim().ifEmpty { null }
    }

    private data class DirItem(val semantic: String, val length: Long?)

    private fun directoryItems(xml: String): List<DirItem> {
        val semantics = SEMANTIC.findAll(xml).map { match ->
            val semantic = match.groupValues[1].ifEmpty { match.groupValues[2] }.trim()
            match.range.first to semantic
        }.filter { it.second.isNotEmpty() }.toList()
        return semantics.mapIndexed { index, (at, semantic) ->
            val end = semantics.getOrNull(index + 1)?.first ?: xml.length
            val window = xml.substring(at, end)
            val length = LENGTH.find(window)?.let { found ->
                found.groupValues[1].ifEmpty { found.groupValues[2] }.toLong()
            }
            DirItem(semantic, length)
        }
    }

    /** 没有 Directory 元素时，整段 XMP 就是一份目录（属性形式）。 */
    private fun directoryBlocks(xml: String): List<String> {
        val blocks = DIRECTORY_BLOCK.findAll(xml).map { it.groupValues[1] }.filter { it.isNotEmpty() }.toList()
        return blocks.ifEmpty { listOf(xml) }
    }

    private fun googleOffset(xml: String, itemsXml: String, size: Long): Long? {
        if (named(xml, motionPhotoValue) != "1") return null
        val items = directoryItems(itemsXml)
        val start = items.indexOfFirst { it.semantic == "MotionPhoto" }
        if (start < 0) return null
        var sum = 0L
        for (item in items.subList(start, items.size)) {
            val length = item.length ?: return null
            if (length < 0) return null
            sum += length
        }
        if (sum <= 0 || sum > size) return null
        return size - sum
    }

    private fun microOffset(xml: String, size: Long): Long? {
        if (named(xml, microVideoValue) != "1") return null
        val length = named(xml, microOffsetValue)?.toLongOrNull() ?: return null
        if (length <= 0 || length > size) return null
        return size - length
    }

    private fun isOppo(xml: String): Boolean {
        if (xml.contains("ns.oplus.com/photo/1.0/camera/") || xml.contains("ns.oppo.com/photo/1.0/camera/")) {
            return true
        }
        return named(xml, oliveVersionValue) != null
    }

    private fun oppoOffset(xml: String, size: Long): Long? {
        val length = named(xml, videoLengthValue)?.toLongOrNull() ?: return null
        if (length <= 0 || length > size) return null
        return size - length
    }

    private fun samsungOffset(source: RandomBytes): Long? {
        if (source.size < 8) return null
        val tail = source.read(source.size - 8, 8)
        if (!tail.copyOfRange(4, 8).contentEquals(SEFT)) return null
        val back = leU32(tail, 0)
        val sefh = source.size - 8 - back
        if (sefh < 0 || sefh + 12 > source.size) return null
        val header = source.read(sefh, 12)
        if (!header.copyOfRange(0, 4).contentEquals(SEFH)) return null
        val count = leU32(header, 8)
        if (count <= 0 || count > 64) return null
        val indexBytes = count * 12
        if (sefh + 12 + indexBytes > source.size - 8) return null
        val index = source.read(sefh + 12, indexBytes.toInt())
        for (i in 0 until count.toInt()) {
            val at = i * 12
            val type = le16(index, at + 2)
            if (type != 0x0A30) continue
            val backOff = leU32(index, at + 4)
            val recordLen = leU32(index, at + 8)
            val dataPos = sefh - backOff
            if (dataPos < 0 || recordLen < 12 || dataPos + recordLen > source.size) continue
            // 常见布局是 2 字节 + 类型 + key 长度 + "MotionPhoto_Data" + MP4，不一定有 mpv2/mpv3 头。
            // 在记录前 64 字节里找 ftyp，起点 = 命中处 − 4。有 mpv2 头时 ftyp 在头后面，同样命中。
            val prefixLen = minOf(recordLen, 64L).toInt()
            val prefix = source.read(dataPos, prefixLen)
            val ftypAt = indexOf(prefix, FTYP)
            if (ftypAt < 4) continue
            val videoAt = dataPos + ftypAt - 4
            if (videoAt + 8 <= source.size) return videoAt
        }
        return null
    }

    private fun honorOffset(source: RandomBytes): Long? {
        if (source.size < 20) return null
        val text = source.read(source.size - 20, 20).toString(LATIN1).trimEnd(' ', '\u0000')
        val length = liveFooter.matchEntire(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        if (length <= 0) return null
        val offset = source.size - 20 - length
        if (offset < 0) return null
        return offset
    }

    private fun findEoi(source: RandomBytes, start: Long): Long? {
        var pos = start
        while (pos < source.size) {
            val len = minOf(SCAN_WINDOW.toLong(), source.size - pos).toInt()
            if (len <= 0) return null
            val buf = source.read(pos, len)
            var i = 0
            while (i < buf.size) {
                if ((buf[i].toInt() and 0xFF) != 0xFF) {
                    i++
                    continue
                }
                if (i + 1 >= buf.size) {
                    if (pos + i + 1 >= source.size) return null
                    pos += i
                    i = -1
                    break
                }
                val next = buf[i + 1].toInt() and 0xFF
                if (next == 0xD9) return pos + i + 2
                i += if (next == 0xFF) 1 else 2
            }
            if (i >= 0) pos += len
        }
        return null
    }

    private fun scanFtyp(source: RandomBytes, start: Long): MotionPhotoVideo? {
        var pos = start
        while (pos + 8 <= source.size) {
            val len = minOf(SCAN_WINDOW.toLong(), source.size - pos).toInt()
            if (len < 8) return null
            val buf = source.read(pos, len)
            var from = 0
            while (from <= buf.size - 4) {
                val at = indexOf(buf, FTYP, from)
                if (at < 0) break
                if (at >= 4) {
                    validate(source, pos + at - 4)?.let { return it }
                }
                from = at + 1
            }
            if (pos + len >= source.size) break
            pos += len - 8
        }
        return null
    }

    private fun printable(type: ByteArray): Boolean = type.size == 4 && type.all { it in 0x20..0x7E }

    private fun indexOf(data: ByteArray, needle: ByteArray, start: Int = 0): Int {
        if (needle.isEmpty() || start > data.size - needle.size) return -1
        outer@ for (i in start..data.size - needle.size) {
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun be16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun beU32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or
            ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or
            (b[o + 3].toLong() and 0xFF)

    private fun beU64(b: ByteArray, o: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (b[o + i].toLong() and 0xFF)
        return value
    }

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun leU32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or
            ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or
            ((b[o + 3].toLong() and 0xFF) shl 24)
}

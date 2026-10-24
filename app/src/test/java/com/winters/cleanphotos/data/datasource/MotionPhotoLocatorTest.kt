package com.winters.cleanphotos.data.datasource

import com.winters.cleanphotos.domain.model.MotionPhotoVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.Charset

/**
 * 动态照片识别。每个分支用合成字节，不读真实文件。
 */
class MotionPhotoLocatorTest {

    private val latin1: Charset = Charsets.ISO_8859_1

    @Test
    fun `Google 属性形式：Padding 不计入，MotionPhoto 及其后的 Length 相加，非法尾巴截掉`() {
        val mp4 = mp4()
        val trailer = oversizeBox("uuid")
        val xml = """
            <rdf:Description Camera:MotionPhoto="1">
              <Container:Directory>
                <rdf:li Item:Semantic="Primary" Item:Length="0" Item:Padding="8"/>
                <rdf:li Item:Semantic="MotionPhoto" Item:Length="${mp4.size}" Item:Padding="50"/>
                <rdf:li Item:Semantic="GainMap" Item:Length="${trailer.size}"/>
              </Container:Directory>
            </rdf:Description>
        """.trimIndent()
        val file = jpeg(xmpApp1(xml), mp4 + trailer)
        val found = MotionPhotoLocator.locate(ArrayBytes(file))
        assertVideo(file, found, (file.size - mp4.size - trailer.size).toLong(), mp4.size.toLong())
    }

    @Test
    fun `同一份 Google XMP 出现两次时不把 Length 加两遍`() {
        val mp4 = mp4()
        val xml = """
            <rdf:Description Camera:MotionPhoto="1">
              <Container:Directory>
                <rdf:li Item:Semantic="Primary" Item:Length="0"/>
                <rdf:li Item:Semantic="MotionPhoto" Item:Length="${mp4.size}"/>
              </Container:Directory>
            </rdf:Description>
        """.trimIndent()
        val packet = xmpApp1(xml)
        val file = jpeg(packet + packet, mp4)
        val found = MotionPhotoLocator.locate(ArrayBytes(file))
        assertVideo(file, found, (file.size - mp4.size).toLong(), mp4.size.toLong())
    }

    @Test
    fun `Google 元素形式`() {
        val mp4 = mp4()
        val xml = """
            <Container:Item>
              <Camera:MotionPhoto>1</Camera:MotionPhoto>
              <Item:Semantic>MotionPhoto</Item:Semantic>
              <Item:Length>${mp4.size}</Item:Length>
              <Item:Padding>8</Item:Padding>
            </Container:Item>
        """.trimIndent()
        val file = jpeg(xmpApp1(xml), mp4)
        val found = MotionPhotoLocator.locate(ArrayBytes(file))
        assertVideo(file, found, (file.size - mp4.size).toLong(), mp4.size.toLong())
    }

    @Test
    fun `MicroVideo 用文件大小减偏移`() {
        val mp4 = mp4()
        val xml = """GCamera:MicroVideo="1" GCamera:MicroVideoOffset="${mp4.size}""""
        val file = jpeg(xmpApp1(xml), mp4)
        val found = MotionPhotoLocator.locate(ArrayBytes(file))
        assertVideo(file, found, (file.size - mp4.size).toLong(), mp4.size.toLong())
    }

    @Test
    fun `vivo 的 XMP 在 Exif 0x02BC，uuid 越界尾巴不计入 length`() {
        val mp4 = mp4()
        val trailer = oversizeBox("uuid")
        val xml = """
            Camera:MotionPhoto="1"
            Item:Semantic="MotionPhoto" Item:Length="${mp4.size + trailer.size}"
        """.trimIndent()
        val file = jpeg(exifApp1(make = null, xmp = xml), mp4 + trailer)
        val found = MotionPhotoLocator.locate(ArrayBytes(file))
        assertVideo(file, found, (file.size - mp4.size - trailer.size).toLong(), mp4.size.toLong())
    }

    @Test
    fun `OPPO VideoLength 含 jxrs 尾巴时截到最后一个合法 box`() {
        val mp4 = mp4()
        val trailer = oversizeBox("jxrs")
        val xml = """
            xmlns:OpCamera="http://ns.oplus.com/photo/1.0/camera/"
            OpCamera:OLivePhotoVersion="1" OpCamera:VideoLength="${mp4.size + trailer.size}"
        """.trimIndent()
        val file = jpeg(xmpApp1(xml), mp4 + trailer)
        val found = MotionPhotoLocator.locate(ArrayBytes(file))
        assertVideo(file, found, (file.size - mp4.size - trailer.size).toLong(), mp4.size.toLong())
    }

    @Test
    fun `OPPO 标记在但 VideoLength 对不上时，从 EOI 之后扫描 ftyp`() {
        val mp4 = mp4()
        val xml = """
            xmlns:OpCamera="http://ns.oppo.com/photo/1.0/camera/"
            OpCamera:OLivePhotoVersion="1" OpCamera:VideoLength="4"
        """.trimIndent()
        val file = jpeg(xmpApp1(xml), mp4)
        val found = MotionPhotoLocator.locate(ArrayBytes(file))
        assertVideo(file, found, (file.size - mp4.size).toLong(), mp4.size.toLong())
    }

    @Test
    fun `三星 SEFT 跳过 mpv2 头`() {
        val mp4 = mp4()
        val record = cat(
            byteArrayOf(0, 0, 0x30, 0x0A),
            le32(16),
            "MotionPhoto_Data".toByteArray(latin1),
            "mpv2".toByteArray(latin1),
            ByteArray(8),
            mp4,
        )
        val file = jpeg(byteArrayOf(), samsungTail(record))
        val found = MotionPhotoLocator.locate(ArrayBytes(file))
        assertVideo(file, found, (file.size - samsungTail(record).size + record.size - mp4.size).toLong(), mp4.size.toLong())
    }

    @Test
    fun `三星 SEFT 没有 mpv2 头时从记录里的 ftyp 起`() {
        val mp4 = mp4()
        val record = cat(
            byteArrayOf(0, 0),
            byteArrayOf(0x30, 0x0A),
            le32("MotionPhoto_Data".length.toLong()),
            "MotionPhoto_Data".toByteArray(latin1),
            mp4,
        )
        val file = jpeg(byteArrayOf(), samsungTail(record))
        val found = MotionPhotoLocator.locate(ArrayBytes(file))
        val videoAt = file.size - samsungTail(record).size + record.size - mp4.size
        assertVideo(file, found, videoAt.toLong(), mp4.size.toLong())
    }

    @Test
    fun `荣耀 LIVE_ 尾标`() {
        val mp4 = mp4()
        val footer = "LIVE_${mp4.size}".padEnd(20, ' ').toByteArray(latin1)
        val file = jpeg(byteArrayOf(), mp4 + footer)
        val found = MotionPhotoLocator.locate(ArrayBytes(file))
        assertVideo(file, found, file.size - 20L - mp4.size, mp4.size.toLong())
    }

    @Test
    fun `LIVE_ 推出的起点比 ftyp 晚 20 字节时就近找到，不依赖 Make`() {
        val mp4 = mp4()
        // 同华为样本：视频和尾标之间还有一段 20 字节记录，按 size − 20 − n 算会落进视频里
        val footer = "LIVE_${mp4.size}".padEnd(20, ' ').toByteArray(latin1)
        val file = jpeg(byteArrayOf(), mp4 + ByteArray(20) + footer)
        val found = MotionPhotoLocator.locate(ArrayBytes(file))
        assertVideo(file, found, file.size - 40L - mp4.size, mp4.size.toLong())
    }

    @Test
    fun `HEIC mpvd 的载荷就是视频`() {
        val mp4 = mp4()
        val file = heic(mp4)
        val found = MotionPhotoLocator.locate(ArrayBytes(file))
        val offset = found?.offset ?: error("应识别到 mpvd")
        assertEquals("ftyp", file.copyOfRange((offset + 4).toInt(), (offset + 8).toInt()).toString(latin1))
        assertEquals(mp4.size.toLong(), found.length)
    }

    @Test
    fun `华为 Make 精确匹配时才在 EOI 之后扫描`() {
        val mp4 = mp4()
        val file = jpeg(exifApp1(make = "Huawei", xmp = null), mp4)
        val found = MotionPhotoLocator.locate(ArrayBytes(file))
        assertVideo(file, found, (file.size - mp4.size).toLong(), mp4.size.toLong())
    }

    @Test
    fun `Make 不是 HUAWEI 或 HONOR 的普通 JPEG 返回 null`() {
        val mp4 = mp4()
        val file = jpeg(exifApp1(make = "Canon", xmp = null), mp4)
        assertNull(MotionPhotoLocator.locate(ArrayBytes(file)))
    }

    @Test
    fun `没有动态照片标记的 JPEG 返回 null`() {
        assertNull(MotionPhotoLocator.locate(ArrayBytes(jpeg(byteArrayOf(), byteArrayOf()))))
    }

    @Test
    fun `偏移不指向 ftyp 返回 null`() {
        val file = jpeg(byteArrayOf(), mp4())
        val xml = """GCamera:MicroVideo="1" GCamera:MicroVideoOffset="${file.size}""""
        val miss = jpeg(xmpApp1(xml), byteArrayOf())
        assertNull(MotionPhotoLocator.locate(ArrayBytes(miss)))
    }

    @Test
    fun `moov 声明的大小越出文件返回 null`() {
        val ftyp = box("ftyp", "isom".toByteArray(latin1))
        val moov = be32(1000) + "moov".toByteArray(latin1)
        assertNull(MotionPhotoLocator.validate(ArrayBytes(ftyp + moov), 0))
    }

    private fun assertVideo(file: ByteArray, found: MotionPhotoVideo?, offset: Long, length: Long) {
        val video = found ?: error("应识别到视频 offset=$offset")
        assertEquals(offset, video.offset)
        assertEquals(length, video.length)
        val at = offset.toInt()
        assertEquals("ftyp", file.copyOfRange(at + 4, at + 8).toString(latin1))
    }

    private fun mp4(): ByteArray = box("ftyp", "isom".toByteArray(latin1)) + box("moov", byteArrayOf())

    private fun box(type: String, payload: ByteArray): ByteArray = be32((8 + payload.size).toLong()) + type.toByteArray(latin1) + payload

    /** 类型合法但 size 大于剩余字节，校验应停在它之前。 */
    private fun oversizeBox(type: String): ByteArray = be32(0x00FF) + type.toByteArray(latin1)

    private fun samsungTail(record: ByteArray): ByteArray {
        val entry = cat(le16(0), le16(0x0A30), le32(record.size.toLong()), le32(record.size.toLong()))
        val sefh = cat("SEFH".toByteArray(latin1), le32(106), le32(1), entry)
        return cat(record, sefh, le32(sefh.size.toLong()), "SEFT".toByteArray(latin1))
    }

    private fun heic(mp4: ByteArray): ByteArray {
        val ftyp = box("ftyp", "heic".toByteArray(latin1) + ByteArray(4))
        val meta = box("meta", ByteArray(4))
        val mpvd = box("mpvd", mp4)
        return cat(ftyp, meta, mpvd)
    }

    private fun jpeg(segment: ByteArray, after: ByteArray): ByteArray {
        val sos = cat(byteArrayOf(0xFF.toByte(), 0xDA.toByte()), be16(8), ByteArray(6))
        return cat(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte()),
            segment,
            sos,
            byteArrayOf(0x00),
            byteArrayOf(0xFF.toByte(), 0xD9.toByte()),
            after,
        )
    }

    private fun xmpApp1(xml: String): ByteArray {
        val payload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(latin1) + xml.toByteArray(latin1)
        return app1(payload)
    }

    private fun exifApp1(make: String?, xmp: String?): ByteArray = app1(exifPayload(make, xmp))

    private fun app1(payload: ByteArray): ByteArray =
        cat(byteArrayOf(0xFF.toByte(), 0xE1.toByte()), be16(payload.size + 2), payload)

    private fun exifPayload(make: String?, xmp: String?): ByteArray {
        data class Field(val tag: Int, val type: Int, val bytes: ByteArray)
        val fields = buildList {
            if (make != null) add(Field(0x010F, 2, (make + "\u0000").toByteArray(latin1)))
            if (xmp != null) add(Field(0x02BC, 7, xmp.toByteArray(latin1)))
        }
        val dataStart = 8 + 2 + fields.size * 12 + 4
        var cursor = dataStart
        val entries = ArrayList<ByteArray>()
        val blobs = ArrayList<ByteArray>()
        for (field in fields) {
            val inline = if (field.bytes.size <= 4) field.bytes + ByteArray(4 - field.bytes.size) else null
            val value = inline ?: le32(cursor.toLong()).also {
                cursor += field.bytes.size
                blobs += field.bytes
            }
            entries += cat(le16(field.tag), le16(field.type), le32(field.bytes.size.toLong()), value)
        }
        return cat(
            "Exif\u0000\u0000".toByteArray(latin1),
            "II".toByteArray(latin1),
            le16(42),
            le32(8),
            le16(fields.size),
            cat(*entries.toTypedArray()),
            le32(0),
            cat(*blobs.toTypedArray()),
        )
    }

    private fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    private fun be32(v: Long) = byteArrayOf(
        (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte(),
    )

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

    private fun le32(v: Long) = byteArrayOf(
        v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte(),
    )

    private fun cat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }
}

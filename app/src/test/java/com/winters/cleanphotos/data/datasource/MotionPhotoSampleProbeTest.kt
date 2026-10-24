package com.winters.cleanphotos.data.datasource

import org.junit.Test
import java.io.File

/** 本地样本探针。样本在 /tmp，不入库。目录不在时跳过。 */
class MotionPhotoSampleProbeTest {
    @Test
    fun probe() {
        val dir = File("/tmp/task033-motion-samples")
        if (!dir.isDirectory) return
        dir.listFiles()
            .orEmpty()
            .filter { it.isFile }
            .sortedBy { it.name }
            .forEach { file ->
                val bytes = file.readBytes()
                val found = MotionPhotoLocator.locate(ArrayBytes(bytes))
                val mark = if (found == null) {
                    "no"
                } else {
                    val at = found.offset.toInt()
                    val tag = if (at + 8 <= bytes.size) {
                        bytes.copyOfRange(at + 4, at + 8).toString(Charsets.ISO_8859_1)
                    } else {
                        "?"
                    }
                    "offset=${found.offset} length=${found.length} at+4=$tag"
                }
                println("SAMPLE ${file.name} size=${bytes.size} $mark")
            }
    }
}

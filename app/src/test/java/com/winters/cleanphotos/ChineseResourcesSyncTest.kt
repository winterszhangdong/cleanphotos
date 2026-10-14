package com.winters.cleanphotos

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** 繁体中文系统用的 values-b+zh+Hant 必须与 values-zh 内容一致（只差文件头注释）。 */
class ChineseResourcesSyncTest {
    private fun body(path: String): String {
        val f = listOf(File("src/main/res/$path"), File("app/src/main/res/$path")).first { it.exists() }
        return f.readLines().filterNot { it.startsWith("<?xml") || it.trim().startsWith("<!--") }.joinToString("\n")
    }

    @Test
    fun hantStringsMatchZh() {
        assertEquals(body("values-zh/strings.xml"), body("values-b+zh+Hant/strings.xml"))
    }
}

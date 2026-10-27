package com.winters.cleanphotos.data.datasource

import org.junit.Assert.assertEquals
import org.junit.Test

/** Android 8–9 装袋时目标文件夹同名：另起名字，绝不覆盖。 */
class FreeFileNameTest {

    @Test
    fun `没有同名_原样`() {
        assertEquals("IMG_1.jpg", freeFileName("IMG_1.jpg") { false })
    }

    @Test
    fun `同名_加序号放在扩展名前`() {
        val taken = setOf("IMG_1.jpg")
        assertEquals("IMG_1 (1).jpg", freeFileName("IMG_1.jpg") { it in taken })
    }

    @Test
    fun `序号也被占_往后找第一个空的`() {
        val taken = setOf("IMG_1.jpg", "IMG_1 (1).jpg", "IMG_1 (2).jpg")
        assertEquals("IMG_1 (3).jpg", freeFileName("IMG_1.jpg") { it in taken })
    }

    @Test
    fun `没有扩展名或以点开头_整个名字后面加序号`() {
        assertEquals("README (1)", freeFileName("README") { it == "README" })
        assertEquals(".nomedia (1)", freeFileName(".nomedia") { it == ".nomedia" })
    }

    @Test
    fun `多个点_只把最后一段当扩展名`() {
        assertEquals("a.b (1).jpeg", freeFileName("a.b.jpeg") { it == "a.b.jpeg" })
    }
}

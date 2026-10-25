package com.winters.cleanphotos.feature.allphotos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReorderPlacementTest {
    @Test fun `初次和范围变化不做位移_只有排序才记下锚点`() {
        val motion = ReorderMotion(sort = "newest", membership = "all")
        motion.advance("newest", "all", firstVisibleItemIndex = 12)
        assertFalse(motion.place)

        motion.advance("newest", "on-this-day", firstVisibleItemIndex = 40)
        assertFalse(motion.place)
        assertEquals(0, motion.anchor)

        motion.advance("oldest", "on-this-day", firstVisibleItemIndex = 6)
        assertTrue(motion.place)
        assertEquals(6, motion.anchor)

        motion.advance("oldest", "on-this-day", firstVisibleItemIndex = 99)
        assertTrue(motion.place)
        assertEquals(6, motion.anchor)

        motion.advance("oldest", "chat", firstVisibleItemIndex = 3)
        assertFalse(motion.place)
        assertEquals("chat", motion.membership)
        assertEquals("oldest", motion.sort)
    }

    @Test fun `范围和排序同一帧变化时不位移_之后同一排序不再打开`() {
        val motion = ReorderMotion(sort = "newest", membership = "all")
        motion.advance("oldest", "chat", firstVisibleItemIndex = 8)
        assertFalse(motion.place)
        motion.advance("oldest", "chat", firstVisibleItemIndex = 1)
        assertFalse(motion.place)
    }

    @Test fun `排序之后列表又变了_锚点跟到此刻的首个可见项_位移照常`() {
        val motion = ReorderMotion(sort = "newest", membership = "all")
        val sorted = listOf("a")
        motion.advance("oldest", "all", firstVisibleItemIndex = 6, items = sorted)
        assertEquals(6, motion.anchor)
        motion.advance("oldest", "all", firstVisibleItemIndex = 300, items = sorted)
        assertEquals(6, motion.anchor)
        motion.advance("oldest", "all", firstVisibleItemIndex = 300, items = listOf("a"))
        assertTrue(motion.place)
        assertEquals(300, motion.anchor)
    }
}

package com.winters.cleanphotos.feature.contactsheet

import com.winters.cleanphotos.domain.model.PhotoAlbum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** 托盘露 8 只 +「还有 N 个」、本次进入固定位置 + 插队、面板搜索、贴边滚动速度、名签位置。 */
class TrayRulesTest {
    private fun album(path: String, count: Int = 1) = PhotoAlbum(path, path.trimEnd('/').substringAfterLast('/'), count, emptyList())
    private fun albums(n: Int) = (1..n).map { album("Pictures/A$it/", count = 100 - it) }

    @Test
    fun `还有 N 个_N 等于总数减 8_不超过 8 个时没有`() {
        assertEquals(0, TrayRules.moreCount(0))
        assertEquals(0, TrayRules.moreCount(8))
        assertEquals(1, TrayRules.moreCount(9))
        assertEquals(15, TrayRules.moreCount(23))
    }

    @Test
    fun `本次进入位置固定_装过的不往前挪`() {
        val all = albums(10)
        val session = all.map { it.relativePath }
        // 新一轮排序里 A5 跑到了最前（刚装过），托盘照旧
        val fresh = listOf(all[4]) + all.filterNot { it == all[4] }
        val tray = TrayRules.arrange(fresh, session, emptyList())
        assertEquals(session, tray.map { it.relativePath })
        // 已经露在前 8 只里的相册装完不插队
        assertEquals(emptyList<String>(), TrayRules.promote(tray, emptyList(), "Pictures/A5/"))
    }

    @Test
    fun `从面板装进托盘外的相册_插到第一格_第 8 只挤出去`() {
        val all = albums(10)
        val session = all.map { it.relativePath }
        val tray = TrayRules.arrange(all, session, emptyList())
        val promoted = TrayRules.promote(tray, emptyList(), "Pictures/A10/")
        assertEquals(listOf("Pictures/A10/"), promoted)
        val next = TrayRules.arrange(all, session, promoted)
        assertEquals("Pictures/A10/", next.first().relativePath)
        assertEquals((1..7).map { "Pictures/A$it/" }, next.drop(1).take(7).map { it.relativePath })
        // A8 被挤到「还有 N 个」里
        assertTrue(next.take(TrayRules.LIMIT).none { it.relativePath == "Pictures/A8/" })
        // 再从面板装 A8：它回到第一格，A10 往后一格
        val again = TrayRules.promote(next, promoted, "Pictures/A8/")
        assertEquals(listOf("Pictures/A8/", "Pictures/A10/"), again)
        assertEquals(listOf("Pictures/A8/", "Pictures/A10/", "Pictures/A1/"), TrayRules.arrange(all, session, again).take(3).map { it.relativePath })
    }

    @Test
    fun `新出现的相册排最前_不在了的跳过`() {
        val all = albums(3)
        val session = all.map { it.relativePath }
        val withNew = listOf(album("Pictures/新/")) + all.drop(1)
        assertEquals(listOf("Pictures/新/", "Pictures/A2/", "Pictures/A3/"), TrayRules.arrange(withNew, session, emptyList()).map { it.relativePath })
    }

    @Test
    fun `搜索按名字和路径包含_忽略大小写和全半角`() {
        val list = listOf(album("Pictures/秋天的山/"), album("DCIM/Camera/"), album("Pictures/2025 秋天/"), album("Pictures/Screenshots/"))
        assertEquals(list, AlbumSearch.filter(list, "  "))
        assertEquals(listOf("Pictures/秋天的山/", "Pictures/2025 秋天/"), AlbumSearch.filter(list, "秋天").map { it.relativePath })
        assertEquals(listOf("DCIM/Camera/"), AlbumSearch.filter(list, "camera").map { it.relativePath })
        // 全角字母、全角数字
        assertEquals(listOf("DCIM/Camera/"), AlbumSearch.filter(list, "ＣＡＭ").map { it.relativePath })
        assertEquals(listOf("Pictures/2025 秋天/"), AlbumSearch.filter(list, "２０２５").map { it.relativePath })
        // 路径也算
        assertEquals(listOf("DCIM/Camera/"), AlbumSearch.filter(list, "dcim").map { it.relativePath })
        assertEquals(emptyList<PhotoAlbum>(), AlbumSearch.filter(list, "海"))
    }

    @Test
    fun `没有同名时给新建_同名或不合法时不给`() {
        val list = listOf(album("Pictures/秋天的山/"), album("DCIM/Camera/"))
        assertEquals("秋天", AlbumSearch.createName(list, " 秋天 "))
        assertNull(AlbumSearch.createName(list, "camera"))
        assertNull(AlbumSearch.createName(list, ".hidden"))
        assertNull(AlbumSearch.createName(list, "   "))
        assertEquals("ab", AlbumSearch.createName(list, "a/b"))
    }

    @Test
    fun `贴边深度_区外为 0_越靠边越接近 1`() {
        val z = 40f
        assertEquals(0f, EdgeScroll.depth(200f, 0f, 400f, z), 0f)
        assertEquals(-1f, EdgeScroll.depth(0f, 0f, 400f, z), 1e-4f)
        assertEquals(-0.5f, EdgeScroll.depth(20f, 0f, 400f, z), 1e-4f)
        assertEquals(0.5f, EdgeScroll.depth(380f, 0f, 400f, z), 1e-4f)
        assertEquals(1f, EdgeScroll.depth(420f, 0f, 400f, z), 1e-4f)
    }

    @Test
    fun `目标速度等于 600 乘深度平方`() {
        assertEquals(0f, EdgeScroll.targetSpeed(0f), 0f)
        assertEquals(150f, EdgeScroll.targetSpeed(0.5f), 1e-3f)
        assertEquals(600f, EdgeScroll.targetSpeed(1f), 1e-3f)
        assertEquals(-600f, EdgeScroll.targetSpeed(-1f), 1e-3f)
    }

    @Test
    fun `进入贴边区 150ms 后才滚_缓入_离开约 150ms 停`() {
        val s = EdgeScroller()
        var t = 0f
        while (t < 144f) {
            assertEquals(0f, s.step(1f, 16f), 0f)
            t += 16f
        }
        // 过了 150ms 开始滚，第一帧远小于目标速度
        s.step(1f, 16f)
        assertTrue(s.velocity > 0f && s.velocity < 150f)
        repeat(60) { s.step(1f, 16f) }
        assertEquals(600f, s.velocity, 5f)
        // 离开：150ms 内停下
        var stopped = -1f
        var elapsed = 0f
        while (elapsed < 400f) {
            s.step(0f, 16f)
            elapsed += 16f
            if (s.velocity == 0f) {
                stopped = elapsed
                break
            }
        }
        assertTrue("停下用了 $stopped ms", stopped in 0f..160f)
    }

    @Test
    fun `滚到头清零_还在区内继续等着`() {
        val s = EdgeScroller()
        repeat(40) { s.step(1f, 16f) }
        assertTrue(s.velocity > 0f)
        s.hitEnd()
        assertEquals(0f, s.velocity, 0f)
    }

    @Test
    fun `名签下沿取手指上方和袋口扇形上方更高的那个`() {
        // 手指在袋子上：袋子上沿 − 48 更高
        assertEquals(452f, TagPlacement.bottom(fingerY = 616f, envelopeTop = 500f, fingerGap = 56f, fanClear = 48f), 0f)
        // 手指在袋子上方很远：手指 − 56
        assertEquals(344f, TagPlacement.bottom(fingerY = 400f, envelopeTop = 500f, fingerGap = 56f, fanClear = 48f), 0f)
    }

    @Test
    fun `名签左右夹到屏幕边 8_宽度 64 到 160`() {
        assertEquals(150f, TagPlacement.left(centerX = 200f, width = 100f, screenWidth = 360f, margin = 8f), 0f)
        assertEquals(8f, TagPlacement.left(centerX = 20f, width = 100f, screenWidth = 360f, margin = 8f), 0f)
        assertEquals(252f, TagPlacement.left(centerX = 350f, width = 100f, screenWidth = 360f, margin = 8f), 0f)
        assertEquals(64f, TagPlacement.width(30f, 64f, 160f), 0f)
        assertEquals(160f, TagPlacement.width(300f, 64f, 160f), 0f)
    }

    @Test
    fun `照片叠在手指左上方_不出左边`() {
        val p = StackPlacement(androidx.compose.ui.geometry.Offset(-40f, -44f), 40f)
        assertEquals(androidx.compose.ui.geometry.Offset(160f, 556f), p.center(androidx.compose.ui.geometry.Offset(200f, 600f)))
        assertEquals(40f, p.center(androidx.compose.ui.geometry.Offset(30f, 600f)).x, 0f)
    }

    @Test
    fun `面板行时间_今天_几天前_日期`() {
        val zone: ZoneId = ZoneOffset.ofHours(8)
        val now = LocalDate.of(2026, 10, 27).atTime(14, 0).atZone(zone).toInstant().toEpochMilli()
        fun at(d: LocalDate) = d.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        assertNull(LastFiled.of(null, now, zone))
        assertEquals(LastFiled.Today, LastFiled.of(at(LocalDate.of(2026, 10, 27)), now, zone))
        assertEquals(LastFiled.DaysAgo(3), LastFiled.of(at(LocalDate.of(2026, 10, 24)), now, zone))
        assertEquals(LastFiled.On(LocalDate.of(2026, 10, 2)), LastFiled.of(at(LocalDate.of(2026, 10, 2)), now, zone))
    }
}

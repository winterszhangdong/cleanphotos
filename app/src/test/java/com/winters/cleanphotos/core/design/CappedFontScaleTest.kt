package com.winters.cleanphotos.core.design

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** 固定尺寸容器的字体缩放封顶（A-U8）：上限以内原样不动，超出按上限。 */
class CappedFontScaleTest {
    @Test
    fun `上限以内原样返回_同一实例`() {
        val d = Density(2.625f, 1f)
        assertSame(d, cappedDensity(d, FontScaleCaps.HEADER))
        val d2 = Density(2.625f, 1.15f)
        assertSame(d2, cappedDensity(d2, FontScaleCaps.NAV_LABEL))
    }

    @Test
    fun `超出上限按上限_像素密度不变`() {
        val capped = cappedDensity(Density(2.625f, 2f), FontScaleCaps.HEADER)
        assertEquals(1.3f, capped.fontScale, 0f)
        assertEquals(2.625f, capped.density, 0f)
    }
}

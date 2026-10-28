package com.winters.cleanphotos.feature.similar

import org.junit.Assert.assertEquals
import org.junit.Test

class SimilarFilmstripTest {
    @Test
    fun centeredItemDoesNotScroll() {
        assertEquals(0, filmstripScrollDelta(itemOffset = 160, itemSize = 80, viewportStart = 0, viewportEnd = 400))
    }

    @Test
    fun itemRightOfCenterScrollsForward() {
        assertEquals(80, filmstripScrollDelta(itemOffset = 240, itemSize = 80, viewportStart = 0, viewportEnd = 400))
    }

    @Test
    fun itemLeftOfCenterScrollsBackward() {
        assertEquals(-160, filmstripScrollDelta(itemOffset = 0, itemSize = 80, viewportStart = 0, viewportEnd = 400))
    }

    @Test
    fun centerOffsetInsetsTheItem() {
        assertEquals(-160, filmstripCenterOffset(viewportPx = 400, itemPx = 80))
    }

    @Test
    fun centerOffsetStaysPutWhenTheItemFillsTheStrip() {
        assertEquals(0, filmstripCenterOffset(viewportPx = 80, itemPx = 80))
        assertEquals(0, filmstripCenterOffset(viewportPx = 40, itemPx = 80))
        assertEquals(0, filmstripCenterOffset(viewportPx = 0, itemPx = 80))
        assertEquals(0, filmstripCenterOffset(viewportPx = 400, itemPx = 0))
    }
}

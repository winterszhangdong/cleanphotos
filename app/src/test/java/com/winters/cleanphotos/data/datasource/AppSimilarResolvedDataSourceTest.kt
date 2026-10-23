package com.winters.cleanphotos.data.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppSimilarResolvedDataSourceTest {

    @Test
    fun `编码按 id 升序，非法格式直接抛`() {
        assertEquals("1,2,9", AppSimilarResolvedDataSource.encode(setOf(9L, 1L, 2L)))
        assertEquals(setOf(1L, 2L), AppSimilarResolvedDataSource.decode("1,2"))
        assertThrows(IllegalStateException::class.java) {
            AppSimilarResolvedDataSource.decode("1,x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppSimilarResolvedDataSource.decode("2,1")
        }
    }
}

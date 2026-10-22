package com.winters.cleanphotos.data.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaRelativePathTest {
    private val root = "/storage/emulated/0"

    @Test
    fun dataPathBecomesRelativeDirectory() {
        assertEquals(
            "DCIM/Camera/",
            relativePathFromData("$root/DCIM/Camera/VID.mp4", root),
        )
        assertEquals(
            "Pictures/Screenshots/",
            relativePathFromData("$root/Pictures/Screenshots/a.png", "$root/"),
        )
    }

    @Test
    fun fileAtRootOrOutsideRootIsNull() {
        assertNull(relativePathFromData("$root/IMG.jpg", root))
        assertNull(relativePathFromData("/storage/ABCD-1234/DCIM/Camera/a.mp4", root))
        assertNull(relativePathFromData(null, root))
        assertNull(relativePathFromData("", root))
    }

    @Test
    fun prefixMustBeADirectoryBoundary() {
        assertNull(relativePathFromData("/storage/emulated/0-extra/DCIM/a.jpg", root))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyStorageRootThrows() {
        relativePathFromData("$root/DCIM/a.jpg", "")
    }

    @Test
    fun normalizeRelativePath() {
        assertEquals("DCIM/Camera/", normalizeRelativePath("DCIM/Camera/"))
        assertEquals("DCIM/Camera/", normalizeRelativePath("DCIM/Camera"))
        assertNull(normalizeRelativePath(null))
        assertNull(normalizeRelativePath(""))
        assertNull(normalizeRelativePath("   "))
    }
}

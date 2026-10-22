package com.winters.cleanphotos.core.permission

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyTest {
    @Test
    fun photoPermissionIgnoresVideo() {
        val images = Manifest.permission.READ_MEDIA_IMAGES
        val video = Manifest.permission.READ_MEDIA_VIDEO
        val partial = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        assertTrue(isPhotoPermissionGranted(33) { it == images })
        assertFalse(isPhotoPermissionGranted(33) { it == video })
        assertTrue(isPhotoPermissionGranted(34) { it == partial })
        assertTrue(isPhotoPermissionGranted(26) { it == Manifest.permission.READ_EXTERNAL_STORAGE })
    }

    @Test
    fun fullAccessOn13RequiresImageAndVideo() {
        val images = Manifest.permission.READ_MEDIA_IMAGES
        val video = Manifest.permission.READ_MEDIA_VIDEO
        val partial = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        assertFalse(isFullPhotoAccess(33) { it == images })
        assertFalse(isFullPhotoAccess(34) { it == images || it == partial })
        assertTrue(isFullPhotoAccess(33) { it == images || it == video })
        assertTrue(isFullPhotoAccess(34) { it == images || it == video })
        assertTrue(isFullPhotoAccess(26) { it == Manifest.permission.READ_EXTERNAL_STORAGE })
        assertFalse(isFullPhotoAccess(32) { false })
    }

    @Test
    fun requestPermissionsIncludeVideoFrom13() {
        assertEquals(
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            permissionsToRequest(32).toList(),
        )
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO),
            permissionsToRequest(Build.VERSION_CODES.TIRAMISU).toList(),
        )
        assertEquals(
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ),
            permissionsToRequest(Build.VERSION_CODES.UPSIDE_DOWN_CAKE).toList(),
        )
    }

    @Test
    fun missingVideoOnlyWhenImagesGranted() {
        val images = Manifest.permission.READ_MEDIA_IMAGES
        val video = Manifest.permission.READ_MEDIA_VIDEO
        assertEquals(listOf(video), missingVideoPermissions(33) { it == images }?.toList())
        assertEquals(listOf(video), missingVideoPermissions(34) { it == images }?.toList())
        assertNull(missingVideoPermissions(33) { it == images || it == video })
        assertNull(missingVideoPermissions(33) { false })
        assertNull(missingVideoPermissions(32) { it == Manifest.permission.READ_EXTERNAL_STORAGE })
    }

    @Test
    fun videoReadableWithVideoPermissionOrPartialOrLegacyStorage() {
        val video = Manifest.permission.READ_MEDIA_VIDEO
        val partial = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        assertTrue(isVideoReadable(33) { it == video })
        assertFalse(isVideoReadable(33) { it == Manifest.permission.READ_MEDIA_IMAGES })
        assertTrue(isVideoReadable(34) { it == partial })
        assertTrue(isVideoReadable(28) { it == Manifest.permission.READ_EXTERNAL_STORAGE })
        assertFalse(isVideoReadable(28) { false })
    }
}

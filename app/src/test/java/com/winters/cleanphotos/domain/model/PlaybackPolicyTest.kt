package com.winters.cleanphotos.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPolicyTest {

    @Test
    fun `视频在系统动画开时自动播并且循环`() {
        val policy = playbackPolicy(PlaybackKind.Video, animationsEnabled = true)
        assertTrue(policy.autoplay)
        assertTrue(policy.loop)
    }

    @Test
    fun `视频在系统动画关时不自动播但仍循环`() {
        val policy = playbackPolicy(PlaybackKind.Video, animationsEnabled = false)
        assertFalse(policy.autoplay)
        assertTrue(policy.loop)
    }

    @Test
    fun `动态照片在系统动画开时自动播一次`() {
        val policy = playbackPolicy(PlaybackKind.MotionPhoto, animationsEnabled = true)
        assertTrue(policy.autoplay)
        assertFalse(policy.loop)
    }

    @Test
    fun `动态照片在系统动画关时不自动播也不循环`() {
        val policy = playbackPolicy(PlaybackKind.MotionPhoto, animationsEnabled = false)
        assertEquals(PlaybackPolicy(autoplay = false, loop = false), policy)
    }
}

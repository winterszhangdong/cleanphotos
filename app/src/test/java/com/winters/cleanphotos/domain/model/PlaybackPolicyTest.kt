package com.winters.cleanphotos.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPolicyTest {

    @Test
    fun `视频在系统动画开时自动播且不循环`() {
        assertEquals(PlaybackPolicy(autoplay = true), playbackPolicy(PlaybackKind.Video, animationsEnabled = true))
    }

    @Test
    fun `视频在系统动画关时不自动播且不循环`() {
        assertEquals(PlaybackPolicy(autoplay = false), playbackPolicy(PlaybackKind.Video, animationsEnabled = false))
    }

    @Test
    fun `动态照片在系统动画开时自动播一次`() {
        assertEquals(PlaybackPolicy(autoplay = true), playbackPolicy(PlaybackKind.MotionPhoto, animationsEnabled = true))
    }

    @Test
    fun `动态照片在系统动画关时不自动播`() {
        assertEquals(PlaybackPolicy(autoplay = false), playbackPolicy(PlaybackKind.MotionPhoto, animationsEnabled = false))
    }
}

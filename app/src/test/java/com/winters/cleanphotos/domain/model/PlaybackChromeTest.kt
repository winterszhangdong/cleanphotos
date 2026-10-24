package com.winters.cleanphotos.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackChromeTest {

    @Test
    fun `阶段：用户暂停优先，手势暂停仍算播放中`() {
        assertEquals(PlaybackPhase.Playing, playbackPhase(started = true, userPaused = false, ended = false, playing = true))
        assertEquals(
            PlaybackPhase.Playing,
            playbackPhase(started = true, userPaused = false, ended = false, playing = false),
        )
        assertEquals(PlaybackPhase.UserPaused, playbackPhase(started = true, userPaused = true, ended = false, playing = false))
        assertEquals(PlaybackPhase.UserPaused, playbackPhase(started = true, userPaused = true, ended = true, playing = false))
        assertEquals(PlaybackPhase.NotStarted, playbackPhase(started = false, userPaused = false, ended = false, playing = false))
        assertEquals(PlaybackPhase.Ended, playbackPhase(started = true, userPaused = false, ended = true, playing = false))
    }

    @Test
    fun `视频四态乘拖动和离场`() {
        for (phase in PlaybackPhase.entries) {
            val dragging = playbackChrome(isVideo = true, phase = phase, gestureActive = true, pinnedOrExiting = false)
            assertFalse(dragging.showCenter)
            assertEquals(DetailsMode.Hidden, dragging.details)

            val exiting = playbackChrome(isVideo = true, phase = phase, gestureActive = false, pinnedOrExiting = true)
            assertFalse(exiting.showCenter)
            assertEquals(DetailsMode.Hidden, exiting.details)
        }

        val playing = playbackChrome(isVideo = true, phase = PlaybackPhase.Playing, gestureActive = false, pinnedOrExiting = false)
        assertFalse(playing.showCenter)
        assertEquals(DetailsMode.Hidden, playing.details)

        val paused = playbackChrome(isVideo = true, phase = PlaybackPhase.UserPaused, gestureActive = false, pinnedOrExiting = false)
        assertTrue(paused.showCenter)
        assertEquals(DetailsMode.Shown, paused.details)

        val notStarted = playbackChrome(isVideo = true, phase = PlaybackPhase.NotStarted, gestureActive = false, pinnedOrExiting = false)
        assertTrue(notStarted.showCenter)
        assertEquals(DetailsMode.Keep, notStarted.details)

        val ended = playbackChrome(isVideo = true, phase = PlaybackPhase.Ended, gestureActive = false, pinnedOrExiting = false)
        assertTrue(ended.showCenter)
        assertEquals(DetailsMode.Keep, ended.details)
    }

    @Test
    fun `开播延迟不算未开始，中心键不出现`() {
        assertTrue(
            awaitingAutoplay(
                animationsEnabled = true,
                settled = true,
                resumed = true,
                started = false,
                userPaused = false,
                ended = false,
            ),
        )
        assertFalse(
            awaitingAutoplay(
                animationsEnabled = false,
                settled = true,
                resumed = true,
                started = false,
                userPaused = false,
                ended = false,
            ),
        )
        assertFalse(
            awaitingAutoplay(
                animationsEnabled = true,
                settled = false,
                resumed = true,
                started = false,
                userPaused = false,
                ended = false,
            ),
        )
        assertFalse(
            awaitingAutoplay(
                animationsEnabled = true,
                settled = true,
                resumed = false,
                started = false,
                userPaused = false,
                ended = false,
            ),
        )
        assertFalse(
            awaitingAutoplay(
                animationsEnabled = true,
                settled = true,
                resumed = true,
                started = true,
                userPaused = false,
                ended = false,
            ),
        )
        assertFalse(
            awaitingAutoplay(
                animationsEnabled = true,
                settled = true,
                resumed = true,
                started = false,
                userPaused = true,
                ended = false,
            ),
        )
        assertFalse(
            awaitingAutoplay(
                animationsEnabled = true,
                settled = true,
                resumed = true,
                started = false,
                userPaused = false,
                ended = true,
            ),
        )

        val waiting = playbackChrome(
            isVideo = true,
            phase = PlaybackPhase.NotStarted,
            gestureActive = false,
            pinnedOrExiting = false,
            awaitingAutoplay = true,
        )
        assertFalse(waiting.showCenter)
        assertEquals(DetailsMode.Keep, waiting.details)

        val animationsOff = playbackChrome(
            isVideo = true,
            phase = PlaybackPhase.NotStarted,
            gestureActive = false,
            pinnedOrExiting = false,
            awaitingAutoplay = false,
        )
        assertTrue(animationsOff.showCenter)

        val ended = playbackChrome(
            isVideo = true,
            phase = PlaybackPhase.Ended,
            gestureActive = false,
            pinnedOrExiting = false,
            awaitingAutoplay = true,
        )
        assertTrue(ended.showCenter)
    }

    @Test
    fun `播完后拖进度改为停在拖到的位置`() {
        assertEquals(
            UserSeekPose(ended = false, userPaused = true, started = true),
            poseAfterUserSeek(ended = true, userPaused = false, started = true),
        )
        assertEquals(
            UserSeekPose(ended = false, userPaused = true, started = true),
            poseAfterUserSeek(ended = false, userPaused = true, started = true),
        )
        assertEquals(
            UserSeekPose(ended = false, userPaused = false, started = true),
            poseAfterUserSeek(ended = false, userPaused = false, started = true),
        )
    }

    @Test
    fun `静态图和动态照片不画中心键，详情保持页面自己的开关`() {
        val chrome = playbackChrome(isVideo = false, phase = PlaybackPhase.UserPaused, gestureActive = true, pinnedOrExiting = true)
        assertFalse(chrome.showCenter)
        assertEquals(DetailsMode.Keep, chrome.details)
    }

    @Test
    fun `单击在视频页切换播放，在动态照片页切换详情`() {
        assertEquals(ReviewPhotoTap.TogglePlay(showDetails = true), reviewPhotoTap(isVideo = true, playing = true, detailsOpen = false))
        assertEquals(ReviewPhotoTap.TogglePlay(showDetails = false), reviewPhotoTap(isVideo = true, playing = false, detailsOpen = true))
        assertEquals(ReviewPhotoTap.ToggleDetails(showDetails = true), reviewPhotoTap(isVideo = false, playing = true, detailsOpen = false))
        assertEquals(ReviewPhotoTap.ToggleDetails(showDetails = false), reviewPhotoTap(isVideo = false, playing = false, detailsOpen = true))
    }

    @Test
    fun `视频播完回到第 0 帧并显示中心键`() {
        assertEquals(VideoEndedPose(positionMs = 0L, showCenter = true), videoEndedPose())
    }
}

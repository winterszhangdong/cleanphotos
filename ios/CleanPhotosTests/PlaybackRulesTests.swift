import XCTest

final class PlaybackRulesTests: XCTestCase {
    func testPolicyFollowsAnimations() {
        XCTAssertEqual(PlaybackRules.policy(kind: .video, animationsEnabled: true), PlaybackPolicy(autoplay: true))
        XCTAssertEqual(PlaybackRules.policy(kind: .video, animationsEnabled: false), PlaybackPolicy(autoplay: false))
        XCTAssertEqual(PlaybackRules.policy(kind: .motion, animationsEnabled: true), PlaybackPolicy(autoplay: true))
        XCTAssertEqual(PlaybackRules.policy(kind: .motion, animationsEnabled: false), PlaybackPolicy(autoplay: false))
    }

    func testPhaseUserPauseWinsOverGesturePause() {
        XCTAssertEqual(PlaybackRules.phase(started: true, userPaused: false, ended: false, playing: true), .playing)
        XCTAssertEqual(PlaybackRules.phase(started: true, userPaused: false, ended: false, playing: false), .playing)
        XCTAssertEqual(PlaybackRules.phase(started: true, userPaused: true, ended: false, playing: false), .userPaused)
        XCTAssertEqual(PlaybackRules.phase(started: true, userPaused: true, ended: true, playing: false), .userPaused)
        XCTAssertEqual(PlaybackRules.phase(started: false, userPaused: false, ended: false, playing: false), .notStarted)
        XCTAssertEqual(PlaybackRules.phase(started: true, userPaused: false, ended: true, playing: false), .ended)
    }

    func testVideoChromeAcrossPhases() {
        for phase in [PlaybackPhase.playing, .userPaused, .notStarted, .ended] {
            let dragging = PlaybackRules.chrome(isVideo: true, phase: phase, gestureActive: true, pinnedOrExiting: false)
            XCTAssertFalse(dragging.showCenter)
            XCTAssertFalse(dragging.showBack)
            XCTAssertEqual(dragging.details, .hidden)
            let exiting = PlaybackRules.chrome(isVideo: true, phase: phase, gestureActive: false, pinnedOrExiting: true)
            XCTAssertFalse(exiting.showCenter)
            XCTAssertFalse(exiting.showBack)
            XCTAssertEqual(exiting.details, .hidden)
        }

        let playing = PlaybackRules.chrome(isVideo: true, phase: .playing, gestureActive: false, pinnedOrExiting: false)
        XCTAssertFalse(playing.showCenter)
        XCTAssertFalse(playing.showProgress)
        XCTAssertFalse(playing.showPauseIcon)
        XCTAssertFalse(playing.showBack)
        XCTAssertEqual(playing.details, .hidden)

        let paused = PlaybackRules.chrome(isVideo: true, phase: .userPaused, gestureActive: false, pinnedOrExiting: false)
        XCTAssertTrue(paused.showCenter)
        XCTAssertTrue(paused.showProgress)
        XCTAssertFalse(paused.showPauseIcon)
        XCTAssertTrue(paused.showBack)
        XCTAssertEqual(paused.details, .shown)

        let notStarted = PlaybackRules.chrome(isVideo: true, phase: .notStarted, gestureActive: false, pinnedOrExiting: false)
        XCTAssertTrue(notStarted.showCenter)
        XCTAssertTrue(notStarted.showProgress)
        XCTAssertFalse(notStarted.showPauseIcon)
        XCTAssertTrue(notStarted.showBack)
        XCTAssertEqual(notStarted.details, .keep)

        let ended = PlaybackRules.chrome(isVideo: true, phase: .ended, gestureActive: false, pinnedOrExiting: false)
        XCTAssertTrue(ended.showCenter)
        XCTAssertTrue(ended.showProgress)
        XCTAssertFalse(ended.showPauseIcon)
        XCTAssertTrue(ended.showBack)
        XCTAssertEqual(ended.details, .keep)
    }

    func testPlayingTapShowsTransportUntilDrag() {
        let shown = PlaybackRules.chrome(
            isVideo: true, phase: .playing, gestureActive: false, pinnedOrExiting: false, transportShown: true
        )
        XCTAssertTrue(shown.showCenter)
        XCTAssertTrue(shown.showProgress)
        XCTAssertTrue(shown.showPauseIcon)
        XCTAssertTrue(shown.showBack)
        XCTAssertEqual(shown.details, .hidden)

        let dragging = PlaybackRules.chrome(
            isVideo: true, phase: .playing, gestureActive: true, pinnedOrExiting: false, transportShown: true
        )
        XCTAssertFalse(dragging.showCenter)
        XCTAssertFalse(dragging.showProgress)
        XCTAssertEqual(dragging.details, .hidden)
    }

    func testAwaitingAutoplayHidesCenter() {
        XCTAssertTrue(PlaybackRules.awaitingAutoplay(
            animationsEnabled: true, settled: true, resumed: true, started: false, userPaused: false, ended: false
        ))
        XCTAssertFalse(PlaybackRules.awaitingAutoplay(
            animationsEnabled: false, settled: true, resumed: true, started: false, userPaused: false, ended: false
        ))
        XCTAssertFalse(PlaybackRules.awaitingAutoplay(
            animationsEnabled: true, settled: false, resumed: true, started: false, userPaused: false, ended: false
        ))
        XCTAssertFalse(PlaybackRules.awaitingAutoplay(
            animationsEnabled: true, settled: true, resumed: false, started: false, userPaused: false, ended: false
        ))
        XCTAssertFalse(PlaybackRules.awaitingAutoplay(
            animationsEnabled: true, settled: true, resumed: true, started: true, userPaused: false, ended: false
        ))
        XCTAssertFalse(PlaybackRules.awaitingAutoplay(
            animationsEnabled: true, settled: true, resumed: true, started: false, userPaused: true, ended: false
        ))
        XCTAssertFalse(PlaybackRules.awaitingAutoplay(
            animationsEnabled: true, settled: true, resumed: true, started: false, userPaused: false, ended: true
        ))

        let waiting = PlaybackRules.chrome(
            isVideo: true, phase: .notStarted, gestureActive: false, pinnedOrExiting: false, awaitingAutoplay: true
        )
        XCTAssertFalse(waiting.showCenter)
        XCTAssertFalse(waiting.showProgress)
        XCTAssertFalse(waiting.showBack)
        XCTAssertEqual(waiting.details, .keep)

        let animationsOff = PlaybackRules.chrome(
            isVideo: true, phase: .notStarted, gestureActive: false, pinnedOrExiting: false, awaitingAutoplay: false
        )
        XCTAssertTrue(animationsOff.showCenter)
        XCTAssertTrue(animationsOff.showProgress)
        XCTAssertTrue(animationsOff.showBack)

        let ended = PlaybackRules.chrome(
            isVideo: true, phase: .ended, gestureActive: false, pinnedOrExiting: false, awaitingAutoplay: true
        )
        XCTAssertTrue(ended.showCenter)
    }

    func testSeekPoseStartsPlayback() {
        XCTAssertEqual(
            PlaybackRules.poseAfterUserSeek(),
            UserSeekPose(ended: false, userPaused: false, started: true)
        )
    }

    func testStillAndMotionKeepPageDetails() {
        let chrome = PlaybackRules.chrome(isVideo: false, phase: .userPaused, gestureActive: true, pinnedOrExiting: true)
        XCTAssertFalse(chrome.showCenter)
        XCTAssertFalse(chrome.showProgress)
        XCTAssertFalse(chrome.showBack)
        XCTAssertEqual(chrome.details, .keep)
    }

    func testReviewTap() {
        XCTAssertEqual(
            PlaybackRules.reviewPhotoTap(isVideo: true, playing: true, transportShown: false, detailsOpen: false),
            .toggleTransport(true)
        )
        XCTAssertEqual(
            PlaybackRules.reviewPhotoTap(isVideo: true, playing: true, transportShown: true, detailsOpen: true),
            .toggleTransport(false)
        )
        XCTAssertNil(PlaybackRules.reviewPhotoTap(isVideo: true, playing: false, transportShown: false, detailsOpen: false))
        XCTAssertEqual(
            PlaybackRules.reviewPhotoTap(isVideo: false, playing: true, transportShown: false, detailsOpen: false),
            .toggleDetails(true)
        )
        XCTAssertEqual(
            PlaybackRules.reviewPhotoTap(isVideo: false, playing: false, transportShown: false, detailsOpen: true),
            .toggleDetails(false)
        )
    }

    func testTrashTapOnlyTogglesPlayingVideo() {
        XCTAssertEqual(
            PlaybackRules.trashPhotoTap(isVideo: true, playing: true, transportShown: false),
            .toggleTransport(true)
        )
        XCTAssertEqual(
            PlaybackRules.trashPhotoTap(isVideo: true, playing: true, transportShown: true),
            .toggleTransport(false)
        )
        XCTAssertNil(PlaybackRules.trashPhotoTap(isVideo: true, playing: false, transportShown: false))
        XCTAssertNil(PlaybackRules.trashPhotoTap(isVideo: false, playing: true, transportShown: false))
    }

    func testVideoEndedPose() {
        XCTAssertEqual(PlaybackRules.videoEndedPose(), VideoEndedPose(positionMs: 0, showCenter: true))
    }
}

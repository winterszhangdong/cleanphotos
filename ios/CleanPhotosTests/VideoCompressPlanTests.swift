import XCTest

final class VideoCompressPlanTests: XCTestCase {
    func testFittedSizeScales4KAndPortrait() {
        XCTAssertEqual(pair(VideoCompressPlan.fittedSize(width: 3840, height: 2160)), pair((1920, 1080)))
        XCTAssertEqual(pair(VideoCompressPlan.fittedSize(width: 2160, height: 3840)), pair((1080, 1920)))
    }

    func testDoesNotUpscale() {
        XCTAssertEqual(pair(VideoCompressPlan.fittedSize(width: 1280, height: 720)), pair((1280, 720)))
        XCTAssertEqual(pair(VideoCompressPlan.fittedSize(width: 640, height: 360)), pair((640, 360)))
    }

    func testDimensionsRoundDownToEvenAtLeastTwo() {
        XCTAssertEqual(pair(VideoCompressPlan.fittedSize(width: 1919, height: 1080)), pair((1918, 1080)))
        XCTAssertEqual(pair(VideoCompressPlan.fittedSize(width: 100, height: 101)), pair((100, 100)))
        XCTAssertEqual(pair(VideoCompressPlan.fittedSize(width: 3, height: 5)), pair((2, 4)))
    }

    func testFrameRateCapsAt60AndUnknownIs30() {
        XCTAssertEqual(VideoCompressPlan.frameRate(120), 60)
        XCTAssertEqual(VideoCompressPlan.frameRate(24), 24)
        XCTAssertEqual(VideoCompressPlan.frameRate(nil), 30)
        XCTAssertEqual(VideoCompressPlan.frameRate(0), 30)
        let fast = VideoCompressPlan.output(width: 1920, height: 1080, framesPerSecond: 60, byteSize: 80_000_000, duration: 10)
        XCTAssertEqual(fast.framesPerSecond, 60)
        let unknown = VideoCompressPlan.output(width: 1920, height: 1080, framesPerSecond: nil, byteSize: 80_000_000, duration: 10)
        XCTAssertEqual(unknown.framesPerSecond, 30)
    }

    func testBitrateDropsWhenTargetWouldStayAbove85Percent() {
        let high = VideoCompressPlan.output(width: 1920, height: 1080, framesPerSecond: 30, byteSize: 20_000_000, duration: 10)
        XCTAssertEqual(high.videoBitrate, 8_000_000)

        let sixty = VideoCompressPlan.output(width: 1920, height: 1080, framesPerSecond: 60, byteSize: 80_000_000, duration: 10)
        let expected = Int((8_000_000.0 * sqrt(60.0 / 30.0)).rounded())
        XCTAssertEqual(sixty.videoBitrate, expected)

        // 10s、10MB：原总码率 8 Mbps。8 Mbps + 160 kbps 大于它的 85%，降到 8e6 * 0.7 - 160000。
        let reduced = VideoCompressPlan.output(width: 1920, height: 1080, framesPerSecond: 30, byteSize: 10_000_000, duration: 10)
        XCTAssertEqual(reduced.videoBitrate, 5_440_000)

        let floor = VideoCompressPlan.output(width: 1920, height: 1080, framesPerSecond: 30, byteSize: 1_000_000, duration: 10)
        XCTAssertEqual(floor.videoBitrate, 500_000)
    }

    func testSkipOnlyWhenAlreadyInsideTheBoxAndBitrateIsLowEnough() {
        let small = VideoCompressPlan.output(width: 1280, height: 720, framesPerSecond: 30, byteSize: 400_000, duration: 10)
        XCTAssertTrue(VideoCompressPlan.shouldSkip(width: 1280, height: 720, byteSize: 400_000, duration: 10, output: small))

        let heavy = VideoCompressPlan.output(width: 1920, height: 1080, framesPerSecond: 30, byteSize: 20_000_000, duration: 10)
        XCTAssertFalse(VideoCompressPlan.shouldSkip(width: 1920, height: 1080, byteSize: 20_000_000, duration: 10, output: heavy))

        let fourK = VideoCompressPlan.output(width: 3840, height: 2160, framesPerSecond: 30, byteSize: 100_000, duration: 10)
        XCTAssertFalse(VideoCompressPlan.shouldSkip(width: 3840, height: 2160, byteSize: 100_000, duration: 10, output: fourK))
    }

    func testDurationThreshold() {
        XCTAssertTrue(VideoCompressPlan.durationAccepted(original: 10, output: 10.5))
        XCTAssertFalse(VideoCompressPlan.durationAccepted(original: 10, output: 10.5 + 0.001))
        XCTAssertTrue(VideoCompressPlan.durationAccepted(original: 100, output: 103))
        XCTAssertFalse(VideoCompressPlan.durationAccepted(original: 100, output: 103.01))
        XCTAssertTrue(VideoCompressPlan.durationAccepted(original: 2, output: 2.5))
        XCTAssertFalse(VideoCompressPlan.durationAccepted(original: 2, output: 2.51))
    }

    func testOutputFilename() {
        XCTAssertEqual(VideoCompressPlan.outputFilename("IMG_0001.MOV"), "IMG_0001_1080p.mp4")
        XCTAssertEqual(VideoCompressPlan.outputFilename("clip"), "clip_1080p.mp4")
        XCTAssertEqual(VideoCompressPlan.outputFilename(nil), "video_1080p.mp4")
        XCTAssertEqual(VideoCompressPlan.outputFilename("  "), "video_1080p.mp4")
    }

    func testEntryHiddenForLivePhotoAndTrash() {
        XCTAssertTrue(VideoCompressPlan.offersEntry(isVideo: true, isLivePhoto: false, isTrashed: false))
        XCTAssertFalse(VideoCompressPlan.offersEntry(isVideo: false, isLivePhoto: true, isTrashed: false))
        XCTAssertFalse(VideoCompressPlan.offersEntry(isVideo: true, isLivePhoto: false, isTrashed: true))
        XCTAssertFalse(VideoCompressPlan.offersEntry(isVideo: false, isLivePhoto: false, isTrashed: false))
    }

    func testPortraitTransformLandsInsideTheOutputBox() {
        // 1920×1080 缓冲，顺时针转正成 1080×1920。
        let preferred = VideoCompressAffine(a: 0, b: 1, c: -1, d: 0, tx: 1080, ty: 0)
        let display = VideoCompressGeometry.displaySize(naturalWidth: 1920, naturalHeight: 1080, transform: preferred)
        XCTAssertEqual(display.width, 1080, accuracy: 0.001)
        XCTAssertEqual(display.height, 1920, accuracy: 0.001)
        let mapped = VideoCompressGeometry.outputTransform(
            naturalWidth: 1920, naturalHeight: 1080, preferred: preferred, outWidth: 540, outHeight: 960
        )
        let corners = [(0.0, 0.0), (1920.0, 0.0), (0.0, 1080.0), (1920.0, 1080.0)]
        for point in corners {
            let out = mapped.apply(x: point.0, y: point.1)
            XCTAssertGreaterThanOrEqual(out.x, -0.01)
            XCTAssertGreaterThanOrEqual(out.y, -0.01)
            XCTAssertLessThanOrEqual(out.x, 540.01)
            XCTAssertLessThanOrEqual(out.y, 960.01)
        }
    }

    func testIdentityScaleDoesNotMoveTheOrigin() {
        let mapped = VideoCompressGeometry.outputTransform(
            naturalWidth: 3840, naturalHeight: 2160,
            preferred: .identity,
            outWidth: 1920, outHeight: 1080
        )
        XCTAssertEqual(mapped.a, 0.5, accuracy: 0.0001)
        XCTAssertEqual(mapped.d, 0.5, accuracy: 0.0001)
        XCTAssertEqual(mapped.tx, 0, accuracy: 0.0001)
        XCTAssertEqual(mapped.ty, 0, accuracy: 0.0001)
    }

    private func pair(_ size: (width: Int, height: Int)) -> [Int] { [size.width, size.height] }
}

final class VideoCompressJobTests: XCTestCase {
    private func source(favorite: Bool = true, system: Bool = true) -> VideoCompressSource {
        VideoCompressSource(
            id: "old",
            creationDate: Date(timeIntervalSince1970: 1_700_000_000),
            latitude: 35.0,
            longitude: 139.0,
            originalFilename: "IMG_0001.MOV",
            appFavorite: favorite,
            systemFavorite: system
        )
    }

    private func fourK() -> VideoCompressMedia {
        .ready(VideoCompressMediaFacts(width: 3840, height: 2160, framesPerSecond: 30, byteSize: 50_000_000, duration: 10))
    }

    func testSuccessMigratesFavoriteSeenAndTrashesOriginal() async {
        let log = SideEffects()
        let result = await run(media: fourK(), source: source(), log: log) { _ in 10 }
        guard case .success(let id) = result else { return XCTFail("\(result)") }
        XCTAssertEqual(id, "new-id")
        XCTAssertEqual(log.favorites, ["new-id"])
        XCTAssertEqual(log.seen, ["new-id"])
        XCTAssertEqual(log.trashed, ["old"])
        XCTAssertEqual(log.saved?.filename, "IMG_0001_1080p.mp4")
        XCTAssertEqual(log.saved?.systemFavorite, true)
        XCTAssertEqual(log.saved?.latitude, 35)
        XCTAssertEqual(log.saved?.creationDate, Date(timeIntervalSince1970: 1_700_000_000))
        XCTAssertEqual(log.deleted, 1)
        XCTAssertTrue(log.wrote)
    }

    func testNotAppFavoriteDoesNotAddFavoriteButStillMarksSeenAndTrashes() async {
        let log = SideEffects()
        let result = await run(media: fourK(), source: source(favorite: false, system: false), log: log) { _ in 10 }
        guard case .success = result else { return XCTFail("\(result)") }
        XCTAssertTrue(log.favorites.isEmpty)
        XCTAssertEqual(log.seen, ["new-id"])
        XCTAssertEqual(log.trashed, ["old"])
        XCTAssertEqual(log.saved?.systemFavorite, false)
    }

    func testExportFailureDoesNotSaveOrTrash() async {
        let log = SideEffects()
        let result = await run(media: fourK(), source: source(), log: log) { _ in
            throw VideoCompressWriteError.failed
        }
        guard case .failure(.failed) = result else { return XCTFail("\(result)") }
        XCTAssertNil(log.saved)
        XCTAssertTrue(log.favorites.isEmpty)
        XCTAssertTrue(log.seen.isEmpty)
        XCTAssertTrue(log.trashed.isEmpty)
        XCTAssertEqual(log.deleted, 1)
    }

    func testCancelDoesNotSaveOrTrash() async {
        let log = SideEffects()
        let result = await run(media: fourK(), source: source(), log: log, cancelled: true) { _ in 10 }
        guard case .failure(.cancelled) = result else { return XCTFail("\(result)") }
        XCTAssertFalse(log.wrote)
        XCTAssertNil(log.saved)
        XCTAssertTrue(log.trashed.isEmpty)
        XCTAssertEqual(log.deleted, 0)
    }

    func testCancelDuringWriteDoesNotSaveOrTrash() async {
        let log = SideEffects()
        let result = await run(media: fourK(), source: source(), log: log) { _ in
            throw VideoCompressWriteError.cancelled
        }
        guard case .failure(.cancelled) = result else { return XCTFail("\(result)") }
        XCTAssertNil(log.saved)
        XCTAssertTrue(log.trashed.isEmpty)
        XCTAssertEqual(log.deleted, 1)
    }

    func testDurationMismatchDoesNotSaveOrTrash() async {
        let log = SideEffects()
        let result = await run(media: fourK(), source: source(), log: log) { _ in 8 }
        guard case .failure(.durationMismatch) = result else { return XCTFail("\(result)") }
        XCTAssertNil(log.saved)
        XCTAssertTrue(log.favorites.isEmpty)
        XCTAssertTrue(log.seen.isEmpty)
        XCTAssertTrue(log.trashed.isEmpty)
        XCTAssertEqual(log.deleted, 1)
    }

    func testNotOnDeviceDoesNotSaveOrTrash() async {
        let log = SideEffects()
        let result = await run(media: .notOnDevice, source: source(), log: log) { _ in 10 }
        guard case .failure(.notOnDevice) = result else { return XCTFail("\(result)") }
        XCTAssertFalse(log.wrote)
        XCTAssertNil(log.saved)
        XCTAssertTrue(log.trashed.isEmpty)
        XCTAssertEqual(log.deleted, 0)
    }

    func testUnsupportedDoesNotSaveOrTrash() async {
        let log = SideEffects()
        let result = await run(media: .unsupported, source: source(), log: log) { _ in 10 }
        guard case .failure(.unsupported) = result else { return XCTFail("\(result)") }
        XCTAssertFalse(log.wrote)
        XCTAssertNil(log.saved)
        XCTAssertTrue(log.trashed.isEmpty)
    }

    private func run(
        media: VideoCompressMedia,
        source: VideoCompressSource,
        log: SideEffects,
        cancelled: Bool = false,
        write: @escaping (VideoCompressPlan.Output) async throws -> TimeInterval
    ) async -> Result<String, VideoCompressFailure> {
        await VideoCompressJob.run(
            source: source,
            media: media,
            freeBytes: 5_000_000_000,
            makeTemp: { URL(fileURLWithPath: "/tmp/compress-test.mp4") },
            write: { plan, _, _ in
                log.wrote = true
                return try await write(plan)
            },
            save: { request in
                log.saved = request
                return "new-id"
            },
            addFavorite: { log.favorites.append($0) },
            markSeen: { log.seen.append($0) },
            moveToTrash: { log.trashed.append($0) },
            deleteFile: { _ in log.deleted += 1 },
            isCancelled: { cancelled },
            onProgress: { _ in }
        )
    }
}

private final class SideEffects: @unchecked Sendable {
    var wrote = false
    var saved: VideoCompressSaveRequest?
    var favorites: [String] = []
    var seen: [String] = []
    var trashed: [String] = []
    var deleted = 0
}

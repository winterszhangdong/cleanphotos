import XCTest

final class ContactSheetRulesTests: XCTestCase {
    func testColumnsSwitchAfterThirty() {
        XCTAssertEqual(FilmGeometryRules.columns(count: 0), 3)
        XCTAssertEqual(FilmGeometryRules.columns(count: 30), 3)
        XCTAssertEqual(FilmGeometryRules.columns(count: 31), 4)
    }

    func testFrameGeometry() {
        let width = FilmGeometryRules.frameWidth(stripWidth: 358, cols: 3)
        XCTAssertEqual(width, (358 - 16 - 12) / 3, accuracy: 0.001)
        XCTAssertEqual(FilmGeometryRules.frameLeft(stripWidth: 358, cols: 3, col: 0), 8, accuracy: 0.001)
        let height = FilmGeometryRules.stripHeight(stripWidth: 358, cols: 3)
        XCTAssertEqual(height, width * 3 / 4 + 24, accuracy: 0.001)
    }

    func testCoverTiltStaysInRangeAndStable() {
        XCTAssertEqual(FilmGeometryRules.coverTilt(id: "1"), FilmGeometryRules.coverTilt(id: "1"))
        let tilt = FilmGeometryRules.coverTilt(id: "42")
        XCTAssertGreaterThanOrEqual(tilt, -5)
        XCTAssertLessThanOrEqual(tilt, 5)
        // Android: floorMod(7 * 7919, 11) - 5
        let mixed = (7 * 7919) % 11
        XCTAssertEqual(FilmGeometryRules.coverTilt(id: "7"), CGFloat(mixed - 5))
    }

    func testEdgePrint() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        var parts = DateComponents()
        parts.year = 2026
        parts.month = 10
        parts.day = 24
        let date = calendar.date(from: parts)
        XCTAssertEqual(FilmGeometryRules.edgePrint(date: date, calendar: calendar), "CP 400 · 10.24")
        XCTAssertEqual(FilmGeometryRules.edgePrint(date: nil), "CP 400")
    }

    func testPenPathsMatchTheThreeHandDrawnLoops() {
        XCTAssertEqual(FilmGeometryRules.penPaths.count, 3)
        XCTAssertEqual(FilmGeometryRules.penPaths[0].start, CGPoint(x: 64, y: 7))
        XCTAssertEqual(FilmGeometryRules.penPaths[0].cubics.last?.end, CGPoint(x: 88, y: 16))
        XCTAssertEqual(FilmGeometryRules.penPath(for: "2").start, FilmGeometryRules.penPaths[2].start)
    }

    func testEdgeDepthAndSpeed() {
        let z = 40.0
        XCTAssertEqual(EdgeScroll.depth(pos: 200, start: 0, end: 400, zone: z), 0, accuracy: 1e-4)
        XCTAssertEqual(EdgeScroll.depth(pos: 0, start: 0, end: 400, zone: z), -1, accuracy: 1e-4)
        XCTAssertEqual(EdgeScroll.depth(pos: 20, start: 0, end: 400, zone: z), -0.5, accuracy: 1e-4)
        XCTAssertEqual(EdgeScroll.depth(pos: 380, start: 0, end: 400, zone: z), 0.5, accuracy: 1e-4)
        XCTAssertEqual(EdgeScroll.depth(pos: 420, start: 0, end: 400, zone: z), 1, accuracy: 1e-4)
        XCTAssertEqual(EdgeScroll.targetSpeed(depth: 0), 0, accuracy: 1e-4)
        XCTAssertEqual(EdgeScroll.targetSpeed(depth: 0.5), 150, accuracy: 1e-3)
        XCTAssertEqual(EdgeScroll.targetSpeed(depth: 1), 600, accuracy: 1e-3)
        XCTAssertEqual(EdgeScroll.targetSpeed(depth: -1), -600, accuracy: 1e-3)
    }

    func testEdgeScrollerRampsAfterDelayAndStops() {
        let scroller = EdgeScroller()
        var t = 0.0
        while t < 144 {
            XCTAssertEqual(scroller.step(depth: 1, dtMs: 16), 0, accuracy: 0.001)
            t += 16
        }
        _ = scroller.step(depth: 1, dtMs: 16)
        XCTAssertGreaterThan(scroller.velocity, 0)
        XCTAssertLessThan(scroller.velocity, 150)
        for _ in 0..<60 { _ = scroller.step(depth: 1, dtMs: 16) }
        XCTAssertEqual(scroller.velocity, 600, accuracy: 5)
        var stopped = -1.0
        var elapsed = 0.0
        while elapsed < 400 {
            _ = scroller.step(depth: 0, dtMs: 16)
            elapsed += 16
            if scroller.velocity == 0 {
                stopped = elapsed
                break
            }
        }
        XCTAssertGreaterThan(stopped, 0)
        XCTAssertLessThanOrEqual(stopped, 160)
    }

    func testHitEndClearsVelocity() {
        let scroller = EdgeScroller()
        for _ in 0..<40 { _ = scroller.step(depth: 1, dtMs: 16) }
        XCTAssertGreaterThan(scroller.velocity, 0)
        scroller.hitEnd()
        XCTAssertEqual(scroller.velocity, 0)
    }

    func testTagPlacement() {
        XCTAssertEqual(TagPlacement.bottom(fingerY: 616, envelopeTop: 500, fingerGap: 56, fanClear: 48), 452)
        XCTAssertEqual(TagPlacement.bottom(fingerY: 400, envelopeTop: 500, fingerGap: 56, fanClear: 48), 344)
        XCTAssertEqual(TagPlacement.left(centerX: 200, width: 100, screenWidth: 360, margin: 8), 150)
        XCTAssertEqual(TagPlacement.left(centerX: 20, width: 100, screenWidth: 360, margin: 8), 8)
        XCTAssertEqual(TagPlacement.left(centerX: 350, width: 100, screenWidth: 360, margin: 8), 252)
        let half = TagPlacement.stackHalfHeight(frameW: 117, frameH: 88, scale: 0.66, maxRotationDeg: 12, badgeHalf: 10)
        XCTAssertEqual(half, 39.04, accuracy: 0.01)
        XCTAssertEqual(TagPlacement.stackHalfHeight(frameW: 200, frameH: 88, scale: 0.66, maxRotationDeg: 12, badgeHalf: 10), 42.12, accuracy: 0.01)
        let gap = TagPlacement.fingerGap(lift: 36, stackHalfHeight: half, gap: 8)
        XCTAssertEqual(gap, 83.04, accuracy: 0.01)
        XCTAssertEqual(TagPlacement.bottom(fingerY: 552, envelopeTop: 500, fingerGap: gap, fanClear: 48), 452)
    }

    func testSheetPrintsTakesNewestThree() {
        let recent = [
            SheetPrintRef(id: "1", taken: 10),
            SheetPrintRef(id: "2", taken: 50),
            SheetPrintRef(id: "3", taken: 30),
            SheetPrintRef(id: "4", taken: 40),
            SheetPrintRef(id: "5", taken: 20),
        ]
        XCTAssertEqual(SheetPrints.of(recent).map(\.id), ["2", "4", "3"])
        XCTAssertEqual(SheetPrints.of([SheetPrintRef(id: "6", taken: 1), SheetPrintRef(id: "7", taken: 2)]).map(\.id), ["7", "6"])
        XCTAssertEqual(SheetPrints.of([SheetPrintRef(id: "6", taken: 1)]).map(\.id), ["6"])
        XCTAssertTrue(SheetPrints.of([]).isEmpty)
        let ties = [
            SheetPrintRef(id: "1", taken: nil),
            SheetPrintRef(id: "2", taken: 100),
            SheetPrintRef(id: "3", taken: 100),
            SheetPrintRef(id: "4", taken: 5),
        ]
        XCTAssertEqual(SheetPrints.of(ties).map(\.id), ["3", "2", "4"])
    }

    func testHeldEnvelopeStaysPutUntilRelease() {
        let old = HeldAlbumSnap(id: "cat", count: 9, coverId: "1")
        let after = HeldAlbumSnap(id: "cat", count: 10, coverId: "9")
        XCTAssertEqual(HeldEnvelope.shown(album: after, picked: old, inFlight: nil), old)
        XCTAssertEqual(HeldEnvelope.shown(album: after, picked: nil, inFlight: old).count, 9)
        XCTAssertEqual(HeldEnvelope.shown(album: after, picked: nil, inFlight: nil), after)
        let trip = HeldAlbumSnap(id: "trip", count: 5, coverId: nil)
        XCTAssertEqual(HeldEnvelope.shown(album: trip, picked: old, inFlight: nil), trip)
        XCTAssertEqual(HeldEnvelope.shown(album: trip, picked: nil, inFlight: old), trip)
    }

    func testLiftProgressPinsBothEnds() {
        for i in 0...8 {
            XCTAssertEqual(FilingLiftRules.liftProgress(lift: 0, index: i, duration: 0.240), 0)
            XCTAssertEqual(FilingLiftRules.liftProgress(lift: 1, index: i, duration: 0.240), 1)
        }
        let mid = (0...4).map { FilingLiftRules.liftProgress(lift: 0.5, index: $0, duration: 0.240) }
        XCTAssertTrue(zip(mid, mid.dropFirst()).allSatisfy { $0 > $1 })
        XCTAssertEqual(
            FilingLiftRules.liftProgress(lift: 0.5, index: 4, duration: 0.240),
            FilingLiftRules.liftProgress(lift: 0.5, index: 20, duration: 0.240)
        )
        XCTAssertEqual(FilingLiftRules.slotAlphaFor(returning: false, t: 1), 0)
        XCTAssertEqual(FilingLiftRules.slotAlphaFor(returning: true, t: FilingLiftRules.landHandoff), 0)
        XCTAssertEqual(FilingLiftRules.slotAlphaFor(returning: true, t: FilingLiftRules.landHandoff / 2), 0.5, accuracy: 1e-5)
        XCTAssertEqual(FilingLiftRules.slotAlphaFor(returning: true, t: 0), 1)
    }
}

import XCTest

final class ScrollChromeTests: XCTestCase {
    private let chrome: CGFloat = 96

    func testCollapseFollowsTheFingerUntilItHitsAnEnd() {
        XCTAssertEqual(ScrollChrome.collapseWhileMoving(current: 0, delta: 40, scrollY: 40, chromeHeight: chrome, forceOpen: false), 40)
        XCTAssertEqual(ScrollChrome.collapseWhileMoving(current: 80, delta: 40, scrollY: 200, chromeHeight: chrome, forceOpen: false), chrome)
        XCTAssertEqual(ScrollChrome.collapseWhileMoving(current: 30, delta: -50, scrollY: 100, chromeHeight: chrome, forceOpen: false), 0)
    }

    func testCollapseStaysOpenAtTheTopAndWhileForced() {
        XCTAssertEqual(ScrollChrome.collapseWhileMoving(current: 40, delta: 20, scrollY: 1, chromeHeight: chrome, forceOpen: false), 0)
        XCTAssertEqual(ScrollChrome.collapseWhileMoving(current: 40, delta: 20, scrollY: 0, chromeHeight: chrome, forceOpen: false), 0)
        XCTAssertEqual(ScrollChrome.collapseWhileMoving(current: 40, delta: 20, scrollY: 400, chromeHeight: chrome, forceOpen: true), 0)
    }

    func testCollapseSnapsToTheNearerEnd() {
        XCTAssertEqual(ScrollChrome.collapseSnap(current: chrome / 2, scrollY: 200, chromeHeight: chrome, forceOpen: false), 0)
        XCTAssertEqual(ScrollChrome.collapseSnap(current: chrome / 2 + 0.5, scrollY: 200, chromeHeight: chrome, forceOpen: false), chrome)
        XCTAssertEqual(ScrollChrome.collapseSnap(current: chrome, scrollY: 1, chromeHeight: chrome, forceOpen: false), 0)
        XCTAssertEqual(ScrollChrome.collapseSnap(current: 80, scrollY: 200, chromeHeight: chrome, forceOpen: true), 0)
    }

    func testStuckDayChangesOnlyWhenThePinCrossesAHeader() {
        let days = [
            ScrollChrome.DayMark(id: "a", headerY: 100, headerHeight: 50, lastY: 200, lastHeight: 40),
            ScrollChrome.DayMark(id: "b", headerY: 400, headerHeight: 50, lastY: 500, lastHeight: 40),
        ]
        XCTAssertNil(ScrollChrome.stuck(pinY: 96, scrollY: 0, days: days))
        let first = ScrollChrome.stuck(pinY: 96, scrollY: 10, days: days)
        XCTAssertEqual(first?.id, "a")
        XCTAssertEqual(first?.y, 96)
        // 还在同一天里滑动：吸住的 id 不变，叠层 y 可以跟着针走。
        let still = ScrollChrome.stuck(pinY: 80, scrollY: 40, days: days)
        XCTAssertEqual(still?.id, first?.id)
        XCTAssertEqual(still?.y, 80)
        XCTAssertEqual(ScrollChrome.stuck(pinY: 96, scrollY: 310, days: days)?.id, "b")
    }

    func testUnmeasuredDaysAreSkipped() {
        let days = [
            ScrollChrome.DayMark(id: "a", headerY: nil, headerHeight: 50, lastY: nil, lastHeight: nil),
            ScrollChrome.DayMark(id: "b", headerY: 40, headerHeight: 50, lastY: nil, lastHeight: nil),
        ]
        XCTAssertEqual(ScrollChrome.stuck(pinY: 96, scrollY: 0, days: days)?.id, "b")
    }

    func testLastDaySlidesOffWithItsLastItem() {
        let days = [
            ScrollChrome.DayMark(id: "a", headerY: 0, headerHeight: 50, lastY: 80, lastHeight: 40),
        ]
        XCTAssertEqual(ScrollChrome.stuck(pinY: 96, scrollY: 0, days: days)?.y, 70)
        XCTAssertNil(ScrollChrome.stuck(pinY: 96, scrollY: 120, days: days))
    }

    func testScrubIndexFollowsTheAnchorThatPassedThePin() {
        let anchors: [CGFloat] = [100, 400, 800]
        XCTAssertEqual(ScrollChrome.scrubIndex(scrollY: 0, pinY: 96, anchors: anchors), 0)
        XCTAssertEqual(ScrollChrome.scrubIndex(scrollY: 4, pinY: 96, anchors: anchors), 0)
        XCTAssertEqual(ScrollChrome.scrubIndex(scrollY: 5, pinY: 96, anchors: anchors), 0)
        XCTAssertEqual(ScrollChrome.scrubIndex(scrollY: 304, pinY: 96, anchors: anchors), 1) // 400 - 304 <= 97
        XCTAssertEqual(ScrollChrome.scrubIndex(scrollY: 704, pinY: 96, anchors: anchors), 2)
    }

    func testScrubThumbMovesByDayNotByPixel() {
        XCTAssertEqual(ScrollChrome.scrubThumbY(index: 0, count: 3, top: 96, bottom: 496, thumbHeight: 36), 96)
        XCTAssertEqual(ScrollChrome.scrubThumbY(index: 2, count: 3, top: 96, bottom: 496, thumbHeight: 36), 496 - 36)
        XCTAssertEqual(ScrollChrome.scrubThumbY(index: 0, count: 1, top: 96, bottom: 400, thumbHeight: 36), 96)
    }

    func testStuckSortedMatchesLinear() {
        XCTAssertNil(ScrollChrome.stuckSorted(pinY: 96, scrollY: 0, days: []))
        let days = [
            ScrollChrome.DayMark(id: "d0", headerY: 96, headerHeight: 50, lastY: 200, lastHeight: 80),
            ScrollChrome.DayMark(id: "d1", headerY: 420, headerHeight: 50, lastY: 600, lastHeight: 80),
            ScrollChrome.DayMark(id: "d2", headerY: 900, headerHeight: 50, lastY: 1080, lastHeight: 80),
        ]
        let scrollYs: [CGFloat] = [0, 1, 120, 324, 325, 804, 805, 950, 1080, 1180]
        for y in scrollYs {
            for pin: CGFloat in [40, 96] {
                XCTAssertEqual(
                    ScrollChrome.stuckSorted(pinY: pin, scrollY: y, days: days),
                    ScrollChrome.stuck(pinY: pin, scrollY: y, days: days),
                    "Mismatch at scrollY=\(y), pinY=\(pin)"
                )
            }
        }
    }

    func testScrubIndexBinarySearchEdgeCases() {
        XCTAssertEqual(ScrollChrome.scrubIndex(scrollY: 100, pinY: 96, anchors: []), 0)
        let anchors: [CGFloat] = [120, 300, 300, 640, 1200]
        XCTAssertEqual(ScrollChrome.scrubIndex(scrollY: 0, pinY: 96, anchors: anchors), 0)
        XCTAssertEqual(ScrollChrome.scrubIndex(scrollY: 23, pinY: 96, anchors: anchors), 0) // 120 - 23 == 97 <= 97
        XCTAssertEqual(ScrollChrome.scrubIndex(scrollY: 202, pinY: 96, anchors: anchors), 0) // 300 - 202 = 98 > 97
        XCTAssertEqual(ScrollChrome.scrubIndex(scrollY: 203, pinY: 96, anchors: anchors), 2) // duplicate 300 -> last index 2
        XCTAssertEqual(ScrollChrome.scrubIndex(scrollY: 2000, pinY: 96, anchors: anchors), 4)
    }
}

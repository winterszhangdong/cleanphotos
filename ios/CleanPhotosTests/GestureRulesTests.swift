import XCTest

final class GestureRulesTests: XCTestCase {
    // MARK: I1 垃圾桶预览邻页

    func testNeighborsStayInsideAfterListShrinks() {
        XCTAssertEqual(TrashPreviewWindow.neighbors(index: 5, count: 6), [4, 5])
        XCTAssertEqual(TrashPreviewWindow.neighbors(index: 5, count: 2), [0, 1])
        XCTAssertEqual(TrashPreviewWindow.neighbors(index: 5, count: 1), [0])
        XCTAssertEqual(TrashPreviewWindow.neighbors(index: 5, count: 0), [])
        XCTAssertEqual(TrashPreviewWindow.neighbors(index: 0, count: 0), [])
        XCTAssertEqual(TrashPreviewWindow.span(index: 5, count: 0, radius: 2), [])
    }

    func testNeighborsForOrdinaryPaging() {
        XCTAssertEqual(TrashPreviewWindow.neighbors(index: 0, count: 6), [0, 1])
        XCTAssertEqual(TrashPreviewWindow.neighbors(index: 3, count: 6), [2, 3, 4])
        XCTAssertEqual(TrashPreviewWindow.clampedIndex(3, count: 6), 3)
        XCTAssertEqual(TrashPreviewWindow.clampedIndex(-1, count: 4), 0)
    }

    // MARK: I2 松手合并最终 translation

    func testFinalSegmentCrossesVerticalThreshold() {
        let slop: CGFloat = 8
        let stale = ReviewGestureEnd.displacement(raw: 108, slop: slop)
        let final = ReviewGestureEnd.displacement(raw: 148, slop: slop)
        XCTAssertEqual(stale, 100)
        XCTAssertEqual(final, 140)
        XCTAssertLessThanOrEqual(abs(stale), 120)
        XCTAssertGreaterThan(abs(final), 120)
        let end = ReviewGestureEnd.finish(pinched: false, axis: .vertical, translation: CGSize(width: 0, height: 148), panBase: .zero)
        XCTAssertEqual(end, .sample)
    }

    func testReverseDragUsesFinalTranslationUnderThreshold() {
        let final = ReviewGestureEnd.displacement(raw: 108, slop: 8)
        XCTAssertEqual(final, 100)
        XCTAssertLessThanOrEqual(abs(final), 120)
        XCTAssertEqual(
            ReviewGestureEnd.finish(pinched: false, axis: .vertical, translation: CGSize(width: 0, height: 108), panBase: .zero),
            .sample)
    }

    func testHorizontalFinalSegmentCrossesPageThreshold() {
        let slop: CGFloat = -8
        let stale = ReviewGestureEnd.displacement(raw: -40, slop: slop)
        let final = ReviewGestureEnd.displacement(raw: -80, slop: slop)
        XCTAssertEqual(stale, -32)
        XCTAssertEqual(final, -72)
        XCTAssertLessThanOrEqual(abs(stale), 48)
        XCTAssertGreaterThan(abs(final), 48)
    }

    func testNilAxisStillSamplesAndPinchDoesNotMovePan() {
        XCTAssertEqual(
            ReviewGestureEnd.finish(pinched: false, axis: nil, translation: CGSize(width: 0, height: 148), panBase: .zero),
            .sample)
        let base = CGSize(width: 12, height: -3)
        let pan = ReviewGestureEnd.finish(pinched: false, axis: .pan, translation: CGSize(width: 30, height: 4), panBase: base)
        XCTAssertEqual(pan, .pan(CGSize(width: 42, height: 1)))
        let pinch = ReviewGestureEnd.finish(pinched: true, axis: .pan, translation: CGSize(width: 30, height: 4), panBase: base)
        XCTAssertEqual(pinch, .pinch(clearPan: true))
        let pinchOnly = ReviewGestureEnd.finish(pinched: true, axis: .vertical, translation: CGSize(width: 0, height: 200), panBase: .zero)
        XCTAssertEqual(pinchOnly, .pinch(clearPan: false))
    }

    // MARK: I3 安全区 fit

    func testClampUsesSafeAreaPhotoNotFullScreen() {
        let screen = CGSize(width: 390, height: 844)
        let fitted = ZoomClamp.fit(pixelWidth: 1170, pixelHeight: 2532, screen: screen, top: 59, bottom: 34)
        XCTAssertEqual(fitted.size.width, 347.027, accuracy: 0.01)
        XCTAssertEqual(fitted.size.height, 751, accuracy: 0.01)
        XCTAssertEqual(fitted.centerShiftY, 12.5, accuracy: 0.01)
        let right = ZoomClamp.offset(proposed: CGSize(width: 9_999, height: 0), zoom: 2, fit: fitted.size, screen: screen, centerShiftY: fitted.centerShiftY)
        let expected = (fitted.size.width * 2 - screen.width) / 2
        XCTAssertEqual(right.width, expected, accuracy: 0.01)
        XCTAssertGreaterThan(abs(right.width - 195), 1, "整屏 fit 会把横向极限算成 195pt")
        let left = ZoomClamp.offset(proposed: CGSize(width: -9_999, height: 0), zoom: 2, fit: fitted.size, screen: screen, centerShiftY: fitted.centerShiftY)
        XCTAssertEqual(left.width, -expected, accuracy: 0.01)
        let limitY = (fitted.size.height * 2 - screen.height) / 2
        let shift = fitted.centerShiftY * 2
        let down = ZoomClamp.offset(proposed: CGSize(width: 0, height: 9_999), zoom: 2, fit: fitted.size, screen: screen, centerShiftY: fitted.centerShiftY)
        let up = ZoomClamp.offset(proposed: CGSize(width: 0, height: -9_999), zoom: 2, fit: fitted.size, screen: screen, centerShiftY: fitted.centerShiftY)
        XCTAssertEqual(down.height, limitY - shift, accuracy: 0.01)
        XCTAssertEqual(up.height, -limitY - shift, accuracy: 0.01)
        let triple = ZoomClamp.offset(proposed: CGSize(width: 9_999, height: 0), zoom: 3, fit: fitted.size, screen: screen, centerShiftY: fitted.centerShiftY)
        XCTAssertEqual(triple.width, (fitted.size.width * 3 - screen.width) / 2, accuracy: 0.01)
        let short = ZoomClamp.offset(proposed: CGSize(width: 40, height: 40), zoom: 1.05, fit: fitted.size, screen: screen, centerShiftY: fitted.centerShiftY)
        XCTAssertEqual(short.height, 0)
        XCTAssertEqual(short.width, 0)
    }

    func testUndersizedAxisCannotPan() {
        let screen = CGSize(width: 390, height: 844)
        let wide = ZoomClamp.fit(pixelWidth: 4000, pixelHeight: 800, screen: screen, top: 59, bottom: 34)
        let moved = ZoomClamp.offset(proposed: CGSize(width: 999, height: 999), zoom: 2, fit: wide.size, screen: screen, centerShiftY: wide.centerShiftY)
        XCTAssertEqual(moved.height, 0)
        XCTAssertGreaterThan(moved.width, 0)
    }

    // MARK: I4 选择范围

    func testPruneKeepsPhotosPastTheDrawnGrid() {
        let scope = Set((0..<20).map { "p\($0)" })
        let drawn = Set((0..<8).map { "p\($0)" })
        let kept = SelectionPrune.remaining(selected: scope, scope: scope)
        XCTAssertEqual(kept, scope)
        XCTAssertEqual(SelectionPrune.remaining(selected: scope, scope: drawn).count, 8)
        XCTAssertEqual(SelectionPrune.remaining(selected: ["p0", "p19", "gone"], scope: scope), ["p0", "p19"])
    }

    // MARK: I5 未读完不打开会话

    func testPartialLibraryDoesNotOpenAndOpenSessionIsNotReplaced() {
        XCTAssertFalse(ReviewOpenGate.allows(partial: true, sessionOpen: false))
        XCTAssertFalse(ReviewOpenGate.allows(partial: true, sessionOpen: true))
        XCTAssertTrue(ReviewOpenGate.allows(partial: false, sessionOpen: false))
        XCTAssertFalse(ReviewOpenGate.allows(partial: false, sessionOpen: true))
    }
}

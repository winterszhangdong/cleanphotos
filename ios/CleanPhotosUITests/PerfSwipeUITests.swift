import XCTest

/// 清理页左右翻页帧时间压测（配合 App 内 FrameMonitor，`-cpfps YES`）。
/// 结果写在 App 沙盒 Library/Caches/cpfps.log，scripts/perf-swipe.sh 取出来。
final class PerfSwipeUITests: XCTestCase {
    private func run(label: String, velocity: CGFloat, count: Int, gap: UInt32) {
        let app = XCUIApplication()
        app.launchArguments += ["-cpfps", "YES", "-cpfpsLabel", label, "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        let win = app.windows.firstMatch
        func pt(_ x: CGFloat, _ y: CGFloat) -> XCUICoordinate { win.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y)) }
        let tile = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "tile-0-")).firstMatch
        XCTAssertTrue(tile.waitForExistence(timeout: 30))
        sleep(2)
        tile.tap(); sleep(2)
        let ok = app.descendants(matching: .any)["tutorial-ok"]
        if ok.waitForExistence(timeout: 2) { ok.tap(); sleep(1) }
        for i in 0..<count {
            // 前 2/3 往后翻，后 1/3 往回翻
            if i < count * 2 / 3 {
                pt(0.85, 0.5).press(forDuration: 0.01, thenDragTo: pt(0.15, 0.5), withVelocity: XCUIGestureVelocity(velocity), thenHoldForDuration: 0)
            } else {
                pt(0.15, 0.5).press(forDuration: 0.01, thenDragTo: pt(0.85, 0.5), withVelocity: XCUIGestureVelocity(velocity), thenHoldForDuration: 0)
            }
            usleep(gap * 1000)
        }
        sleep(1)
        let back = app.descendants(matching: .any)["review-back"]
        if back.exists { back.tap() } else { app.descendants(matching: .any)["summary-back"].firstMatch.tap() }
        sleep(2)
        app.terminate()
    }

    func testFastFlicks() { run(label: "fast", velocity: 2400, count: 30, gap: 450) }
    func testSlowDrags() { run(label: "slow", velocity: 500, count: 15, gap: 400) }
}

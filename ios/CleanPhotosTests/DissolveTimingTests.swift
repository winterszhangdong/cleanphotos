import XCTest

/// 清空溶解时间表：每个可见格子在时钟结束时都要溶解完（之前时钟少走一个步长，最后一格停在半溶解）。
final class DissolveTimingTests: XCTestCase {
    func testClockCoversLastTile() {
        XCTAssertEqual(DissolveTiming.clockEnd(count: 3), 0.38 + 3 * 0.06, accuracy: 1e-9)
        XCTAssertEqual(DissolveTiming.clockEnd(count: 1), 0.38 + 0.06, accuracy: 1e-9)
        XCTAssertEqual(DissolveTiming.clockEnd(count: 0), 0.38, accuracy: 1e-9)
        // 超过一屏只数可见的 15 张
        XCTAssertEqual(DissolveTiming.clockEnd(count: 40), 0.38 + 15 * 0.06, accuracy: 1e-9)
    }

    func testEveryVisibleTileFinishes() {
        for n in 1...30 {
            let end = DissolveTiming.clockEnd(count: n)
            for i in 0..<min(n, DissolveTiming.maxVisible) {
                XCTAssertEqual(DissolveTiming.linearProgress(index: i, clock: end), 1, accuracy: 1e-9, "n=\(n) i=\(i)")
            }
        }
    }

    func testStaggerUnchanged() {
        XCTAssertEqual(DissolveTiming.start(index: 0), 0.06, accuracy: 1e-9)
        XCTAssertEqual(DissolveTiming.linearProgress(index: 0, clock: 0.06), 0, accuracy: 1e-9)
        XCTAssertEqual(DissolveTiming.linearProgress(index: 2, clock: 0.18 + 0.38), 1, accuracy: 1e-9)
    }
}

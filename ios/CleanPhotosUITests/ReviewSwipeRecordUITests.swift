import XCTest

/// 清理页四个方向的真实拖动，供 simctl recordVideo 逐帧对比（与 Android `adb input swipe` 同速）。
/// 每段手势之间停 2s，录屏里用 freezedetect 切段。
final class ReviewSwipeRecordUITests: XCTestCase {
    func testRecordReviewSwipes() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        let tileId = ProcessInfo.processInfo.environment["TILE"] ?? "tile-1-"
        let tilePred = ProcessInfo.processInfo.environment["TILESUFFIX"].map { NSPredicate(format: "identifier BEGINSWITH %@ AND identifier ENDSWITH %@", tileId, $0) } ?? NSPredicate(format: "identifier BEGINSWITH %@", tileId)
        let tile = app.descendants(matching: .any).matching(tilePred).firstMatch
        XCTAssertTrue(tile.waitForExistence(timeout: 15))
        sleep(1)
        // NOOPEN=1：不进清理页，只在主界面录底部导航切换（A/F/T）
        if ProcessInfo.processInfo.environment["NOOPEN"] == nil {
            tile.tap()
            sleep(3)
        }
        let win = app.windows.firstMatch
        func pt(_ x: CGFloat, _ y: CGFloat) -> XCUICoordinate { win.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y)) }
        let mode = ProcessInfo.processInfo.environment["SWIPES"] ?? "lrud"
        for c in mode {
            switch c {
            case "l": pt(0.83, 0.5).press(forDuration: 0.02, thenDragTo: pt(0.23, 0.5), withVelocity: 900, thenHoldForDuration: 0)
            case "r": pt(0.23, 0.5).press(forDuration: 0.02, thenDragTo: pt(0.83, 0.5), withVelocity: 900, thenHoldForDuration: 0)
            case "u": pt(0.5, 0.62).press(forDuration: 0.02, thenDragTo: pt(0.5, 0.31), withVelocity: 450, thenHoldForDuration: 0)
            case "d": pt(0.5, 0.36).press(forDuration: 0.02, thenDragTo: pt(0.5, 0.67), withVelocity: 450, thenHoldForDuration: 0)
            case "h": pt(0.5, 0.36).press(forDuration: 0.02, thenDragTo: pt(0.5, 0.55), withVelocity: 300, thenHoldForDuration: 1.0) // 下滑过阈值后停住
            case "H": pt(0.5, 0.62).press(forDuration: 0.02, thenDragTo: pt(0.5, 0.43), withVelocity: 300, thenHoldForDuration: 1.0) // 上滑过阈值后停住
            case "z": // 点撤回（中文 / 英文）
                // 全屏清理页下面的照片页也挂着同一个撤回条（count == 2），按坐标点清理页那一个
                let b = app.buttons.matching(NSPredicate(format: "label CONTAINS '撤回' OR label CONTAINS 'Undo'"))
                if b.firstMatch.waitForExistence(timeout: 2) {
                    let all = b.allElementsBoundByIndex.filter { $0.isHittable }
                    if let top = all.max(by: { $0.frame.minY > $1.frame.minY }) ?? all.first { top.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
                }
            case "b": // 清理页返回（共享元素收回）
                let b = app.descendants(matching: .any)["review-back"]
                if b.waitForExistence(timeout: 2) { b.tap() }
            case "k": // 教学「知道了」
                let b = app.descendants(matching: .any)["tutorial-ok"]
                if b.waitForExistence(timeout: 2) { b.tap() }
            case "o": // 再点一次起始瓦片（共享元素展开）
                if tile.waitForExistence(timeout: 2) { tile.tap() }
            case "D": pt(0.5, 0.5).doubleTap() // 双击缩放（1× ↔ 2×）
            case "P": win.pinch(withScale: 0.4, velocity: -1.5) // 捏到 1× 以下松手 → 回弹
            case "n": // 小结「下一天」
                let b = app.descendants(matching: .any)["summary-next"]
                if b.waitForExistence(timeout: 2) { b.tap() }
            case "A", "F", "T": // 底部导航：照片 / 收藏 / 垃圾桶
                let key = c == "A" ? "照片" : c == "F" ? "收藏" : "垃圾桶"
                let b = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", key)).firstMatch
                if b.waitForExistence(timeout: 2) { b.tap() }
            default: break
            }
            sleep(2)
        }
    }
}

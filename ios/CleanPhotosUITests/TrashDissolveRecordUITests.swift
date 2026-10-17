import XCTest

/// 清空垃圾桶录屏（#9 溶解 / #10 对话框），与 Android 同节奏：
/// 清理页下滑 3 张 → 垃圾桶 → 清空（对话框）→ 点暗幕取消 → 再清空 → 删除 → 系统确认 → 溶解 → 空状态。
final class TrashDissolveRecordUITests: XCTestCase {
    func testRecordTrashDissolve() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        let win = app.windows.firstMatch
        func pt(_ x: CGFloat, _ y: CGFloat) -> XCUICoordinate { win.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y)) }
        func any(_ id: String) -> XCUIElement { app.descendants(matching: .any)[id] }
        let n = Int(ProcessInfo.processInfo.environment["TRASH_N"] ?? "3") ?? 3
        let tile = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "tile-1-")).firstMatch
        XCTAssertTrue(tile.waitForExistence(timeout: 15))
        if n > 0 {
        sleep(1); tile.tap(); sleep(3)
        if any("tutorial-ok").waitForExistence(timeout: 2) { any("tutorial-ok").tap(); sleep(1) }
        for _ in 0..<n {
            pt(0.5, 0.36).press(forDuration: 0.02, thenDragTo: pt(0.5, 0.67), withVelocity: 450, thenHoldForDuration: 0)
            sleep(2)
        }
        sleep(3)
        if any("review-back").waitForExistence(timeout: 2) { any("review-back").tap() }
        sleep(2)
        }
        let tab = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH 'Trash'")).allElementsBoundByIndex
            .filter { $0.frame.minY > win.frame.height * 0.8 }.first
        tab?.tap()
        sleep(3)
        XCTAssertTrue(any("trash-empty").waitForExistence(timeout: 3))
        any("trash-empty").tap(); sleep(2)
        pt(0.5, 0.85).tap(); sleep(2)                 // 点暗幕关闭
        any("trash-empty").tap(); sleep(2)
        any("dialog-delete").tap()
        let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for l in ["Delete", "删除"] {
            let b = sb.buttons[l]
            if b.waitForExistence(timeout: 4) { b.tap(); break }
        }
        sleep(5)
    }
}

import XCTest

/// 横屏检查（审查 I-U6 / I-U7 / I-U16）：网格、清理页教学、清理页、垃圾桶预览各截一张到 $OUT。
final class LandscapeShotsUITests: XCTestCase {
    func testLandscapeShots() throws {
        let out = ProcessInfo.processInfo.environment["OUT"] ?? "/tmp/cp-land"
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        func shot(_ n: String) { try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: "\(out)/\(n).png")) }
        let tile = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'tile-1-'")).firstMatch
        XCTAssertTrue(tile.waitForExistence(timeout: 15))
        XCUIDevice.shared.orientation = .landscapeLeft
        sleep(2); shot("land-grid")
        tile.tap(); sleep(3); shot("land-tutorial")
        let ok = app.descendants(matching: .any)["tutorial-ok"]
        if ok.waitForExistence(timeout: 2) { ok.tap() }
        sleep(2); shot("land-review")
        // 下滑移入垃圾桶一张，回去打开垃圾桶预览
        let win = app.windows.firstMatch
        win.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3)).press(forDuration: 0.02, thenDragTo: win.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.8)), withVelocity: 450, thenHoldForDuration: 0)
        sleep(2); shot("land-review-undo")
        let back = app.descendants(matching: .any)["review-back"]
        if back.waitForExistence(timeout: 2) { back.tap() }
        sleep(2)
        let trashTab = app.buttons.matching(NSPredicate(format: "label BEGINSWITH '垃圾桶'")).firstMatch
        if trashTab.waitForExistence(timeout: 3) { trashTab.tap() }
        sleep(2); shot("land-trash")
        let tt = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'trash-tile-'")).firstMatch
        if tt.waitForExistence(timeout: 3) { tt.tap() }
        sleep(2); shot("land-trash-preview")
        XCUIDevice.shared.orientation = .portrait
        sleep(2); shot("port-trash-preview")
    }
}

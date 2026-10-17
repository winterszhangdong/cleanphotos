import XCTest

/// #20 读屏巡检：逐屏抓无障碍快照，列出「可交互却没名字」的元素（按钮/图片/开关无 label）。
/// 输出行以 A11Y| 开头，脚本从 xcodebuild 日志里 grep。
final class A11ySweepUITests: XCTestCase {
    var app: XCUIApplication!

    private func sweep(_ screen: String) {
        guard let snap = try? app.snapshot() else { print("A11Y|\(screen)|snapshot failed"); return }
        var total = 0, buttons = 0, bad: [String] = []
        func walk(_ e: XCUIElementSnapshot) {
            total += 1
            let t = e.elementType
            let label = e.label.trimmingCharacters(in: .whitespaces)
            let interactive = [.button, .image, .switch, .slider, .link, .cell].contains(t)
            if t == .button { buttons += 1 }
            if interactive && label.isEmpty && e.frame.width > 1 && e.frame.height > 1 {
                bad.append("\(t.rawValue)#\(e.identifier.isEmpty ? "-" : e.identifier)@\(Int(e.frame.minX)),\(Int(e.frame.minY)) \(Int(e.frame.width))x\(Int(e.frame.height))")
            }
            e.children.forEach(walk)
        }
        walk(snap)
        print("A11Y|\(screen)|elements=\(total) buttons=\(buttons) unlabeled=\(bad.count)")
        bad.prefix(40).forEach { print("A11Y|\(screen)|  \($0)") }
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = screen; shot.lifetime = .keepAlways; add(shot)
    }

    func testSweep() throws {
        app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        let win = app.windows.firstMatch
        func pt(_ x: CGFloat, _ y: CGFloat) -> XCUICoordinate { win.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y)) }
        func any(_ id: String) -> XCUIElement { app.descendants(matching: .any)[id] }
        func tab(_ prefix: String) {
            app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", prefix)).allElementsBoundByIndex
                .filter { $0.frame.minY > win.frame.height * 0.8 }.first?.tap()
            sleep(3)
        }
        if any("perm-allow").waitForExistence(timeout: 3) { sweep("permission"); return }
        let tile = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "tile-1-")).firstMatch
        XCTAssertTrue(tile.waitForExistence(timeout: 15)); sleep(2)
        sweep("grid")
        let tileLabel = tile.label
        print("A11Y|grid|tile-1 label='\(tileLabel)' isButton=\(tile.elementType == .button) selected=\(tile.isSelected)")
        let dd = app.debugDescription.components(separatedBy: "\n")
        for (i, l) in dd.enumerated() where l.contains("tile-1-") { dd[max(0, i - 2)...min(dd.count - 1, i + 4)].forEach { print("A11Y|dump|\($0)") } }
        app.buttons["Sort"].tap(); sleep(1); sweep("grid-sort-menu"); pt(0.5, 0.9).tap(); sleep(1)
        app.buttons["More"].tap(); sleep(1); sweep("grid-more-menu"); pt(0.5, 0.9).tap(); sleep(1)
        tile.press(forDuration: 0.8); sleep(1); sweep("grid-select")
        print("A11Y|grid-select|tile-1 selected=\(tile.isSelected) value=\(String(describing: tile.value))")
        if app.buttons["Exit selection"].exists { app.buttons["Exit selection"].tap() }
        sleep(1)
        tile.tap(); sleep(3)
        if any("tutorial-ok").waitForExistence(timeout: 2) { sweep("review-tutorial"); any("tutorial-ok").tap(); sleep(1) }
        sweep("review")
        if app.buttons["More"].firstMatch.exists { app.buttons["More"].firstMatch.tap(); sleep(1); sweep("review-more-menu"); pt(0.2, 0.9).tap(); sleep(1) }
        for _ in 0..<2 {
            pt(0.5, 0.36).press(forDuration: 0.02, thenDragTo: pt(0.5, 0.67), withVelocity: 450, thenHoldForDuration: 0); sleep(2)
        }
        if any("review-back").waitForExistence(timeout: 2) { any("review-back").tap() }
        sleep(2)
        tab("Favorites"); sweep("favorites")
        tab("Trash"); sweep("trash")
        let tt = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "trash-tile-")).firstMatch
        if tt.exists { tt.tap(); sleep(1); sweep("trash-preview"); if any("trash-preview-back").exists { any("trash-preview-back").tap(); sleep(1) } }
        if any("trash-select").exists { any("trash-select").tap(); sleep(1); tt.tap(); sleep(1); sweep("trash-select"); print("A11Y|trash-select|tile label='\(tt.label)' selected=\(tt.isSelected)"); if any("trash-select-cancel").exists { any("trash-select-cancel").tap(); sleep(1) } }
        if any("trash-empty").exists { any("trash-empty").tap(); sleep(2); sweep("trash-dialog"); pt(0.5, 0.85).tap(); sleep(1) }
    }
}

import XCTest

/// 审计用：按真实用户顺序走一遍所有交互，每一步截图写到 TEST_RUNNER_OUT（宿主机目录）。
/// 不做断言失败中断：找不到控件就记一条 MISSING 继续走，最后把日志写到 OUT/tour.log。
final class AuditTourUITests: XCTestCase {
    var app: XCUIApplication!
    var out = "/tmp/cp-tour"
    var log: [String] = []
    var n = 0

    func note(_ s: String) { log.append(s); NSLog("[cptour] %@", s); try? log.joined(separator: "\n").write(toFile: "\(out)/tour.log", atomically: true, encoding: .utf8) }
    func shot(_ name: String) {
        n += 1
        let path = "\(out)/\(String(format: "%02d", n))-\(name).png"
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: path))
        note("shot \(path)")
    }
    func el(_ label: String) -> XCUIElement {
        let q = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@ OR identifier == %@", label, label))
        return q.firstMatch
    }
    @discardableResult func tap(_ label: String, wait: TimeInterval = 3) -> Bool {
        let e = el(label)
        if e.waitForExistence(timeout: wait) && e.isHittable { e.tap(); note("tap \(label)"); return true }
        if e.exists { e.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap(); note("tap(coord) \(label)"); return true }
        note("MISSING \(label)"); return false
    }
    func pt(_ x: CGFloat, _ y: CGFloat) -> XCUICoordinate { app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y)) }
    func swipe(_ c: Character) {
        switch c {
        case "l": pt(0.83, 0.5).press(forDuration: 0.02, thenDragTo: pt(0.2, 0.5), withVelocity: 1200, thenHoldForDuration: 0)
        case "r": pt(0.2, 0.5).press(forDuration: 0.02, thenDragTo: pt(0.83, 0.5), withVelocity: 1200, thenHoldForDuration: 0)
        case "u": pt(0.5, 0.62).press(forDuration: 0.02, thenDragTo: pt(0.5, 0.3), withVelocity: 700, thenHoldForDuration: 0)
        case "d": pt(0.5, 0.36).press(forDuration: 0.02, thenDragTo: pt(0.5, 0.68), withVelocity: 700, thenHoldForDuration: 0)
        default: break
        }
        usleep(700_000)
    }
    func springboardAllow() {
        let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for l in ["Allow Full Access", "允许完全访问", "Allow Access to All Photos", "Allow", "好", "OK", "Delete", "删除"] {
            let b = sb.buttons[l]
            if b.waitForExistence(timeout: 1.5) { b.tap(); note("springboard \(l)"); return }
        }
        note("springboard: no alert button")
    }
    func dumpLabels(_ tag: String) {
        let d = app.debugDescription
        try? d.write(toFile: "\(out)/tree-\(String(format: "%02d", n))-\(tag).txt", atomically: true, encoding: .utf8)
        note("tree \(tag)")
    }

    func testTour() throws {
        out = ProcessInfo.processInfo.environment["OUT"] ?? out
        try? FileManager.default.createDirectory(atPath: out, withIntermediateDirectories: true)
        defer { try? log.joined(separator: "\n").write(toFile: "\(out)/tour.log", atomically: true, encoding: .utf8) }
        app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        let t0 = Date()
        app.launch()
        // 1. 首次启动无权限 → 授权
        sleep(2); shot("permission")
        note("launch->permission \(Date().timeIntervalSince(t0))s")
        tap("允许访问照片"); springboardAllow()
        let t1 = Date()
        _ = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'tile-'")).firstMatch.waitForExistence(timeout: 20)
        note("grant->first tile \(Date().timeIntervalSince(t1))s")
        shot("grid-first")
        sleep(2); shot("grid"); dumpLabels("grid")
        // 2. 快速滚动
        for _ in 0..<3 { app.swipeUp(velocity: .fast) }
        shot("grid-scrolled")
        for _ in 0..<4 { app.swipeDown(velocity: .fast) }
        sleep(1); shot("grid-top")
        // 3. 进入一天，反复上下滑，总结页，返回
        let tile = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'tile-0-'")).firstMatch
        if tile.waitForExistence(timeout: 3) { tile.tap(); note("open \(tile.identifier)") } else { note("MISSING tile-0") }
        sleep(2); shot("review-static"); dumpLabels("review")
        swipe("l"); shot("review-next"); swipe("r"); shot("review-prev")
        swipe("u"); shot("review-after-fav")
        tap("撤回", wait: 1.5); sleep(1); shot("review-after-undo")
        for (i, c) in "ududlududuu".enumerated() {
            swipe(c)
            shot("review-step\(i+1)-\(c)")
            if el("这一天整理完了").exists { note("summary after \(i+1) gestures"); break }
        }
        sleep(1); shot("review-summary"); dumpLabels("summary")
        if !tap("下一天", wait: 1) { swipe("l") }
        sleep(1); shot("review-next-day")
        if !tap("返回", wait: 2) { tap("回到照片", wait: 1) }
        sleep(1); shot("grid-after-review")
        // 4. 收藏 → 取消收藏
        tap("收藏"); sleep(1); shot("favorites"); dumpLabels("favorites")
        let ftile = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'tile-'")).firstMatch
        if ftile.waitForExistence(timeout: 2) { ftile.tap() } else { pt(0.17, 0.3).tap(); note("fav tile by coord") }
        sleep(2); shot("favorites-review")
        swipe("u"); sleep(1); shot("favorites-after-unfav")
        tap("返回", wait: 2); sleep(1); shot("favorites-after")
        // 5. 垃圾桶 → 恢复 → 清空
        tap("垃圾桶"); sleep(1); shot("trash"); dumpLabels("trash")
        pt(0.17, 0.45).tap(); sleep(1); shot("trash-preview"); dumpLabels("trash-preview")
        tap("移出垃圾桶", wait: 2); sleep(1); shot("trash-after-restore")
        tap("选择"); sleep(1); pt(0.17, 0.45).tap(); pt(0.5, 0.45).tap(); sleep(1); shot("trash-select")
        tap("移出垃圾桶", wait: 2); sleep(1); shot("trash-after-batch-restore")
        tap("撤回", wait: 1.5); sleep(1); shot("trash-after-undo")
        tap("清空"); sleep(1); shot("trash-empty-confirm")
        tap("删除", wait: 2); sleep(1); shot("trash-system-alert"); springboardAllow(); sleep(2); shot("trash-emptied")
        // 6. 多选 / 拖选 → 批量 → 撤回
        tap("照片"); sleep(1)
        pt(0.17, 0.32).press(forDuration: 0.6, thenDragTo: pt(0.83, 0.45), withVelocity: 300, thenHoldForDuration: 0.3)
        sleep(1); shot("grid-multiselect"); dumpLabels("multiselect")
        tap("收藏", wait: 2); sleep(1); shot("grid-batch-fav")
        tap("撤回", wait: 2); sleep(1); shot("grid-batch-undo")
        // 7. 排序 / 筛选
        tap("排序"); sleep(1); shot("menu-sort"); tap("最早优先"); sleep(1); shot("grid-oldest")
        tap("筛选"); sleep(1); shot("menu-filter"); tap("仅未看过"); sleep(1); shot("grid-unseen")
        // 8. 语言双向
        tap("更多"); sleep(1); shot("menu-more"); tap("语言"); sleep(1); shot("language-sheet")
        tap("English"); sleep(1); shot("grid-english"); dumpLabels("english")
        tap("Favorites"); sleep(1); shot("favorites-english"); tap("Trash"); sleep(1); shot("trash-english"); tap("Photos"); sleep(1)
        // 9. 重启持久化（English + 最早优先 + 仅未看过 应保留）
        app.terminate(); app.launch(); sleep(3); shot("relaunch"); dumpLabels("relaunch")
        tap("More"); sleep(1); tap("Language"); sleep(1); tap("简体中文"); sleep(1); shot("grid-back-zh")
        tap("筛选"); tap("全部照片"); tap("排序"); tap("最新优先"); sleep(1); shot("grid-restored")
    }
}

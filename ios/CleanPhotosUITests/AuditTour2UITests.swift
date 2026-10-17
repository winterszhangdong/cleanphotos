import XCTest

/// 审计第二段：小结规则、会话边缘、垃圾桶多选/清空、收藏空状态、全部看过了。
final class AuditTour2UITests: XCTestCase {
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

    func open(_ prefix: String) -> Bool {
        let t = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", prefix)).firstMatch
        if t.waitForExistence(timeout: 3) { t.tap(); note("open \(t.identifier)"); return true }
        note("MISSING \(prefix)"); return false
    }

    func testTour2() throws {
        out = (ProcessInfo.processInfo.environment["OUT"] ?? out)
        try? FileManager.default.createDirectory(atPath: out, withIntermediateDirectories: true)
        app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch(); sleep(2); shot("launch")
        // B. 小结：9/18 这一天 6 张全部上下处理
        if open("tile-0-2026-09-18") {
            sleep(2)
            for (i, c) in "ududuu".enumerated() { swipe(c); shot("sum-step\(i+1)-\(c)") }
            sleep(1); shot("sum-page"); dumpLabels("summary")
            swipe("u"); shot("sum-page-after-up"); swipe("d"); shot("sum-page-after-down")
            if !tap("回到照片", wait: 2) { tap("返回") }
            sleep(1); shot("sum-back-grid")
        }
        // 会话末尾的边缘阻尼：打开最旧那天（列表最后）后继续左滑
        app.swipeUp(velocity: .fast); app.swipeUp(velocity: .fast); app.swipeUp(velocity: .fast); sleep(1)
        let tiles = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'tile-'"))
        let last = tiles.element(boundBy: max(0, tiles.count - 1))
        if last.exists { last.tap(); sleep(2); shot("edge-last-photo")
            pt(0.8, 0.5).press(forDuration: 0.02, thenDragTo: pt(0.25, 0.5), withVelocity: 600, thenHoldForDuration: 0); sleep(1); shot("edge-after-left")
            tap("返回"); sleep(1) }
        app.swipeDown(velocity: .fast); app.swipeDown(velocity: .fast); app.swipeDown(velocity: .fast); app.swipeDown(velocity: .fast)
        // C. 垃圾桶：多选 → 恢复 → 撤回 → 清空
        tap("垃圾桶"); sleep(1); shot("trash"); dumpLabels("trash")
        tap("选择"); sleep(1); shot("trash-select-mode"); dumpLabels("trash-select")
        pt(0.17, 0.34).tap(); pt(0.5, 0.34).tap(); sleep(1); shot("trash-selected-2")
        tap("移出垃圾桶", wait: 2); sleep(1); shot("trash-after-batch-restore")
        tap("撤回", wait: 2); sleep(1); shot("trash-after-undo")
        tap("清空"); sleep(1); shot("trash-empty-confirm"); dumpLabels("confirm")
        tap("删除", wait: 2); sleep(2); shot("trash-system-alert")
        springboardAllow(); sleep(3); shot("trash-emptied")
        // D. 收藏清空 → 空状态
        tap("收藏"); sleep(1); shot("fav")
        if open("tile-") {
            sleep(2)
            for i in 0..<12 { swipe("u"); if i == 0 { shot("fav-unfav-1") } }
            sleep(1); shot("fav-session-end")
            tap("返回", wait: 1); tap("回到照片", wait: 1)
        }
        sleep(1); shot("fav-empty")
        // E. 仅未看过 → 全部翻一遍 → 全部看过了
        tap("照片"); sleep(1); tap("筛选"); tap("仅未看过"); sleep(1); shot("unseen")
        if open("tile-") {
            sleep(2)
            for _ in 0..<30 { swipe("l") }
            shot("unseen-end"); tap("返回", wait: 2); tap("回到照片", wait: 1)
        }
        sleep(2); shot("all-seen"); dumpLabels("all-seen")
        tap("显示全部照片"); sleep(1); shot("show-all")
    }
}

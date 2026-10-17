import XCTest

/// 最终对照：按设计稿 01–17 的状态逐个截图到 $OUT（NN-name-ios.png）。
/// 前提：CP-Audit 刚 seed + 重装（首次启动无权限，教学未看过）。03 / 04（拎起过阈值）在录屏里取帧。
final class FinalShotsUITests: XCTestCase {
    var app: XCUIApplication!
    var out = "/tmp/cp-final"
    var log: [String] = []

    func note(_ s: String) { log.append(s); NSLog("[cpfinal] %@", s); try? log.joined(separator: "\n").write(toFile: "\(out)/final.log", atomically: true, encoding: .utf8) }
    func shot(_ name: String) {
        let path = "\(out)/\(name)-ios.png"
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: path))
        note("shot \(name)")
    }
    func el(_ label: String) -> XCUIElement {
        let exact = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@ OR identifier == %@", label, label)).firstMatch
        if exact.exists { return exact }
        let prefix = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", label)).firstMatch
        return prefix.exists ? prefix : exact
    }
    /// 在屏幕内（预渲染在屏外的下一页小结不算）
    func onScreen(_ id: String) -> Bool {
        let e = app.descendants(matching: .any)[id]
        guard e.exists else { return false }
        let f = e.frame, w = app.windows.firstMatch.frame
        return f.minX >= w.minX - 1 && f.maxX <= w.maxX + 1
    }
    @discardableResult func tap(_ label: String, wait: TimeInterval = 3) -> Bool {
        let e = el(label)
        if e.waitForExistence(timeout: wait) {
            if e.isHittable { e.tap() } else { e.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
            note("tap \(label)"); return true
        }
        note("MISSING \(label)"); return false
    }
    func pt(_ x: CGFloat, _ y: CGFloat) -> XCUICoordinate { app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y)) }
    func swipe(_ c: Character) {
        switch c {
        case "l": pt(0.83, 0.5).press(forDuration: 0.02, thenDragTo: pt(0.2, 0.5), withVelocity: 1200, thenHoldForDuration: 0)
        case "u": pt(0.5, 0.62).press(forDuration: 0.02, thenDragTo: pt(0.5, 0.3), withVelocity: 700, thenHoldForDuration: 0)
        case "d": pt(0.5, 0.36).press(forDuration: 0.02, thenDragTo: pt(0.5, 0.68), withVelocity: 700, thenHoldForDuration: 0)
        default: break
        }
        usleep(800_000)
    }
    func open(_ prefix: String) -> Bool {
        let t = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", prefix)).firstMatch
        if t.waitForExistence(timeout: 4) { t.tap(); note("open \(t.identifier)"); return true }
        note("MISSING \(prefix)"); return false
    }
    func springboardAllow() {
        let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for l in ["Allow Full Access", "允许完全访问", "Allow Access to All Photos", "Allow", "好", "OK"] {
            let b = sb.buttons[l]
            if b.waitForExistence(timeout: 2) { b.tap(); note("springboard \(l)"); return }
        }
        note("springboard: no alert button")
    }
    func back() { if !tap("review-back", wait: 2) { tap("summary-back", wait: 2) }; sleep(1) }

    func testFinalShots() throws {
        out = ProcessInfo.processInfo.environment["OUT"] ?? out
        try? FileManager.default.createDirectory(atPath: out, withIntermediateDirectories: true)
        app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        sleep(2)

        // 14 权限
        if app.descendants(matching: .any)["perm-allow"].waitForExistence(timeout: 5) {
            sleep(3)   // 三张相片显影到满（4.8s 周期的 0.45–0.85 段）
            shot("14-permission")
            tap("perm-allow"); springboardAllow()
        }
        _ = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "tile-0-")).firstMatch.waitForExistence(timeout: 10)
        sleep(3)

        // 01 网格
        shot("01-grid")
        // 10 排序菜单 / 10b 语言 / 15 仅未看过
        tap("排序"); sleep(1); shot("10-menu"); tap("排序"); sleep(1)
        tap("更多"); sleep(1); tap("语言"); sleep(1); shot("10b-language")
        pt(0.5, 0.15).tap(); sleep(1)
        // 10c 多选（长按拖选）
        let t0 = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "tile-0-")).firstMatch
        let t5 = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "tile-5-")).firstMatch
        if t0.waitForExistence(timeout: 3) && t5.exists {
            t0.press(forDuration: 0.6, thenDragTo: t5)
            sleep(1); shot("10c-multiselect")
            tap("select-exit", wait: 2); sleep(1)
        }
        // 07 收藏空状态
        tap("收藏"); sleep(1); shot("07-favorites-empty")
        tap("照片"); sleep(1)

        // 13 教学 → 02 清理页 → 08 双击放大 → 17 横屏
        if open("tile-0-") {
            sleep(2); shot("13-tutorial")
            tap("tutorial-ok"); sleep(1)
            shot("02-review")
            // 05 用：先收藏一张、移走一张
            swipe("u"); swipe("d")
            pt(0.5, 0.5).doubleTap(); sleep(1); shot("08-zoom")
            pt(0.5, 0.5).doubleTap(); sleep(1)
            XCUIDevice.shared.orientation = .landscapeLeft; sleep(2); shot("17-landscape")
            XCUIDevice.shared.orientation = .portrait; sleep(2)
            // 05 小结：其余左滑到小结
            for _ in 0..<12 {
                if onScreen("summary-next") { break }
                swipe("l")
            }
            sleep(2); shot("05-summary")
            back()
        }

        // 09 收藏列表
        tap("收藏"); sleep(1); shot("09-favorites")
        // 06 垃圾桶 / 11 选择 / 12 预览
        tap("垃圾桶"); sleep(1); shot("06-trash")
        tap("trash-select"); sleep(1)
        if open("trash-tile-0") { sleep(1) }
        shot("11-trash-select")
        tap("trash-select-cancel", wait: 2); sleep(1)
        if open("trash-tile-0") { sleep(1); shot("12-trash-preview"); tap("trash-preview-back", wait: 2); sleep(1) }

        // 16 全部看过了：筛「仅未看过」，从第一张开始一直翻（跨天小结点「下一天」），直到会话结束；最多三轮
        tap("照片"); sleep(1)
        tap("筛选"); sleep(1); tap("仅未看过"); sleep(2)
        for _ in 0..<3 {
            guard open("tile-0-") else { break }
            sleep(1)
            for _ in 0..<60 {
                if onScreen("summary-next") { tap("summary-next", wait: 1); sleep(1); continue }
                if !app.descendants(matching: .any)["review-back"].exists && !onScreen("summary-back") { break }
                swipe("l")
            }
            sleep(1)
            if app.descendants(matching: .any)["review-back"].exists || onScreen("summary-back") { back() }
            sleep(1)
        }
        shot("16-all-seen")
        tap("all-seen-show-all", wait: 2); sleep(1)
        shot("15-grid-after")

        // 01b 英文网格
        app.terminate()
        let en = XCUIApplication()
        en.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        en.launch(); app = en
        sleep(4); shot("01b-grid-en")

        // 18 垃圾桶清空 → 溶解 → 空状态（中文）
        app.terminate()
        let zh = XCUIApplication()
        zh.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        zh.launch(); app = zh
        sleep(3)
        let trashTab = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH '垃圾桶'")).allElementsBoundByIndex
            .filter { $0.frame.minY > app.windows.firstMatch.frame.height * 0.8 }.first
        trashTab?.tap(); sleep(2)
        if tap("trash-empty", wait: 3) {
            sleep(1); shot("18a-trash-dialog")
            tap("dialog-delete", wait: 2)
            let sb = XCUIApplication(bundleIdentifier: "com.apple.springboard")
            for l in ["删除", "Delete"] { let b = sb.buttons[l]; if b.waitForExistence(timeout: 4) { b.tap(); note("springboard \(l)"); break } }
            sleep(5); shot("18-trash-empty")
        }

        // 15b 加载中（-cpForceLoading 让骨架常驻）
        app.terminate()
        let ld = XCUIApplication()
        ld.launchArguments += ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN", "-cpForceLoading"]
        ld.launch(); app = ld
        sleep(3); shot("15b-loading")
    }
}

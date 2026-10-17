import XCTest

/// Drives the real long-press → drag-select gesture on the Photos grid and saves screenshots.
/// Output dir: env CLEANPHOTOS_VERIFY_DIR (set via TEST_RUNNER_ prefix) or /tmp.
final class DragSelectUITests: XCTestCase {
    private var outDir: String {
        ProcessInfo.processInfo.environment["CLEANPHOTOS_VERIFY_DIR"] ?? NSTemporaryDirectory()
    }

    private func save(_ name: String) {
        let shot = XCUIScreen.main.screenshot()
        let url = URL(fileURLWithPath: outDir).appendingPathComponent(name)
        try? shot.pngRepresentation.write(to: url)
        let a = XCTAttachment(screenshot: shot); a.name = name; a.lifetime = .keepAlways; add(a)
    }

    func testLongPressDragSelectsRange() throws {
        let app = XCUIApplication()
        addUIInterruptionMonitor(withDescription: "Photos") { alert in
            for label in ["Allow Full Access", "允许完全访问", "Allow Access to All Photos", "OK"] where alert.buttons[label].exists {
                alert.buttons[label].tap(); return true
            }
            return false
        }
        app.launch()

        let tiles = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'tile-'"))
        XCTAssertTrue(tiles.firstMatch.waitForExistence(timeout: 15), "no tiles")
        sleep(1)
        // Accessibility order ≠ grid order; address tiles by their grid index identifier.
        func tile(_ i: Int) -> XCUIElement {
            app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "tile-\(i)-")).firstMatch
        }
        let first = tile(0)
        let target = tile(5)
        let start = first.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        let end = target.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        start.press(forDuration: 0.7, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.4)
        sleep(1)
        save("08-drag-select.png")

        // Header shows the selected count; expect the 6-tile range (first row + 2)
        let six = app.staticTexts["6"]
        XCTAssertTrue(six.waitForExistence(timeout: 2), "expected 6 selected after drag")
    }
}

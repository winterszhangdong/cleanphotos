import XCTest

final class ScopeRulesTests: XCTestCase {
    private var calendar: Calendar = {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(secondsFromGMT: 0)!
        return cal
    }()

    private func date(_ year: Int, _ month: Int, _ day: Int) -> Date {
        calendar.date(from: DateComponents(year: year, month: month, day: day))!
    }

    // MARK: 大文件

    func testLargeFileThreshold() {
        XCTAssertFalse(ScopeRules.isLargeFile(bytes: nil))
        XCTAssertFalse(ScopeRules.isLargeFile(bytes: ScopeRules.largeFileMinBytes - 1))
        XCTAssertTrue(ScopeRules.isLargeFile(bytes: ScopeRules.largeFileMinBytes))
        XCTAssertTrue(ScopeRules.isLargeFile(bytes: ScopeRules.largeFileMinBytes + 1))
        XCTAssertEqual(ScopeRules.largeFileMinBytes, 10 * 1024 * 1024)
    }

    // MARK: 截图 / 聊天

    func testScreenshotNeedlesAndSystemFlag() {
        XCTAssertTrue(ScopeRules.isScreenshot(systemScreenshot: true, albumTitle: nil, filename: nil))
        XCTAssertTrue(ScopeRules.isScreenshot(systemScreenshot: false, albumTitle: "My Screenshots", filename: nil))
        XCTAssertTrue(ScopeRules.isScreenshot(systemScreenshot: false, albumTitle: "截屏", filename: nil))
        XCTAssertTrue(ScopeRules.isScreenshot(systemScreenshot: false, albumTitle: nil, filename: "IMG_截图.PNG"))
        XCTAssertTrue(ScopeRules.filenameLooksLikeScreenshot("ScreenShot_1.jpg"))
        XCTAssertFalse(ScopeRules.isScreenshot(systemScreenshot: false, albumTitle: "Camera", filename: "IMG_0001.JPG"))
        XCTAssertFalse(ScopeRules.isScreenshot(systemScreenshot: false, albumTitle: nil, filename: nil))
    }

    func testChatAlbumTitles() {
        XCTAssertTrue(ScopeRules.isChatAlbumTitle("WeChat"))
        XCTAssertTrue(ScopeRules.isChatAlbumTitle("WeiXin Images"))
        XCTAssertTrue(ScopeRules.isChatAlbumTitle("WhatsApp"))
        XCTAssertTrue(ScopeRules.isChatAlbumTitle("Telegram"))
        XCTAssertTrue(ScopeRules.isChatAlbumTitle("QQ"))
        XCTAssertFalse(ScopeRules.isChatAlbumTitle("Camera"))
        XCTAssertFalse(ScopeRules.isChatAlbumTitle(nil))
    }

    // MARK: 那年今天

    func testOnThisDayMatchesEarlierYearOnly() {
        let today = date(2026, 11, 13)
        XCTAssertTrue(ScopeRules.isOnThisDay(date: date(2020, 11, 13), today: today, calendar: calendar))
        XCTAssertFalse(ScopeRules.isOnThisDay(date: date(2026, 11, 13), today: today, calendar: calendar))
        XCTAssertFalse(ScopeRules.isOnThisDay(date: date(2027, 11, 13), today: today, calendar: calendar))
        XCTAssertFalse(ScopeRules.isOnThisDay(date: date(2020, 11, 14), today: today, calendar: calendar))
        XCTAssertFalse(ScopeRules.isOnThisDay(date: .distantPast, today: today, calendar: calendar))
        XCTAssertFalse(ScopeRules.isOnThisDay(date: Date(timeIntervalSince1970: 0), today: today, calendar: calendar))
    }

    func testLeapDayOnlyMatchesLeapDay() {
        let leap = date(2024, 2, 29)
        XCTAssertFalse(ScopeRules.isOnThisDay(date: leap, today: date(2026, 2, 28), calendar: calendar))
        XCTAssertFalse(ScopeRules.isOnThisDay(date: leap, today: date(2025, 3, 1), calendar: calendar))
        XCTAssertTrue(ScopeRules.isOnThisDay(date: leap, today: date(2028, 2, 29), calendar: calendar))
    }

    // MARK: RAW / 时长 / 角标

    func testRawExtensions() {
        for ext in ["dng", "cr2", "cr3", "nef", "nrw", "arw", "srf", "sr2", "orf", "raf", "pef", "rw2", "srw", "gpr"] {
            XCTAssertTrue(ScopeRules.isRawFileName("IMG.\(ext.uppercased())"), ext)
        }
        XCTAssertFalse(ScopeRules.isRawFileName("IMG.JPG"))
        XCTAssertFalse(ScopeRules.isRawFileName("IMG.png"))
        XCTAssertFalse(ScopeRules.isRawFileName("IMG."))
        XCTAssertFalse(ScopeRules.isRawFileName("IMG"))
        XCTAssertFalse(ScopeRules.isRawFileName(nil))
        XCTAssertEqual(ScopeRules.fileExtension("a.b.CR3"), "cr3")
    }

    func testDurationFormatTruncatesAndUsesHourForm() {
        XCTAssertEqual(ScopeRules.formatDuration(seconds: 0), "0:00")
        XCTAssertEqual(ScopeRules.formatDuration(seconds: 59.9), "0:59")
        XCTAssertEqual(ScopeRules.formatDuration(seconds: 60), "1:00")
        XCTAssertEqual(ScopeRules.formatDuration(seconds: 61), "1:01")
        XCTAssertEqual(ScopeRules.formatDuration(seconds: 3599), "59:59")
        XCTAssertEqual(ScopeRules.formatDuration(seconds: 3600), "1:00:00")
        XCTAssertEqual(ScopeRules.formatDuration(seconds: 3661), "1:01:01")
    }

    func testPhotoMarkLineJoinsRawAndSize() {
        XCTAssertNil(ScopeRules.photoMarkLine(raw: nil, size: nil))
        XCTAssertNil(ScopeRules.photoMarkLine(raw: "", size: ""))
        XCTAssertEqual(ScopeRules.photoMarkLine(raw: "RAW", size: nil), "RAW")
        XCTAssertEqual(ScopeRules.photoMarkLine(raw: nil, size: "12 MB"), "12 MB")
        XCTAssertEqual(ScopeRules.photoMarkLine(raw: "RAW", size: "12 MB"), "RAW · 12 MB")
        XCTAssertEqual(ScopeRules.photoMarkLine(raw: nil, motion: "动态", size: nil), "动态")
        XCTAssertEqual(ScopeRules.photoMarkLine(raw: "RAW", motion: "动态", size: nil), "RAW · 动态")
        XCTAssertEqual(ScopeRules.photoMarkLine(raw: "RAW", motion: "动态", size: "28 MB"), "RAW · 动态 · 28 MB")
    }

    // MARK: 范围刻度与空状态

    func testOnThisDayHiddenWhenEmptyAndNotCurrent() {
        XCTAssertFalse(ScopeRules.visibleScopes(onThisDayCount: 0, current: .all).contains(.onThisDay))
        XCTAssertTrue(ScopeRules.visibleScopes(onThisDayCount: 0, current: .onThisDay).contains(.onThisDay))
        XCTAssertEqual(ScopeRules.visibleScopes(onThisDayCount: 3, current: .all).last, .onThisDay)
        XCTAssertEqual(ScopeRules.visibleScopes(onThisDayCount: 1, current: .all).map(\.rawValue),
                       ["all", "similar", "largeFiles", "screenshots", "chat", "onThisDay"])
    }

    func testPhotosBodyOrder() {
        XCTAssertEqual(ScopeRules.photosBody(scope: .similar, isLoading: true, pageReady: false, unseenOnly: true, visibleCount: 0, scopeCount: 0, totalCount: 10), .similar)
        XCTAssertEqual(ScopeRules.photosBody(scope: .all, isLoading: true, pageReady: false, unseenOnly: false, visibleCount: 0, scopeCount: 0, totalCount: 0), .loading)
        XCTAssertEqual(ScopeRules.photosBody(scope: .largeFiles, isLoading: false, pageReady: false, unseenOnly: false, visibleCount: 0, scopeCount: 0, totalCount: 10), .loading)
        XCTAssertEqual(ScopeRules.photosBody(scope: .all, isLoading: false, pageReady: true, unseenOnly: true, visibleCount: 0, scopeCount: 4, totalCount: 4), .allSeen)
        XCTAssertEqual(ScopeRules.photosBody(scope: .screenshots, isLoading: false, pageReady: true, unseenOnly: false, visibleCount: 0, scopeCount: 0, totalCount: 4), .scopeEmpty)
        XCTAssertEqual(ScopeRules.photosBody(scope: .chat, isLoading: false, pageReady: true, unseenOnly: true, visibleCount: 0, scopeCount: 3, totalCount: 4), .scopeSeen)
        XCTAssertEqual(ScopeRules.photosBody(scope: .all, isLoading: false, pageReady: true, unseenOnly: false, visibleCount: 0, scopeCount: 0, totalCount: 0), .libraryEmpty)
        XCTAssertEqual(ScopeRules.photosBody(scope: .onThisDay, isLoading: false, pageReady: true, unseenOnly: false, visibleCount: 2, scopeCount: 2, totalCount: 9), .grid)
    }

    func testDialCentersAndDragDirection() {
        XCTAssertEqual(ScopeDialMath.alpha(distance: 0), 1, accuracy: 0.001)
        XCTAssertEqual(ScopeDialMath.alpha(distance: 1), 0.45, accuracy: 0.001)
        XCTAssertEqual(ScopeDialMath.alpha(distance: 2), 0.22, accuracy: 0.001)
        let widths: [CGFloat] = [40, 60]
        XCTAssertEqual(ScopeDialMath.center(index: 0, widths: widths, gap: 22), 20, accuracy: 0.001)
        XCTAssertEqual(ScopeDialMath.center(index: 1, widths: widths, gap: 22), 40 + 22 + 30, accuracy: 0.001)
        XCTAssertEqual(ScopeDialMath.dragPages(dx: -50, stride: 50), 1, accuracy: 0.001)
        XCTAssertEqual(ScopeDialMath.translationX(viewportCenter: 100, progress: 0, widths: widths, gap: 22), 80, accuracy: 0.001)
    }

    // MARK: 日期行吸附（Android DayExpandTest）

    func testStickyHidesUntilTheRowPassesThePin() {
        XCTAssertNil(DateStick.stickY(pinY: 200, headerHeight: 50, headerTop: nil, nextHeaderTop: nil, lastItemTop: nil, lastItemHeight: nil, sectionVisible: false))
        XCTAssertNil(DateStick.stickY(pinY: 200, headerHeight: 50, headerTop: 200, nextHeaderTop: nil, lastItemTop: nil, lastItemHeight: nil, sectionVisible: true))
        XCTAssertNil(DateStick.stickY(pinY: 200, headerHeight: 50, headerTop: 240, nextHeaderTop: nil, lastItemTop: 400, lastItemHeight: 100, sectionVisible: true))
        XCTAssertEqual(DateStick.stickY(pinY: 200, headerHeight: 50, headerTop: nil, nextHeaderTop: nil, lastItemTop: nil, lastItemHeight: nil, sectionVisible: true), 200)
        XCTAssertEqual(DateStick.stickY(pinY: 200, headerHeight: 50, headerTop: 40, nextHeaderTop: nil, lastItemTop: nil, lastItemHeight: nil, sectionVisible: true), 200)
        XCTAssertEqual(DateStick.stickY(pinY: 200, headerHeight: 50, headerTop: nil, nextHeaderTop: nil, lastItemTop: 80, lastItemHeight: 120, sectionVisible: true), 150)
        XCTAssertEqual(DateStick.stickY(pinY: 200, headerHeight: 50, headerTop: nil, nextHeaderTop: nil, lastItemTop: -40, lastItemHeight: 80, sectionVisible: true), -10)
        XCTAssertNil(DateStick.stickY(pinY: 200, headerHeight: 50, headerTop: nil, nextHeaderTop: nil, lastItemTop: -80, lastItemHeight: 50, sectionVisible: true))
    }

    func testStickyStaysOnThePinUntilTheNextDayArrives() {
        XCTAssertEqual(DateStick.stickY(pinY: 200, headerHeight: 50, headerTop: nil, nextHeaderTop: 280, lastItemTop: 80, lastItemHeight: 120, sectionVisible: true), 200)
        XCTAssertEqual(DateStick.stickY(pinY: 200, headerHeight: 50, headerTop: nil, nextHeaderTop: 220, lastItemTop: 80, lastItemHeight: 120, sectionVisible: true), 200)
        XCTAssertNil(DateStick.stickY(pinY: 200, headerHeight: 50, headerTop: nil, nextHeaderTop: 200, lastItemTop: 80, lastItemHeight: 120, sectionVisible: true))
        XCTAssertNil(DateStick.stickY(pinY: 200, headerHeight: 50, headerTop: nil, nextHeaderTop: -10, lastItemTop: -80, lastItemHeight: 50, sectionVisible: true))
        XCTAssertEqual(DateStick.stickY(pinY: 200, headerHeight: 50, headerTop: nil, nextHeaderTop: CGFloat.greatestFiniteMagnitude, lastItemTop: 80, lastItemHeight: 120, sectionVisible: true), 200)
    }

    func testInFlowDateHidesOnceItPassesThePin() {
        XCTAssertFalse(DateStick.inFlowDateHidden(headerTop: 200, pinY: 200))
        XCTAssertFalse(DateStick.inFlowDateHidden(headerTop: 240, pinY: 200))
        XCTAssertTrue(DateStick.inFlowDateHidden(headerTop: 199, pinY: 200))
    }
}

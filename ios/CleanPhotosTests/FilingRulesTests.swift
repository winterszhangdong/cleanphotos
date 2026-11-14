import XCTest

final class FilingRulesTests: XCTestCase {
    private func album(_ id: String, _ title: String, count: Int, filed: TimeInterval? = nil) -> FilingAlbumRef {
        FilingAlbumRef(id: id, title: title, count: count, lastFiledAt: filed)
    }

    // MARK: 同名

    func testNormalizeIgnoresCaseWhitespaceAndWidth() {
        XCTAssertEqual(AlbumNameRules.normalize("  Camera "), AlbumNameRules.normalize("camera"))
        XCTAssertEqual(AlbumNameRules.normalize("\u{FF23}\u{FF41}\u{FF4D}\u{FF45}\u{FF52}\u{FF41}"), "camera")
        XCTAssertEqual(AlbumNameRules.normalize("  "), "")
    }

    func testExistingMatchIsCaseAndTrimInsensitive() {
        let albums = [
            album("1", "Camera", count: 3, filed: 10),
            album("2", "猫", count: 8),
        ]
        XCTAssertEqual(AlbumNameRules.existing(name: "  camera ", albums: albums)?.id, "1")
        XCTAssertNil(AlbumNameRules.existing(name: "   ", albums: albums))
        XCTAssertNil(AlbumNameRules.existing(name: "dogs", albums: albums))
    }

    func testExistingPrefersFirstInGivenOrder() {
        let albums = [
            album("new", "Trip", count: 1, filed: 50),
            album("old", "trip", count: 9, filed: 1),
        ]
        XCTAssertEqual(AlbumNameRules.existing(name: "TRIP", albums: albums)?.id, "new")
    }

    func testInvalidNames() {
        XCTAssertFalse(AlbumNameRules.isValid("  "))
        XCTAssertFalse(AlbumNameRules.isValid("."))
        XCTAssertFalse(AlbumNameRules.isValid(".."))
        XCTAssertFalse(AlbumNameRules.isValid(".hidden"))
        XCTAssertFalse(AlbumNameRules.isValid("a/b"))
        XCTAssertFalse(AlbumNameRules.isValid(String(repeating: "a", count: 65)))
        XCTAssertTrue(AlbumNameRules.isValid(" 猫 "))
        XCTAssertEqual(AlbumNameRules.sanitize("a/b:c\n"), "abc")
    }

    // MARK: 托盘

    func testSortByLastFiledThenCountThenName() {
        let albums = [
            album("a", "A", count: 1, filed: 100),
            album("b", "b", count: 50),
            album("c", "c", count: 50),
            album("d", "D", count: 1, filed: 200),
        ]
        XCTAssertEqual(TrayRules.sorted(albums).map(\.id), ["d", "a", "b", "c"])
    }

    func testMoreCountAndVisible() {
        XCTAssertEqual(TrayRules.moreCount(total: 0), 0)
        XCTAssertEqual(TrayRules.moreCount(total: 8), 0)
        XCTAssertEqual(TrayRules.moreCount(total: 9), 1)
        let arranged = (0..<10).map { album("\($0)", "A\($0)", count: $0) }
        XCTAssertEqual(TrayRules.visible(arranged).count, 8)
    }

    func testSessionOrderThenArrange() {
        let original = [album("a", "A", count: 1), album("b", "B", count: 1), album("c", "C", count: 1)]
        let session = TrayRules.sessionOrder(current: nil, albums: original)
        XCTAssertEqual(session, ["a", "b", "c"])
        // 从面板装进原来不在前 8 的 z：先 promote，再 arrange。
        var promoted: [String] = []
        promoted = TrayRules.promote(tray: original, promoted: promoted, id: "z")
        XCTAssertEqual(promoted, ["z"])
        promoted = TrayRules.promote(tray: original, promoted: promoted, id: "a")
        XCTAssertEqual(promoted, ["z"], "已经露在托盘上的不插队")
        let withNew = [album("n", "N", count: 1)] + original + [album("z", "Z", count: 4)]
        let nextSession = TrayRules.sessionOrder(current: session, albums: withNew)
        XCTAssertEqual(nextSession, ["n", "z", "a", "b", "c"])
        let arranged = TrayRules.arrange(albums: withNew, session: nextSession, promoted: promoted)
        XCTAssertEqual(arranged.map(\.id), ["z", "n", "a", "b", "c"])
    }

    func testArrangeSkipsMissingIds() {
        let albums = [album("a", "A", count: 1)]
        XCTAssertEqual(TrayRules.arrange(albums: albums, session: ["gone", "a"], promoted: ["gone"]).map(\.id), ["a"])
    }

    func testEmptySessionStaysNilUntilAlbumsExist() {
        XCTAssertEqual(TrayRules.sessionOrder(current: nil, albums: []), [])
    }

    // MARK: 搜索（没有拼音）

    func testSearchIsCaseInsensitiveWithoutPinyin() {
        let albums = [album("1", "Camera", count: 1), album("2", "猫咪", count: 2)]
        XCTAssertEqual(AlbumSearch.filter(albums, query: "  CAM ").map(\.id), ["1"])
        XCTAssertEqual(AlbumSearch.filter(albums, query: "").map(\.id), ["1", "2"])
        XCTAssertTrue(AlbumSearch.filter(albums, query: "maomi").isEmpty)
        XCTAssertEqual(AlbumSearch.filter(albums, query: "猫").map(\.id), ["2"])
    }

    func testCreateNameRejectsDuplicateAndInvalid() {
        let albums = [album("1", "Camera", count: 1)]
        XCTAssertNil(AlbumSearch.createName(albums, query: " camera "))
        XCTAssertNil(AlbumSearch.createName(albums, query: "   "))
        XCTAssertNil(AlbumSearch.createName(albums, query: ".."))
        XCTAssertEqual(AlbumSearch.createName(albums, query: " 猫 "), "猫")
    }

    // MARK: 下一天 / 已冲洗

    func testNextDayWalksEarlierThenWraps() {
        let days = [
            FavoriteDayRef(dayKey: "2026-01-20", count: 2, coverId: "a"),
            FavoriteDayRef(dayKey: "2026-01-19", count: 1, coverId: "b"),
            FavoriteDayRef(dayKey: "2026-01-18", count: 4, coverId: "c"),
        ]
        XCTAssertEqual(NextDayRule.next(after: "2026-01-20", daysNewestFirst: days)?.dayKey, "2026-01-19")
        XCTAssertEqual(NextDayRule.next(after: "2026-01-18", daysNewestFirst: days)?.dayKey, "2026-01-20")
        XCTAssertNil(NextDayRule.next(after: "2026-01-18", daysNewestFirst: [days[2]]))
    }

    func testNextDayFromUnknownWrapsToNewest() {
        let days = [
            FavoriteDayRef(dayKey: "2026-01-02", count: 1, coverId: "a"),
            FavoriteDayRef(dayKey: DayKeyRule.unknown, count: 1, coverId: "u"),
        ]
        XCTAssertEqual(NextDayRule.next(after: "2026-01-02", daysNewestFirst: days)?.dayKey, DayKeyRule.unknown)
        XCTAssertEqual(NextDayRule.next(after: DayKeyRule.unknown, daysNewestFirst: days)?.dayKey, "2026-01-02")
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(secondsFromGMT: 0)!
        let date = NextDayRule.date(from: "2026-01-02", calendar: cal)
        XCTAssertEqual(cal.dateComponents([.year, .month, .day], from: date!), DateComponents(year: 2026, month: 1, day: 2))
        XCTAssertNil(NextDayRule.date(from: DayKeyRule.unknown, calendar: cal))
    }

    func testAlreadyInAlbumIsSplitOut() {
        let split = FilingPartition.split(selected: ["a", "b", "c"], members: ["b", "z"])
        XCTAssertEqual(split.already, ["b"])
        XCTAssertEqual(split.toAdd, ["a", "c"])
    }

    func testLogKeepsLatestPerAssetAndCaps() {
        let old = FilingLogRow(assetId: "a", albumTitle: "猫", dayKey: "2026-01-01", filedAt: 1)
        let newer = FilingLogRow(assetId: "a", albumTitle: "狗", dayKey: "2026-01-01", filedAt: 5)
        let other = FilingLogRow(assetId: "b", albumTitle: "猫", dayKey: "2026-01-02", filedAt: 3)
        let upserted = FilingLogRules.upsert([old], adding: [newer, other])
        XCTAssertEqual(upserted.map(\.assetId), ["a", "b"])
        XCTAssertEqual(upserted[0].albumTitle, "狗")
        XCTAssertEqual(FilingLogRules.upsert(upserted, adding: []), upserted)
        XCTAssertEqual(FilingLogRules.remove(upserted, ids: ["a"]).map(\.assetId), ["b"])
        XCTAssertEqual(FilingLogRules.remove(upserted, ids: []), upserted)

        let many = (0..<1005).map { FilingLogRow(assetId: "\($0)", albumTitle: "t", dayKey: "2026-01-01", filedAt: TimeInterval($0)) }
        let capped = FilingLogRules.upsert([], adding: many)
        XCTAssertEqual(capped.count, FilingLogRules.maxEntries)
        XCTAssertEqual(capped.first?.assetId, "1004")
        XCTAssertEqual(capped.last?.assetId, "5")
    }

    func testPruneDropsOnlyConfirmedMissing() {
        XCTAssertNil(FilingPrune.idsToDrop(stored: ["a"], found: nil, libraryCount: 3))
        XCTAssertNil(FilingPrune.idsToDrop(stored: ["a", "b"], found: [], libraryCount: 10))
        XCTAssertEqual(FilingPrune.idsToDrop(stored: ["a"], found: [], libraryCount: 0), ["a"])
        XCTAssertEqual(FilingPrune.idsToDrop(stored: ["a", "b"], found: ["a"], libraryCount: 4), ["b"])
        XCTAssertEqual(FilingPrune.idsToDrop(stored: [], found: [], libraryCount: 4), [])
    }

    func testFiledDaysNewestFirstAndCounts() {
        let rows = [
            FilingLogRow(assetId: "a", albumTitle: "猫", dayKey: "2026-01-01", filedAt: 10),
            FilingLogRow(assetId: "b", albumTitle: "猫", dayKey: "2026-01-01", filedAt: 12),
            FilingLogRow(assetId: "c", albumTitle: "狗", dayKey: "2026-01-01", filedAt: 11),
            FilingLogRow(assetId: "d", albumTitle: "猫", dayKey: "2026-01-02", filedAt: 20),
            FilingLogRow(assetId: "gone", albumTitle: "猫", dayKey: "2026-01-03", filedAt: 30),
        ]
        let days = FiledDayRules.build(rows, present: ["a", "b", "c", "d"])
        XCTAssertEqual(days.map(\.dayKey), ["2026-01-02", "2026-01-01"])
        XCTAssertEqual(days[0].count, 1)
        XCTAssertEqual(days[1].albums.map(\.name), ["猫", "狗"])
        XCTAssertEqual(days[1].albums.map(\.count), [2, 1])
        XCTAssertEqual(days[1].count, 3)
        XCTAssertFalse(days.contains { $0.assetIds.contains("gone") })
    }

    func testLastFiledBuckets() {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(secondsFromGMT: 0)!
        let today = cal.date(from: DateComponents(year: 2026, month: 3, day: 10))!
        let now = today.addingTimeInterval(3_600).timeIntervalSince1970
        XCTAssertEqual(LastFiledRules.kind(filedAt: today.addingTimeInterval(60).timeIntervalSince1970, now: now, calendar: cal), .today)
        let yesterday = cal.date(byAdding: .day, value: -1, to: today)!.timeIntervalSince1970
        XCTAssertEqual(LastFiledRules.kind(filedAt: yesterday, now: now, calendar: cal), .daysAgo(1))
        let older = cal.date(byAdding: .day, value: -8, to: today)!
        XCTAssertEqual(LastFiledRules.kind(filedAt: older.timeIntervalSince1970, now: now, calendar: cal), .on(older))
        XCTAssertNil(LastFiledRules.kind(filedAt: nil, now: now, calendar: cal))
    }
}

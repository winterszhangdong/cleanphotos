import XCTest

final class LibraryRulesTests: XCTestCase {
    // MARK: 未知日期（Android dayKeyOf：timestamp <= 0 → UNKNOWN）

    func testMissingCreationDateIsUnknownDay() {
        XCTAssertEqual(DayKeyRule.key(.distantPast), DayKeyRule.unknown)
        XCTAssertEqual(DayKeyRule.key(Date(timeIntervalSince1970: 0)), DayKeyRule.unknown)
        XCTAssertEqual(DayKeyRule.unknown, ReviewRules.unknownDay, "清理页小结规则按同一个键跳过未知日期")
    }

    func testRealDateKeyUsesCalendarDay() {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        // 2025-09-21 23:30 +08:00（UTC 还是 15:30 同一天；换成 UTC 日历也同天，主要验证格式）
        let d = Date(timeIntervalSince1970: 1_758_468_600)
        XCTAssertEqual(DayKeyRule.key(d, calendar: cal), "2025-09-21")
        XCTAssertFalse(DayKeyRule.isUnknown(d))
    }

    func testUnknownDayNeverGetsSummary() {
        let photos = (0..<8).map { LedgerPhoto(id: "u\($0)", day: DayKeyRule.unknown) } + [LedgerPhoto(id: "n", day: "2026-09-20")]
        var l = ReviewLedger(ordered: photos)
        photos.forEach { l.markViewed($0.id) }
        XCTAssertFalse(DaySummaryRule.shouldInsert(l, from: "u7", to: "n"))
    }

    // MARK: 失效条目清理（只在完整访问权下）

    func testCleanupSkippedUnderLimitedAccess() {
        XCTAssertNil(StoreCleanupPolicy.idsToRemove(stored: ["a", "b"], existing: ["a"], fullAccess: false, libraryCount: 10))
    }

    func testCleanupSkippedWhenQueryFailed() {
        XCTAssertNil(StoreCleanupPolicy.idsToRemove(stored: ["a"], existing: nil, fullAccess: true, libraryCount: 10))
    }

    func testCleanupSkippedWhenNothingFoundInNonEmptyLibrary() {
        XCTAssertNil(StoreCleanupPolicy.idsToRemove(stored: ["a", "b"], existing: [], fullAccess: true, libraryCount: 50))
    }

    func testCleanupRemovesOnlyMissing() {
        XCTAssertEqual(StoreCleanupPolicy.idsToRemove(stored: ["a", "b", "c"], existing: ["a", "c"], fullAccess: true, libraryCount: 3), ["b"])
        XCTAssertEqual(StoreCleanupPolicy.idsToRemove(stored: [], existing: [], fullAccess: true, libraryCount: 3), [])
    }

    func testCleanupEmptyLibraryRemovesEverything() {
        XCTAssertEqual(StoreCleanupPolicy.idsToRemove(stored: ["a"], existing: [], fullAccess: true, libraryCount: 0), ["a"])
    }

    // MARK: 垃圾桶「可释放」只算还在图库里的

    func testTrashBytesIgnoreStaleEntries() {
        let sizes: [String: Int64] = ["a": 100, "gone": 5_000, "b": 50, "neg": -1]
        XCTAssertEqual(TrashTotals.bytes(entrySizes: sizes, present: ["a", "b", "neg"]), 150)
        XCTAssertEqual(TrashTotals.bytes(entrySizes: sizes, present: []), 0)
    }
}

/// 复数文案：代码里 L10n.plural / pluralFormat 用到的每个 key，英文都要有 `<key>_one`（否则 count 1 时退回复数形）。
final class PluralKeysTests: XCTestCase {
    private var iosRoot: URL { URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent() }

    func testEveryPluralKeyHasEnglishOneForm() throws {
        let data = try Data(contentsOf: iosRoot.appendingPathComponent("CleanPhotos/Resources/Localizable.xcstrings"))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let strings = try XCTUnwrap(json["strings"] as? [String: Any])
        func en(_ k: String) -> String? {
            ((((strings[k] as? [String: Any])?["localizations"] as? [String: Any])?["en"] as? [String: Any])?["stringUnit"] as? [String: Any])?["value"] as? String
        }
        let enumerator = FileManager.default.enumerator(at: iosRoot.appendingPathComponent("CleanPhotos"), includingPropertiesForKeys: nil)!
        let re = try NSRegularExpression(pattern: #"L10n\.(?:plural|pluralFormat)\("([a-z0-9_]+)""#)
        var keys = Set<String>()
        for case let url as URL in enumerator where url.pathExtension == "swift" {
            let src = try String(contentsOf: url, encoding: .utf8)
            for m in re.matches(in: src, range: NSRange(src.startIndex..., in: src)) {
                keys.insert(String(src[Range(m.range(at: 1), in: src)!]))
            }
        }
        // 撤回条的 key 是拼出来的（batch_undo_*）
        keys.formUnion(["batch_undo_favorite", "batch_undo_unfavorite", "batch_undo_trash", "batch_undo_restore"])
        XCTAssertFalse(keys.isEmpty)
        for k in keys.sorted() {
            XCTAssertNotNil(en(k), "\(k) 缺英文")
            XCTAssertNotNil(en(k + "_one"), "\(k)_one 缺英文单数形")
        }
    }
}

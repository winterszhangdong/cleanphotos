import XCTest

/// 对应 Android ShouldInsertDaySummaryUseCase / BuildReviewPagesUseCase / ReviewViewModel 的小结规则。
final class ReviewLedgerTests: XCTestCase {
    /// day A 有 n 张，day B 有 m 张（会话顺序 A 在前）。
    func photos(_ n: Int, _ m: Int, dayA: String = "2026-09-21", dayB: String = "2026-09-20") -> [LedgerPhoto] {
        (0..<n).map { LedgerPhoto(id: "a\($0)", day: dayA, size: 100) } + (0..<m).map { LedgerPhoto(id: "b\($0)", day: dayB, size: 100) }
    }
    func next(_ c: inout ReviewSessionCore) {
        guard let i = c.currentIndex, i + 1 < c.pages.count else { return XCTFail("no next") }
        c.settle(on: c.pages[i + 1].key)
    }

    func testSkippedPhotoCountsAsKept_summaryAppearsWhenLeavingDay() {
        // 审计 P0 #3 原样复现：跳过 1 张、其余上下处理 → 跨天前必须出现小结
        var c = ReviewSessionCore(photos: photos(8, 3), startId: "a0")
        c.act("a0", .favorited)            // → a1
        c.act("a1", .trashed)              // → a2
        next(&c)                           // 跳过 a2（保留）→ a3
        XCTAssertEqual(c.currentKey, "p-a3")
        for id in ["a3", "a4", "a5", "a6"] { c.act(id, .favorited) }
        XCTAssertEqual(c.currentKey, "p-a7")
        let key = c.act("a7", .trashed)
        XCTAssertEqual(key, "s-2026-09-21", "处理完最后一张应落到小结")
        let t = DayTally.of(c.ledger, day: "2026-09-21")
        XCTAssertEqual(t.favorited, 5); XCTAssertEqual(t.trashed, 2); XCTAssertEqual(t.kept, 1)
        XCTAssertEqual(t.freedBytes, 200)
    }

    func testSwipeAcrossDays_insertsSummaryAsNeighbourPage() {
        // 只左右翻：全部看过 → 翻过最后一张时侧边已经是小结（预判插入）
        var c = ReviewSessionCore(photos: photos(6, 2), startId: "a0")
        for _ in 0..<5 { next(&c) }
        XCTAssertEqual(c.currentKey, "p-a5")
        let i = c.currentIndex!
        XCTAssertEqual(c.pages[i + 1], .summary("2026-09-21"))
        next(&c)
        XCTAssertEqual(c.currentKey, "s-2026-09-21")
        next(&c)
        XCTAssertEqual(c.currentKey, "p-b0")
        XCTAssertFalse(c.pages.contains(.summary("2026-09-21")), "离开小结后不再出现（同一天最多一次）")
    }

    func testFewerThanSixPhotos_noSummary() {
        var c = ReviewSessionCore(photos: photos(5, 2), startId: "a0")
        for id in ["a0", "a1", "a2", "a3", "a4"] { c.act(id, .trashed) }
        XCTAssertEqual(c.currentKey, "p-b0")
        XCTAssertFalse(c.pages.contains { if case .summary = $0 { return true }; return false })
    }

    func testUntouchedPhotoInDay_noSummary() {
        // 从第 2 张开始进，第 1 张没看过 → 离开这天不出小结
        var c = ReviewSessionCore(photos: photos(7, 2), startId: "a1")
        for id in ["a1", "a2", "a3", "a4", "a5", "a6"] { c.act(id, .favorited) }
        // a6 处理完：下一页是 b0（a0 没看过）
        XCTAssertEqual(c.currentKey, "p-b0")
    }

    func testBackwardsSwipeNeverTriggers() {
        var c = ReviewSessionCore(photos: photos(2, 6, dayA: "2026-09-21", dayB: "2026-09-20"), startId: "b0")
        for _ in 0..<5 { next(&c) }          // 看完 b0…b5
        // 往回翻到 a1（会话顺序向前）：不插 B 的小结
        while let i = c.currentIndex, i > 0, c.currentKey != "p-a1" { c.settle(on: c.pages[i - 1].key) }
        XCTAssertEqual(c.currentKey, "p-a1")
        XCTAssertFalse(c.ledger.summarized.contains("2026-09-20"))
    }

    func testLastDayOfSession_summaryAtEnd() {
        var c = ReviewSessionCore(photos: photos(6, 0), startId: "a0")
        for id in ["a0", "a1", "a2", "a3", "a4"] { c.act(id, .favorited) }
        XCTAssertEqual(c.act("a5", .trashed), "s-2026-09-21")
        XCTAssertEqual(c.pages, [.summary("2026-09-21")])
    }

    func testUndoFromSummary_removesSummaryAndReturnsToPhoto() {
        var c = ReviewSessionCore(photos: photos(6, 1), startId: "a0")
        for id in ["a0", "a1", "a2", "a3", "a4", "a5"] { c.act(id, .favorited) }
        XCTAssertEqual(c.currentKey, "s-2026-09-21")
        XCTAssertEqual(c.undo("a5"), "p-a5")
        XCTAssertEqual(c.ledger.outcomes["a5"], nil)
        XCTAssertTrue(c.ledger.viewed.contains("a5"), "撤回后仍算看过")
        // 摘掉 summarized 后预判又会把小结放在 a5 后面
        let i = c.currentIndex!
        XCTAssertEqual(c.pages[i + 1], .summary("2026-09-21"))
    }

    func testActOnLastPageFallsBackToPrevious() {
        var c = ReviewSessionCore(photos: photos(2, 0), startId: "a1")
        XCTAssertEqual(c.act("a1", .trashed), "p-a0")
        XCTAssertNil(c.act("a0", .trashed))
        XCTAssertTrue(c.isCompleted)
    }

    func testUnknownDayNeverSummarised() {
        let ps = (0..<6).map { LedgerPhoto(id: "u\($0)", day: ReviewRules.unknownDay) }
        var c = ReviewSessionCore(photos: ps, startId: "u0")
        for p in ps.dropLast() { c.act(p.id, .trashed) }
        XCTAssertEqual(c.act("u5", .trashed), nil)
    }

    func testDayCells() {
        var c = ReviewSessionCore(photos: photos(4, 0), startId: "a0")
        c.act("a0", .favorited); next(&c); c.act("a2", .trashed)
        XCTAssertEqual(c.ledger.dayCells(day: "2026-09-21", current: "a3"), [.favorited, .kept, .trashed, .current])
    }

    func testMonthProgress() {
        let byDay = ["2026-09-01": ["x"], "2026-09-02": ["y", "z"], "2026-09-21": ["a"], "2026-09-30": ["f"]]
        let r = MonthProgressBuilder.build(monthOf: "2026-09-21", photosByDay: byDay, processed: ["y"], seen: ["x", "z"], today: "2026-09-25")
        XCTAssertEqual(r.days.count, 30)
        XCTAssertEqual(r.days[0], .done); XCTAssertEqual(r.days[1], .done)
        XCTAssertEqual(r.days[2], .empty); XCTAssertEqual(r.days[20], .highlight)
        XCTAssertEqual(r.days[29], .empty, "晚于今天 = Empty")
        XCTAssertEqual(r.done, 2); XCTAssertEqual(r.withPhotos, 3)
    }
}

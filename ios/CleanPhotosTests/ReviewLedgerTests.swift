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

    func testReplaceKeepsTheSameSlot() {
        var c = ReviewSessionCore(photos: photos(3, 1), startId: "a0")
        c.settle(on: "p-a1")
        c.settle(on: "p-a0")
        c.replace("a1", with: LedgerPhoto(id: "compressed", day: "2026-09-21", size: 0))
        XCTAssertEqual(c.currentKey, "p-a0")
        XCTAssertEqual(c.pages.map(\.key), ["p-a0", "p-compressed", "p-a2", "p-b0"])
        XCTAssertTrue(c.ledger.viewed.contains("compressed"))
        XCTAssertFalse(c.ledger.viewed.contains("a1"))
        c.replace("a0", with: LedgerPhoto(id: "now", day: "2026-09-21", size: 0))
        XCTAssertEqual(c.currentKey, "p-now")
        XCTAssertEqual(c.ledger.dayCells(day: "2026-09-21", current: "now")[0], .current)
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

    func testIncrementalCoreMatchesFullBuilderAcrossMixedOperations() {
        let all = (0..<7).map { LedgerPhoto(id: "d1_\($0)", day: "2026-09-21", size: 100) }
            + (0..<3).map { LedgerPhoto(id: "d2_\($0)", day: "2026-09-20", size: 200) }
            + (0..<6).map { LedgerPhoto(id: "d3_\($0)", day: "2026-09-19", size: 300) }

        var core = ReviewSessionCore(photos: all, startId: "d1_0")

        func assertMatches(_ step: String, file: StaticString = #filePath, line: UInt = #line) {
            let expected = ReviewPagesBuilder.build(
                core.ledger,
                removed: core.removed,
                current: ReviewSessionCore.photoId(of: core.currentKey),
                activeSummaryDay: core.activeSummaryDay
            )
            XCTAssertEqual(core.pages, expected, "pages mismatch at \(step)", file: file, line: line)
            let expectedIdx = core.currentKey.flatMap { key in core.pages.firstIndex { $0.key == key } }
            XCTAssertEqual(core.currentIndex, expectedIdx, "index mismatch at \(step)", file: file, line: line)
        }

        assertMatches("init")
        // 同天横滑跳过若干张 + 处理至触发小结
        next(&core); assertMatches("settle d1_1")
        core.act("d1_1", .favorited); assertMatches("act d1_1")
        core.act("d1_2", .trashed); assertMatches("act d1_2")
        core.act("d1_3", .favorited); assertMatches("act d1_3")
        next(&core); assertMatches("settle d1_5")
        core.act("d1_5", .trashed); assertMatches("act d1_5")
        // 回退再往前到最后一张触发预判小结
        core.settle(on: "p-d1_4"); assertMatches("back to d1_4")
        core.settle(on: "p-d1_6"); assertMatches("predict summary on d1_6")
        XCTAssertEqual(core.summaryDayInPages, "2026-09-21")
        // 撤回刚删掉的 d1_5 后删 d1_6（直接落到小结），再从小结撤回 d1_6，接着删 d1_5 并重新滑入小结
        core.undo("d1_5"); assertMatches("undo d1_5")
        next(&core); assertMatches("settle d1_6 again")
        let s1 = core.act("d1_6", .trashed); assertMatches("act d1_6 -> summary")
        XCTAssertEqual(s1, "s-2026-09-21")
        // 从小结撤回 d1_6（撤销已小结标记）
        XCTAssertEqual(core.undo("d1_6"), "p-d1_6"); assertMatches("undo d1_6 from summary")
        // 滑回 d1_5 并收藏，再删 d1_6 再次进小结
        core.settle(on: "p-d1_5"); assertMatches("settle d1_5")
        core.act("d1_5", .favorited); assertMatches("act d1_5 -> d1_6")
        XCTAssertEqual(core.currentKey, "p-d1_6")
        let s2 = core.act("d1_6", .trashed); assertMatches("act d1_6 -> summary again")
        XCTAssertEqual(s2, "s-2026-09-21")
        // 从小结撤回 d1_6 后通过左右横滑进入再离开小结
        core.undo("d1_6"); assertMatches("undo d1_6 again")
        next(&core); assertMatches("enter summary")
        XCTAssertEqual(core.currentKey, "s-2026-09-21")
        next(&core); assertMatches("leave summary -> d2_0")
        XCTAssertEqual(core.currentKey, "p-d2_0")
        XCTAssertNil(core.summaryDayInPages)
        // 压缩替换 d2_1 并往后翻到 d3 并删光所有照片直至结束
        core.replace("d2_1", with: LedgerPhoto(id: "d2_1_comp", day: "2026-09-20", size: 50))
        assertMatches("replace d2_1")
        next(&core); assertMatches("settle d2_1_comp")
        next(&core); assertMatches("settle d2_2")
        next(&core); assertMatches("settle d3_0")
        for i in 0..<5 {
            core.act("d3_\(i)", .trashed)
            assertMatches("trash d3_\(i)")
        }
        core.act("d3_5", .trashed); assertMatches("trash last d3_5 -> d3 summary")
        XCTAssertEqual(core.currentKey, "s-2026-09-19")
    }
}

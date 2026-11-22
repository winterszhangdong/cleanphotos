import XCTest

final class PhotosPageRulesTests: XCTestCase {
    private let metrics = PhotosLeave.Metrics(chrome: 100, year: 32, header: 50, more: 48, tilePitch: 40, columns: 4)

    // MARK: 日期滑块气泡

    func testChineseBubbleKeepsYearMonthDayUnpadded() {
        let zh = ScrubDateFormat.label(year: 2024, month: 8, day: 3, locale: Locale(identifier: "zh-Hans"))
        let later = ScrubDateFormat.label(year: 2024, month: 12, day: 28, locale: Locale(identifier: "zh-Hans"))
        XCTAssertTrue(zh.chinese)
        XCTAssertEqual(zh.year, "2024")
        XCTAssertEqual(zh.month, "8")
        XCTAssertEqual(zh.day, "3")
        XCTAssertEqual(ScrubDateFormat.phrase(zh), "2024年8月3日")
        XCTAssertEqual(later.month, "12")
        XCTAssertEqual(later.day, "28")
        XCTAssertEqual(ScrubDateFormat.phrase(later), "2024年12月28日")
    }

    func testTraditionalBubbleUsesTheSameYearMonthDaySlots() {
        let hant = ScrubDateFormat.label(year: 2024, month: 8, day: 3, locale: Locale(identifier: "zh-Hant"))
        XCTAssertTrue(hant.chinese)
        XCTAssertTrue(ScrubDateFormat.isChinese(Locale(identifier: "zh_TW")))
        XCTAssertEqual(hant.month, "8")
        XCTAssertEqual(hant.day, "3")
        XCTAssertEqual(ScrubDateFormat.phrase(hant), "2024年8月3日")
    }

    func testEnglishBubbleUsesShortMonthAndUnpaddedDay() {
        let en = ScrubDateFormat.label(year: 2024, month: 8, day: 3, locale: Locale(identifier: "en_US"))
        let later = ScrubDateFormat.label(year: 2024, month: 12, day: 28, locale: Locale(identifier: "en_US"))
        XCTAssertFalse(en.chinese)
        XCTAssertEqual(en.month, "Aug")
        XCTAssertEqual(en.day, "3")
        XCTAssertEqual(en.year, "2024")
        XCTAssertEqual(ScrubDateFormat.phrase(en), "Aug 3, 2024")
        XCTAssertEqual(later.month, "Dec")
        XCTAssertEqual(ScrubDateFormat.phrase(later), "Dec 28, 2024")
        let months = ScrubDateFormat.shortMonths(locale: Locale(identifier: "en_US"))
        XCTAssertEqual(months.count, 12)
        XCTAssertEqual(months[7], "Aug")
    }

    // MARK: 离开照片页

    func testLeavingImmersiveLandsOnTheDateGroupedPhoto() {
        let sections = [day("a", 1...4, year: 2024), day("b", 5...8, year: 2023)]
        let line = metrics.chrome + metrics.tilePitch
        let seen = PhotosLeave.firstVisibleImmersiveIndex(contentLine: line, photoCount: 8, metrics: metrics)
        XCTAssertEqual(seen, 4)
        XCTAssertEqual(PhotosLeave.photoIdOnImmersiveGrid(sections, index: seen), "5")
        let grouped = PhotosLeave.gridKeys(sections, expandedDayKey: nil).firstIndex(of: .photo("5"))
        let landed = PhotosLeave.indexAfterLeave(sections: sections, immersive: true, expandedDayKey: nil, firstIndex: seen)
        XCTAssertEqual(landed, grouped)
        XCTAssertNotEqual(landed, seen)
        XCTAssertNil(PhotosLeave.indexAfterLeave(sections: sections, immersive: true, expandedDayKey: nil, firstIndex: 99))
        let top = PhotosLeave.contentTop(index: landed!, sections: sections, expandedDayKey: nil, metrics: metrics)
        let back = PhotosLeave.firstVisibleIndex(contentLine: top!, sections: sections, expandedDayKey: nil, metrics: metrics)
        XCTAssertEqual(back, landed)
    }

    func testLeavingExpandedOverflowLandsOnTheDayHeader() {
        let sections = [day("a", 1...8, overflow: 9...12, year: 2024), day("b", 13...14)]
        let expanded = PhotosLeave.gridKeys(sections, expandedDayKey: "a")
        let overflow = expanded.firstIndex(of: .photo("9"))!
        let line = PhotosLeave.contentTop(index: overflow, sections: sections, expandedDayKey: "a", metrics: metrics)!
        let seen = PhotosLeave.firstVisibleIndex(contentLine: line, sections: sections, expandedDayKey: "a", metrics: metrics)
        XCTAssertEqual(seen, overflow)
        let header = PhotosLeave.gridKeys(sections, expandedDayKey: nil).firstIndex(of: .header("a"))
        XCTAssertEqual(
            PhotosLeave.indexAfterLeave(sections: sections, immersive: false, expandedDayKey: "a", firstIndex: seen),
            header
        )
        XCTAssertEqual(PhotosLeave.indexInCollapsedGrid(sections, photoId: "9"), header)
        XCTAssertNil(PhotosLeave.indexInCollapsedGrid(sections, photoId: "99"))
    }

    func testLeavingDateGroupedWithoutAnExpandedDayDoesNotMove() {
        let sections = [day("a", 1...4, year: 2024), day("b", 5...8, year: 2023)]
        XCTAssertNil(PhotosLeave.indexAfterLeave(sections: sections, immersive: false, expandedDayKey: nil, firstIndex: 4))
        XCTAssertNil(PhotosLeave.indexAfterLeave(sections: sections, immersive: false, expandedDayKey: nil, firstIndex: 0))
        let expanded = PhotosLeave.gridKeys(sections, expandedDayKey: "a")
        let header = expanded.firstIndex(of: .header("a"))!
        let line = PhotosLeave.contentTop(index: header, sections: sections, expandedDayKey: "a", metrics: metrics)!
        let seen = PhotosLeave.firstVisibleIndex(contentLine: line, sections: sections, expandedDayKey: "a", metrics: metrics)
        XCTAssertEqual(seen, header)
        XCTAssertNil(PhotosLeave.indexAfterLeave(sections: sections, immersive: false, expandedDayKey: "a", firstIndex: seen))
    }

    private func day(
        _ key: String,
        _ photos: ClosedRange<Int>,
        overflow: ClosedRange<Int>? = nil,
        year: Int? = nil
    ) -> PhotosGridSection {
        PhotosGridSection(
            dayKey: key,
            yearHeader: year,
            photoIds: photos.map(String.init),
            overflowIds: overflow?.map(String.init) ?? [],
            header: .day
        )
    }

    // MARK: 阶段二：范围分页与网格布局几何

    func testScopePagerTargetPage() {
        let w: CGFloat = 400
        XCTAssertEqual(ScopePagerMath.targetPage(startPage: 1, currentOffsetX: 400 + 119, pageWidth: w, velocityPtsPerSec: 0, pageCount: 5), 1)
        XCTAssertEqual(ScopePagerMath.targetPage(startPage: 1, currentOffsetX: 400 + 121, pageWidth: w, velocityPtsPerSec: 0, pageCount: 5), 2)
        XCTAssertEqual(ScopePagerMath.targetPage(startPage: 1, currentOffsetX: 400 - 119, pageWidth: w, velocityPtsPerSec: 0, pageCount: 5), 1)
        XCTAssertEqual(ScopePagerMath.targetPage(startPage: 1, currentOffsetX: 400 - 121, pageWidth: w, velocityPtsPerSec: 0, pageCount: 5), 0)
        XCTAssertEqual(ScopePagerMath.targetPage(startPage: 1, currentOffsetX: 410, pageWidth: w, velocityPtsPerSec: 700, pageCount: 5), 2)
        XCTAssertEqual(ScopePagerMath.targetPage(startPage: 1, currentOffsetX: 550, pageWidth: w, velocityPtsPerSec: -700, pageCount: 5), 0)
        XCTAssertEqual(ScopePagerMath.targetPage(startPage: 0, currentOffsetX: -100, pageWidth: w, velocityPtsPerSec: -900, pageCount: 5), 0)
        XCTAssertEqual(ScopePagerMath.targetPage(startPage: 4, currentOffsetX: 1800, pageWidth: w, velocityPtsPerSec: 900, pageCount: 5), 4)
        XCTAssertEqual(ScopePagerMath.targetPage(startPage: 0, currentOffsetX: 0, pageWidth: w, velocityPtsPerSec: 900, pageCount: 1), 0)
    }

    func testScopePagerSpringStep() {
        let t0 = ScopePagerMath.springStep(from: 120, to: 400, initialVelocity: 900, elapsed: 0)
        XCTAssertEqual(t0.x, 120, accuracy: 0.01)
        XCTAssertEqual(t0.v, 900, accuracy: 0.5)
        XCTAssertFalse(t0.settled)

        let end = ScopePagerMath.springStep(from: 120, to: 400, initialVelocity: 900, elapsed: 0.8)
        XCTAssertTrue(end.settled)
        XCTAssertEqual(end.x, 400, accuracy: 0.01)
        XCTAssertEqual(end.v, 0, accuracy: 0.01)
    }

    func testPhotosGridGeometryMatchesPhotosLeave() {
        let width: CGFloat = 390
        let gap: CGFloat = 2
        let side = (width - gap * 3) / 4
        let pitch = side + gap
        let leaveMetrics = PhotosLeave.Metrics(chrome: 96, year: 32, header: 50, more: 48, tilePitch: pitch, columns: 4)
        let sections = [day("a", 1...8, overflow: 9...10, year: 2025), day("b", 11...13)]

        let specs: [PhotosGridGeometry.SectionSpec] = [
            .init(kind: .year),
            .init(kind: .dayHeader(id: "a")),
            .init(kind: .tiles(count: 8, dayId: "a")),
            .init(kind: .more),
            .init(kind: .dayHeader(id: "b")),
            .init(kind: .tiles(count: 3, dayId: "b")),
        ]
        let built = PhotosGridGeometry.build(
            width: width,
            chromeHeight: 96,
            bottomReserve: 176,
            yearHeight: 32,
            headerHeight: 50,
            moreHeight: 48,
            gridGap: gap,
            columns: 4,
            specs: specs
        )

        let keys = PhotosLeave.gridKeys(sections, expandedDayKey: nil)
        XCTAssertEqual(built.slices[0].yStart, PhotosLeave.contentTop(index: 0, sections: sections, expandedDayKey: nil, metrics: leaveMetrics)!, accuracy: 0.01)
        XCTAssertEqual(built.slices[1].yStart, PhotosLeave.contentTop(index: 1, sections: sections, expandedDayKey: nil, metrics: leaveMetrics)!, accuracy: 0.01)
        XCTAssertEqual(built.slices[2].yStart, PhotosLeave.contentTop(index: 2, sections: sections, expandedDayKey: nil, metrics: leaveMetrics)!, accuracy: 0.01)
        let moreIndex = keys.firstIndex(of: .more(PhotosLeave.moreKey(dayKey: "a")))!
        XCTAssertEqual(built.slices[3].yStart, PhotosLeave.contentTop(index: moreIndex, sections: sections, expandedDayKey: nil, metrics: leaveMetrics)!, accuracy: 0.01)
        let bHeader = keys.firstIndex(of: .header("b"))!
        XCTAssertEqual(built.slices[4].yStart, PhotosLeave.contentTop(index: bHeader, sections: sections, expandedDayKey: nil, metrics: leaveMetrics)!, accuracy: 0.01)
        let bFirstPhoto = keys.firstIndex(of: .photo("11"))!
        XCTAssertEqual(built.slices[5].yStart, PhotosLeave.contentTop(index: bFirstPhoto, sections: sections, expandedDayKey: nil, metrics: leaveMetrics)!, accuracy: 0.01)

        XCTAssertEqual(built.dayMarks.count, 2)
        XCTAssertEqual(built.dayMarks[1].id, "b")
        XCTAssertEqual(built.dayMarks[1].lastY ?? -1, built.slices[5].yStart, accuracy: 0.01)
        XCTAssertEqual(built.dayMarks[1].lastHeight ?? -1, side, accuracy: 0.01)

        let range = PhotosGridGeometry.sliceRange(in: built.slices, minY: built.slices[3].yStart + 5, maxY: built.slices[4].yStart + 10)
        XCTAssertEqual(range, 3..<5)
    }

    func testSpringStepCriticallyDampedSnappyConvergesWithoutOvershoot() {
        // SpringToken.snappy: dampingRatio = 1.0, stiffness = 1500
        let k = 1500.0
        let start: CGFloat = 240
        var prevAbs = abs(start)
        var t = 0.0083
        while t <= 0.45 {
            let step = ScopePagerMath.springStep(
                from: start,
                to: 0,
                initialVelocity: -400,
                elapsed: t,
                dampingRatio: 1.0,
                stiffness: k
            )
            XCTAssertGreaterThanOrEqual(step.x, 0, "临界阻尼朝目标方向释放不应过冲 (t=\(t))")
            XCTAssertLessThanOrEqual(abs(step.x), prevAbs + 0.01)
            prevAbs = abs(step.x)
            if step.settled { break }
            t += 0.0083
        }
        let settled = ScopePagerMath.springStep(
            from: start,
            to: 0,
            initialVelocity: -400,
            elapsed: 0.45,
            dampingRatio: 1.0,
            stiffness: k
        )
        XCTAssertTrue(settled.settled)
        XCTAssertEqual(settled.x, 0)
        XCTAssertEqual(settled.v, 0)

        // 高速反向回拽仍单调向目标 0 收敛且最终落定
        let fastReverse = ScopePagerMath.springStep(
            from: -180,
            to: 0,
            initialVelocity: -900,
            elapsed: 0.5,
            dampingRatio: 1.0,
            stiffness: k
        )
        XCTAssertTrue(fastReverse.settled)
        XCTAssertEqual(fastReverse.x, 0)
    }
}

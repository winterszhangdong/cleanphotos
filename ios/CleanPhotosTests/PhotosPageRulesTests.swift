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
}

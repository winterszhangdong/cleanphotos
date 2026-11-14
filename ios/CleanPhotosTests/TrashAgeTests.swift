import XCTest

/// Android TrashOldestDays：满 30 个 24 小时日才返回整天数。
final class TrashAgeTests: XCTestCase {
    private let now: Int64 = 1_700_000_000_000
    private var age: Int64 { TrashAge.thresholdMs }
    private var day: Int64 { age / 30 }

    func testEmptyTrashHasNoAge() {
        XCTAssertNil(TrashAge.wholeDays(movedAtMs: [], nowMs: now))
    }

    func testExactly30DaysUsesTheOldestItem() {
        XCTAssertEqual(TrashAge.wholeDays(movedAtMs: [now - age], nowMs: now), 30)
        XCTAssertEqual(TrashAge.wholeDays(movedAtMs: [now, now - age, now - day], nowMs: now), 30)
    }

    func testOneMillisecondShortOf30DaysIsHidden() {
        XCTAssertNil(TrashAge.wholeDays(movedAtMs: [now - age + 1], nowMs: now))
    }

    func testWholeDaysPast30() {
        XCTAssertEqual(TrashAge.wholeDays(movedAtMs: [now - day * 45], nowMs: now), 45)
        XCTAssertEqual(TrashAge.wholeDays(movedAtMs: [now - day, now - day * 45], nowMs: now), 45)
        XCTAssertEqual(TrashAge.wholeDays(movedAtMs: [now - age - 1], nowMs: now), 30)
        XCTAssertEqual(TrashAge.wholeDays(movedAtMs: [now - day * 45 - (day * 9) / 10], nowMs: now), 45)
    }
}

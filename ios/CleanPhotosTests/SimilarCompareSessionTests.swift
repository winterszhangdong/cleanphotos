import XCTest

final class SimilarCompareSessionTests: XCTestCase {
    func testSwipePastThresholdTrashesCurrentAndDropsItFromCompare() {
        var session = SimilarCompareSession(
            photos: ["a", "b", "c"],
            marks: ["a": .keep, "b": .remove, "c": .remove],
            trashed: [],
            undo: nil
        )
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        XCTAssertTrue(session.trash("c", now: now, lifetime: 4))
        XCTAssertEqual(session.trashed, ["c"])
        XCTAssertEqual(session.photos, ["a", "b"])
        XCTAssertFalse(session.photos.contains("c"))
        XCTAssertNil(session.marks["c"])
        XCTAssertEqual(session.marks["b"], .remove)
        XCTAssertTrue(SimilarCompareSession.showsOnList(photoCount: session.photos.count))

        let marks = session.marks
        session.keep("a")
        XCTAssertEqual(session.marks, marks)
        XCTAssertEqual(session.trashed, ["c"])

        XCTAssertFalse(session.trash("c", now: now, lifetime: 4))
        XCTAssertEqual(session.trashed, ["c"])
        XCTAssertEqual(session.photos, ["a", "b"])
    }

    func testDownToOneStaysOnCompareButLeavesTheList() {
        var session = SimilarCompareSession(
            photos: ["keep", "gone"],
            marks: ["keep": .keep, "gone": .remove],
            trashed: [],
            undo: nil
        )
        XCTAssertTrue(session.trash("gone", now: Date(timeIntervalSince1970: 50), lifetime: 4))
        XCTAssertEqual(session.trashed, ["gone"])
        XCTAssertEqual(session.photos, ["keep"])
        XCTAssertFalse(SimilarCompareSession.showsOnList(photoCount: session.photos.count))
    }

    func testConsecutiveSwipesShareOneUndoAndExpiredSwipeStartsAnother() {
        var session = SimilarCompareSession(
            photos: ["a", "b", "c"],
            marks: ["a": .keep, "b": .remove, "c": .remove],
            trashed: [],
            undo: nil
        )
        let start = Date(timeIntervalSince1970: 1_000)
        XCTAssertTrue(session.trash("c", now: start, lifetime: 4))
        let again = start.addingTimeInterval(3.9)
        XCTAssertTrue(session.trash("b", now: again, lifetime: 4))
        XCTAssertEqual(session.undo?.ids, ["c", "b"])
        XCTAssertEqual(session.undo?.shownAt, again)
        XCTAssertEqual(session.photos, ["a"])

        let expired = again.addingTimeInterval(4)
        XCTAssertTrue(session.trash("a", now: expired, lifetime: 4))
        XCTAssertEqual(session.undo?.ids, ["a"])
        XCTAssertEqual(session.undo?.shownAt, expired)
        XCTAssertTrue(session.photos.isEmpty)
        XCTAssertEqual(session.trashed, ["c", "b", "a"])
    }
}

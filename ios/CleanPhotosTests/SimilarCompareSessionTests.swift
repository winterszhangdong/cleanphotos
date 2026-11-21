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

    func testUnmatchedRouteStillKeepsTheLastPhoto() {
        let original = "1,2,3,4"
        let short = SimilarGroupRef(key: "3,4", photoIds: ["3", "4"], recommendedId: "4")
        let marks = [short.key: ["3": SimilarMark.remove, "4": .keep]]
        let kept = SimilarCompareSession.soleRemainder(
            previous: [short],
            next: [],
            hiddenIds: ["3"],
            marks: marks,
            kept: [:]
        )
        XCTAssertEqual(Array(kept.keys), [short.key])
        let stored = SimilarCompareSession.remainderStored(routeKey: original, remainder: kept)
        XCTAssertEqual(stored?.key, short.key)
        XCTAssertEqual(stored?.value.photoId, "4")
        XCTAssertEqual(stored?.value.mark, .keep)
        let match = SimilarCompareSession.compareMatch(
            routeKey: original,
            groups: [],
            hiddenIds: ["1", "2", "3"],
            remainder: stored?.value
        )
        XCTAssertEqual(match?.photoIds, ["4"])
        XCTAssertEqual(match?.fromRemainder, true)
        XCTAssertEqual(match?.sourceKey, original)
        XCTAssertNil(SimilarCompareSession.compareMatch(
            routeKey: original,
            groups: [],
            hiddenIds: ["4"],
            remainder: stored?.value
        ))
    }

    func testSwipeUpChangesMarkAndLeavesAnExistingKeep() {
        var session = SimilarCompareSession(
            photos: ["a", "b"],
            marks: ["a": .remove, "b": .keep],
            trashed: [],
            undo: nil
        )
        session.keep("a")
        XCTAssertEqual(session.marks["a"], .keep)
        session.keep("a")
        XCTAssertEqual(session.marks["a"], .keep)
        session.keep("missing")
        XCTAssertNil(session.marks["missing"])

        let route = "1,2,3,4"
        var remainder = ["3,4": CompareRemainder(photoId: "4", mark: .remove)]
        let changed = SimilarCompareSession.keptRemainder(routeKey: route, photoId: "4", remainder: remainder)
        XCTAssertEqual(changed?["3,4"]?.mark, .keep)
        XCTAssertEqual(changed?.count, 1)
        remainder = changed ?? [:]
        let again = SimilarCompareSession.keptRemainder(routeKey: route, photoId: "4", remainder: remainder)
        XCTAssertEqual(again?["3,4"]?.mark, .keep)
        XCTAssertNil(SimilarCompareSession.keptRemainder(routeKey: "8,9", photoId: "4", remainder: remainder))
    }

    func testSwipeDownRemovesThePhotoTheRouteKeyCannotSee() {
        let remainder = [
            "9": CompareRemainder(photoId: "9", mark: .remove),
            "3,4": CompareRemainder(photoId: "4", mark: .keep),
        ]
        let stored = SimilarCompareSession.remainderStored(routeKey: "1,2,3,4", remainder: remainder)
        XCTAssertEqual(stored?.key, "3,4")
        let after = SimilarCompareSession.trashedRemainder(routeKey: "1,2,3,4", photoId: "4", remainder: remainder)
        XCTAssertEqual(after, ["9": CompareRemainder(photoId: "9", mark: .remove)])
        XCTAssertNil(SimilarCompareSession.trashedRemainder(routeKey: "1,2,3,4", photoId: "4", remainder: after ?? [:]))
        XCTAssertNil(SimilarCompareSession.compareMatch(
            routeKey: "1,2,3,4",
            groups: [],
            hiddenIds: [],
            remainder: nil
        ))
    }

    func testMarksFollowWhenTheNewGroupReplacesTheKey() {
        let oldKey = "1,2,3,4"
        let old = SimilarGroupRef(key: oldKey, photoIds: ["1", "2", "3", "4"], recommendedId: "1")
        let marks = [
            oldKey: ["1": SimilarMark.keep, "2": .keep, "3": .remove, "4": .remove],
        ]
        let next = SimilarGroupRef(key: "1,2,3", photoIds: ["1", "2", "3"], recommendedId: "1")
        let moved = SimilarCompareSession.migratedMarks(
            previous: marks,
            photoIds: next.photoIds,
            recommendedId: next.recommendedId
        )
        XCTAssertEqual(moved?["2"], .keep)
        XCTAssertEqual(moved?["3"], .remove)
        XCTAssertNil(SimilarCompareSession.migratedMarks(previous: [:], photoIds: next.photoIds, recommendedId: "1"))

        let kept = SimilarCompareSession.soleRemainder(
            previous: [old],
            next: [next],
            hiddenIds: ["4"],
            marks: marks,
            kept: [:]
        )
        XCTAssertTrue(kept.isEmpty)
        let match = SimilarCompareSession.compareMatch(
            routeKey: oldKey,
            groups: [next],
            hiddenIds: [],
            remainder: nil
        )
        XCTAssertEqual(match?.sourceKey, next.key)
        XCTAssertEqual(match?.photoIds, ["1", "2", "3"])
        XCTAssertEqual(match?.fromRemainder, false)
    }
}

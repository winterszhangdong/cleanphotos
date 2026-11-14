import XCTest

final class SimilarRulesTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_700_000_000)

    func testGapAndHammingAreClosedIntervals() {
        let within = [ref("a", 0), ref("b", 10)]
        let hashesTen = hashes(("a", 0), ("b", bits(10)))
        XCTAssertEqual(SimilarRules.group(within, hashes: hashesTen).map(\.ids), [["a", "b"]])

        let pastGap = [ref("a", 0), ref("b", 10 + 0.001)]
        XCTAssertTrue(SimilarRules.group(pastGap, hashes: hashes(("a", 0), ("b", 0))).isEmpty)

        XCTAssertTrue(SimilarRules.group(within, hashes: hashes(("a", 0), ("b", bits(11)))).isEmpty)
        XCTAssertEqual(SimilarRules.hamming(0, bits(10)), 10)
        XCTAssertEqual(SimilarRules.hamming(0, bits(11)), 11)
    }

    func testChainComparesOnlyWithPreviousPhoto() {
        // A–C 相隔 20 秒。B 与 A 距离 10，C 与 B 距离 10，C 与 A 距离 20。只和前一张比才三张一组。
        let photos = [ref("a", 0), ref("b", 10), ref("c", 20)]
        let b = bits(10)
        let c = b | (b << 10)
        XCTAssertEqual(SimilarRules.hamming(0, b), 10)
        XCTAssertEqual(SimilarRules.hamming(b, c), 10)
        XCTAssertGreaterThan(SimilarRules.hamming(0, c), SimilarRules.maxHamming)
        XCTAssertEqual(
            SimilarRules.group(photos, hashes: hashes(("a", 0), ("b", b), ("c", c))).map(\.ids),
            [["a", "b", "c"]]
        )
    }

    func testMissingHashIsSkippedAndSidesCanStillJoin() {
        let photos = [ref("a", 0), ref("b", 5), ref("c", 10)]
        XCTAssertEqual(SimilarRules.group(photos, hashes: hashes(("a", 0), ("c", 0))).map(\.ids), [["a", "c"]])
    }

    func testNoDateVideoAndLonersAreNotHashed() {
        let far = ref("far", 10 + 0.001 + 60)
        let video = SimilarPhotoRef(id: "vid", takenAt: t0, pixelCount: 1, byteSize: 1, isImage: false)
        let undated = SimilarPhotoRef(id: "none", takenAt: nil, pixelCount: 1, byteSize: 1)
        let zero = SimilarPhotoRef(id: "zero", takenAt: Date(timeIntervalSince1970: 0), pixelCount: 1, byteSize: 1)
        let photos = [ref("a", 0), ref("b", 10), far, video, undated, zero]
        XCTAssertEqual(SimilarRules.hashCandidateIds(photos), ["a", "b"])
        XCTAssertTrue(SimilarRules.hashCandidateIds([ref("solo", 5)]).isEmpty)
        XCTAssertTrue(SimilarRules.group([ref("solo", 5)], hashes: hashes(("solo", 0))).isEmpty)
    }

    func testNewerBurstIsListedFirst() {
        let photos = [ref("a", 0), ref("b", 1), ref("c", 100), ref("d", 101)]
        let grouped = SimilarRules.group(photos, hashes: hashes(("a", 0), ("b", 0), ("c", 0), ("d", 0)))
        XCTAssertEqual(grouped.map(\.ids), [["c", "d"], ["a", "b"]])
    }

    func testRecommendationFallsBackWithoutSharpness() {
        let biggerPixels = ref("pixels", 2, pixels: 200 * 100, bytes: 1)
        let biggerFile = ref("file", 3, pixels: 100, bytes: 50)
        let earlier = ref("early", 1, pixels: 100, bytes: 50)
        let later = ref("late", 4, pixels: 100, bytes: 50)
        XCTAssertEqual(SimilarRules.chooseRecommended([biggerFile, biggerPixels, earlier]), "pixels")
        XCTAssertEqual(SimilarRules.chooseRecommended([later, earlier, biggerFile]), "early")
        let same = [ref("m", 1, pixels: 100, bytes: 10), ref("a", 1, pixels: 100, bytes: 10)]
        XCTAssertEqual(SimilarRules.chooseRecommended(same), "a")

        let sharpSmall = ref("sharp", 2, pixels: 100, bytes: 1)
        let blurryBig = ref("blur", 1, pixels: 400 * 400, bytes: 90)
        XCTAssertEqual(
            SimilarRules.chooseRecommended([blurryBig, sharpSmall], sharpness: ["sharp": 20, "blur": 1]),
            "sharp"
        )
        XCTAssertEqual(
            SimilarRules.chooseRecommended([sharpSmall, blurryBig], sharpness: ["blur": 4]),
            "blur"
        )
        XCTAssertEqual(
            SimilarRules.chooseRecommended([blurryBig, sharpSmall], sharpness: ["sharp": 3, "blur": 3]),
            "blur"
        )
    }

    func testHandledKeySuppressesSubsetGroups() {
        XCTAssertNil(SimilarRules.handledKey(remainingIds: ["only"]))
        XCTAssertEqual(SimilarRules.handledKey(remainingIds: ["b", "a"]), "a,b")

        XCTAssertTrue(SimilarRules.isSuppressed(groupIds: ["b", "a"], handledKeys: ["a,b"]))
        XCTAssertTrue(SimilarRules.isSuppressed(groupIds: ["a", "b"], handledKeys: ["a,b,c"]))
        XCTAssertFalse(SimilarRules.isSuppressed(groupIds: ["a", "b", "c"], handledKeys: ["a,b"]))
        XCTAssertFalse(SimilarRules.isSuppressed(groupIds: ["a", "b"], handledKeys: [] as [String]))

        let photos = [ref("a", 0), ref("b", 1), ref("c", 2)]
        let groups = SimilarRules.group(photos, hashes: hashes(("a", 0), ("b", 0), ("c", 0)))
        let visible = groups.filter { !SimilarRules.isSuppressed(groupIds: $0.ids, handledKeys: ["a,b,c"]) }
        XCTAssertTrue(visible.isEmpty)
        let keptPair = groups.filter { !SimilarRules.isSuppressed(groupIds: $0.ids, handledKeys: ["a,b"]) }
        XCTAssertEqual(keptPair.map(\.ids), [["a", "b", "c"]])
    }

    func testDHashBitZeroIsTheFirstPair() {
        var luma = [Int](repeating: 1, count: SimilarRules.dHashLength)
        luma[0] = 2
        XCTAssertEqual(SimilarRules.dHash(luma: luma), 1)
        XCTAssertEqual(SimilarRules.laplacianVariance(luma: [Int](repeating: 40, count: 9), width: 3, height: 3), 0)
    }

    private func ref(_ id: String, _ offset: TimeInterval, pixels: Int64 = 100, bytes: Int64 = 10) -> SimilarPhotoRef {
        SimilarPhotoRef(id: id, takenAt: t0.addingTimeInterval(offset), pixelCount: pixels, byteSize: bytes)
    }

    private func bits(_ n: Int) -> UInt64 {
        precondition(n > 0 && n < 64)
        return (UInt64(1) << UInt64(n)) - 1
    }

    private func hashes(_ pairs: (String, UInt64)...) -> [String: UInt64] {
        Dictionary(uniqueKeysWithValues: pairs)
    }
}

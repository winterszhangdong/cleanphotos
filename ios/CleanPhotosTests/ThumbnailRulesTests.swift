import Accelerate
import CoreGraphics
import XCTest

final class ThumbnailRulesTests: XCTestCase {
    func testPixelSideRoundsUpAcrossDisplayScalesAndClampsMinimum() {
        XCTAssertEqual(ThumbnailCacheKey.pixelSide(points: 100, scale: 1), 100)
        XCTAssertEqual(ThumbnailCacheKey.pixelSide(points: 97.25, scale: 2), 195)
        XCTAssertEqual(ThumbnailCacheKey.pixelSide(points: 105.5, scale: 3), 317)
        XCTAssertEqual(ThumbnailCacheKey.pixelSide(points: 0, scale: 3), 1)
        XCTAssertEqual(ThumbnailCacheKey.pixelSide(points: -12, scale: 3), 1)
        XCTAssertEqual(ThumbnailCacheKey.pixelSide(points: 50, scale: 0), 50)
    }

    func testCacheKeySeparatesNormalAndTrashedStylesAndPixelSizes() {
        let normal100 = ThumbnailCacheKey.make(assetId: "asset-1", pixelSide: 100, style: .normal)
        let trashed100 = ThumbnailCacheKey.make(assetId: "asset-1", pixelSide: 100, style: .trashed)
        let normal200 = ThumbnailCacheKey.make(assetId: "asset-1", pixelSide: 200, style: .normal)

        XCTAssertNotEqual(normal100, trashed100)
        XCTAssertNotEqual(normal100, normal200)
        XCTAssertEqual(normal100, ThumbnailCacheKey.make(assetId: "asset-1", pixelSide: 100, style: .normal))
    }

    func testWindowDiffComputesAddedAndEvictedAssetIds() {
        let d1 = ThumbnailWindowDiff.delta(previous: ["a", "b", "c"], next: ["b", "c", "d", "e"])
        XCTAssertEqual(d1.start, ["d", "e"])
        XCTAssertEqual(d1.stop, ["a"])

        let d2 = ThumbnailWindowDiff.delta(previous: ["a", "b"], next: ["a", "b"])
        XCTAssertTrue(d2.start.isEmpty)
        XCTAssertTrue(d2.stop.isEmpty)

        let d3 = ThumbnailWindowDiff.delta(previous: [], next: ["x", "y"])
        XCTAssertEqual(d3.start, ["x", "y"])
        XCTAssertTrue(d3.stop.isEmpty)
    }

    func testDecodedGeometryPreservesNormalAspectsAndClampsLongScreenshots() {
        let landscape = ThumbnailSizeRule.decodedGeometry(sourceWidth: 4032, sourceHeight: 3024, targetPixelSide: 317)
        XCTAssertEqual(landscape.width, 423)
        XCTAssertEqual(landscape.height, 317)
        XCTAssertEqual(landscape.drawRect.origin.x, 0, accuracy: 0.5)
        XCTAssertEqual(landscape.drawRect.origin.y, 0, accuracy: 0.5)

        let portrait = ThumbnailSizeRule.decodedGeometry(sourceWidth: 1284, sourceHeight: 2778, targetPixelSide: 317)
        XCTAssertEqual(portrait.width, 317)
        XCTAssertEqual(portrait.height, 686)

        let small = ThumbnailSizeRule.decodedGeometry(sourceWidth: 200, sourceHeight: 150, targetPixelSide: 317)
        XCTAssertEqual(small.width, 200)
        XCTAssertEqual(small.height, 150)

        let longScreenshot = ThumbnailSizeRule.decodedGeometry(sourceWidth: 1170, sourceHeight: 12000, targetPixelSide: 317)
        XCTAssertEqual(longScreenshot.width, 317)
        XCTAssertEqual(longScreenshot.height, 951)
        XCTAssertLessThan(longScreenshot.drawRect.minY, 0)

        let zero = ThumbnailSizeRule.decodedGeometry(sourceWidth: 0, sourceHeight: 300, targetPixelSide: 317)
        XCTAssertEqual(zero.width, 317)
        XCTAssertEqual(zero.height, 317)
    }

    func testTrashedColorMatrixInPlaceBGRA8888() throws {
        let width = 4
        let height = 1
        let bytesPerRow = 16
        let colorSpace = try XCTUnwrap(CGColorSpace(name: CGColorSpace.sRGB))
        let bitmapInfo = CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.noneSkipFirst.rawValue
        let ctx = try XCTUnwrap(
            CGContext(
                data: nil,
                width: width,
                height: height,
                bitsPerComponent: 8,
                bytesPerRow: bytesPerRow,
                space: colorSpace,
                bitmapInfo: bitmapInfo
            )
        )
        let raw = try XCTUnwrap(ctx.data).assumingMemoryBound(to: UInt8.self)
        // [B, G, R, X]
        let input: [UInt8] = [
            200, 200, 200, 255, // gray
            0, 0, 200, 255,     // pure red
            0, 200, 0, 255,     // pure green
            200, 0, 0, 255,     // pure blue
        ]
        for (i, b) in input.enumerated() {
            raw[i] = b
        }

        var src = vImage_Buffer(
            data: raw,
            height: vImagePixelCount(height),
            width: vImagePixelCount(width),
            rowBytes: bytesPerRow
        )
        var dst = src
        let err = vImageMatrixMultiply_ARGB8888(
            &src,
            &dst,
            TrashedColorMatrix.bgraMatrix,
            TrashedColorMatrix.divisor,
            nil,
            nil,
            vImage_Flags(kvImageNoFlags)
        )
        XCTAssertEqual(err, kvImageNoError)

        // Gray: (172, 172, 172)
        XCTAssertEqual(Int(raw[0]), 172, accuracy: 1)
        XCTAssertEqual(Int(raw[1]), 172, accuracy: 1)
        XCTAssertEqual(Int(raw[2]), 172, accuracy: 1)
        // Red: R≈131, G≈11, B≈11
        XCTAssertEqual(Int(raw[4]), 11, accuracy: 1)
        XCTAssertEqual(Int(raw[5]), 11, accuracy: 1)
        XCTAssertEqual(Int(raw[6]), 131, accuracy: 1)
        // Green: R≈37, G≈157, B≈37
        XCTAssertEqual(Int(raw[8]), 37, accuracy: 1)
        XCTAssertEqual(Int(raw[9]), 157, accuracy: 1)
        XCTAssertEqual(Int(raw[10]), 37, accuracy: 1)
        // Blue: R≈4, G≈4, B≈124
        XCTAssertEqual(Int(raw[12]), 124, accuracy: 1)
        XCTAssertEqual(Int(raw[13]), 4, accuracy: 1)
        XCTAssertEqual(Int(raw[14]), 4, accuracy: 1)
    }
}

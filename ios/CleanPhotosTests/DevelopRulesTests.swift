import XCTest

final class DevelopRulesTests: XCTestCase {
    func testDevelopCurveEndpointsAndMonotonic() {
        XCTAssertEqual(DevelopCurve.develop(0), 0)
        XCTAssertEqual(DevelopCurve.develop(-0.4), 0)
        XCTAssertEqual(DevelopCurve.develop(1), 1)
        XCTAssertEqual(DevelopCurve.develop(1.7), 1)
        XCTAssertEqual(DevelopCurve.develop(0.25), 0.1876338108996336, accuracy: 1e-4)
        XCTAssertEqual(DevelopCurve.develop(0.5), 0.7622611036101148, accuracy: 1e-4)
        XCTAssertEqual(DevelopCurve.develop(0.75), 0.9586396713866066, accuracy: 1e-4)

        var previous = -1.0
        for step in 0...100 {
            let y = DevelopCurve.develop(Double(step) / 100)
            XCTAssertGreaterThanOrEqual(y, previous)
            previous = y
        }
    }

    func testGridStaggerIsDiagonalAndCapsAtTwelveSteps() {
        XCTAssertEqual(DevelopGridProfile.staggerDelay(row: 0, col: 0), 0)
        XCTAssertEqual(DevelopGridProfile.staggerDelay(row: 1, col: 2), 0.165, accuracy: 1e-9)
        XCTAssertEqual(DevelopGridProfile.staggerDelay(row: -3, col: 1), 0)
        XCTAssertEqual(DevelopGridProfile.staggerDelay(row: 8, col: 8), 12 * 0.055, accuracy: 1e-9)
        XCTAssertEqual(DevelopGridProfile.staggerDelay(row: 40, col: 2), 12 * 0.055, accuracy: 1e-9)
        XCTAssertEqual(DevelopGridProfile.duration, 0.520, accuracy: 1e-9)
        XCTAssertEqual(DevelopGridProfile.maxSteps, 12)
    }

    func testOverlayMatchesDevelopMatrixFormula() {
        let start = DevelopTone.at(elapsedFraction: 0)
        XCTAssertEqual(start.brightness, 0.08, accuracy: 1e-9)
        XCTAssertEqual(start.contrast, 0.60, accuracy: 1e-9)
        XCTAssertEqual(start.saturation, 0, accuracy: 1e-9)
        let startVeil = DevelopCompositor.overlay(elapsedFraction: 0)
        XCTAssertEqual(startVeil.grayAlpha, 1, accuracy: 1e-9)
        XCTAssertEqual(startVeil.veilAlpha, 0.952, accuracy: 1e-9)
        XCTAssertEqual(startVeil.veilWhite, 0.2100840336134454, accuracy: 1e-6)

        let end = DevelopTone.at(elapsedFraction: 1)
        XCTAssertEqual(end.brightness, 1, accuracy: 1e-6)
        XCTAssertEqual(end.contrast, 1, accuracy: 1e-6)
        XCTAssertEqual(end.saturation, 1, accuracy: 1e-6)
        let endVeil = DevelopCompositor.overlay(elapsedFraction: 1)
        XCTAssertEqual(endVeil.grayAlpha, 0, accuracy: 1e-6)
        XCTAssertEqual(endVeil.veilAlpha, 0, accuracy: 1e-6)

        let samples: [Double] = [0, 0.08, 0.25, 0.5, 0.6, 0.75, 1]
        let pixels: [(Double, Double, Double)] = [
            (0, 0, 0), (1, 1, 1), (1, 0, 0), (0, 1, 0), (0, 0, 1), (0.2, 0.5, 0.8),
        ]
        for b in samples {
            for c in samples {
                for s in samples {
                    let veil = DevelopCompositor.overlay(brightness: b, contrast: c, saturation: s)
                    XCTAssertGreaterThanOrEqual(veil.veilAlpha, 0)
                    XCTAssertLessThanOrEqual(veil.veilAlpha, 1)
                    XCTAssertGreaterThanOrEqual(veil.veilWhite, -1e-9)
                    XCTAssertLessThanOrEqual(veil.veilWhite, 1)
                    XCTAssertGreaterThanOrEqual(veil.grayAlpha, 0)
                    XCTAssertLessThanOrEqual(veil.grayAlpha, 1)
                    for rgb in pixels {
                        let luma = 0.2126 * rgb.0 + 0.7152 * rgb.1 + 0.0722 * rgb.2
                        for channel in [rgb.0, rgb.1, rgb.2] {
                            let formula = c * b * (s * channel + (1 - s) * luma) + 0.5 * (1 - c)
                            let gray = veil.grayAlpha * luma + (1 - veil.grayAlpha) * channel
                            let layered = veil.veilAlpha * veil.veilWhite + (1 - veil.veilAlpha) * gray
                            XCTAssertEqual(layered, formula, accuracy: 1e-4)
                        }
                    }
                }
            }
        }
    }

    func testElapsedFractionStaysDarkThroughStaggerDelay() {
        XCTAssertEqual(DevelopCompositor.elapsedFraction(now: 10, start: 10, delay: 0.055, duration: 0.520), 0)
        XCTAssertEqual(DevelopCompositor.elapsedFraction(now: 10.055, start: 10, delay: 0.055, duration: 0.520), 0)
        XCTAssertEqual(
            DevelopCompositor.elapsedFraction(now: 10.055 + 0.260, start: 10, delay: 0.055, duration: 0.520),
            0.5,
            accuracy: 1e-9
        )
        XCTAssertEqual(DevelopCompositor.elapsedFraction(now: 12, start: 10, delay: 0, duration: 0.520), 1)
        XCTAssertEqual(DevelopCompositor.elapsedFraction(now: 10, start: 10, delay: 0, duration: 0), 0)
    }

    func testSessionTrackerKeepsThreeGridsIndependentAndSinglePlay() {
        var tracker = DevelopSessionTracker()
        XCTAssertFalse(tracker.claim(grid: .photos, id: "a"))
        tracker.seed(grid: .photos, id: "a")
        tracker.seed(grid: .favorites, id: "b")
        XCTAssertTrue(tracker.claim(grid: .photos, id: "a"))
        XCTAssertFalse(tracker.claim(grid: .photos, id: "a"))
        XCTAssertFalse(tracker.claim(grid: .photos, id: "missing"))
        XCTAssertTrue(tracker.claim(grid: .favorites, id: "b"))
        XCTAssertTrue(tracker.claim(grid: .similar, id: "cached-thumb"))
        XCTAssertFalse(tracker.claim(grid: .similar, id: "cached-thumb"))

        tracker.markPlayed(.photos)
        tracker.seed(grid: .photos, id: "later")
        XCTAssertFalse(tracker.claim(grid: .photos, id: "later"))
        XCTAssertFalse(tracker.claim(grid: .photos, id: "a"))
        XCTAssertTrue(tracker.hasPlayed(.photos))
        XCTAssertFalse(tracker.hasPlayed(.favorites))
        XCTAssertFalse(tracker.hasPlayed(.similar))
        XCTAssertTrue(tracker.claim(grid: .similar, id: "another"))

        tracker.release(grid: .favorites, id: "b")
        XCTAssertTrue(tracker.claim(grid: .favorites, id: "b"))
        tracker.markPlayed(.favorites)
        tracker.release(grid: .favorites, id: "b")
        XCTAssertFalse(tracker.claim(grid: .favorites, id: "b"))
        tracker.markPlayed(.similar)
        XCTAssertFalse(tracker.claim(grid: .similar, id: "fresh"))
    }

    func testGrayscaleMatrixProducesNeutralRec709Luminance() {
        let matrix = DevelopGrayscaleMatrix.bgraMatrix
        let divisor = Double(DevelopGrayscaleMatrix.divisor)
        let inputs: [(b: Double, g: Double, r: Double, x: Double)] = [
            (1, 0, 0, 1),
            (0, 1, 0, 1),
            (0, 0, 1, 1),
            (1, 1, 1, 1),
            (0, 0, 0, 1),
            (0.1, 0.45, 0.9, 1)
        ]
        for input in inputs {
            let src = [input.b, input.g, input.r, input.x]
            var dst = [Double](repeating: 0, count: 4)
            for col in 0..<4 {
                var sum = 0.0
                for row in 0..<4 {
                    sum += src[row] * Double(matrix[row * 4 + col])
                }
                dst[col] = sum / divisor
            }
            let expectedLuma = 0.0722 * input.b + 0.7152 * input.g + 0.2126 * input.r
            XCTAssertEqual(dst[0], expectedLuma, accuracy: 1e-3)
            XCTAssertEqual(dst[1], expectedLuma, accuracy: 1e-3)
            XCTAssertEqual(dst[2], expectedLuma, accuracy: 1e-3)
            XCTAssertEqual(dst[0], dst[1], accuracy: 1e-9)
            XCTAssertEqual(dst[1], dst[2], accuracy: 1e-9)
            XCTAssertEqual(dst[3], input.x, accuracy: 1e-4)
        }
    }
}

import SwiftUI

/// First-screen develop: brightness/contrast/sat ramp once per process per grid.
/// Scrolled-in cells skip this and show immediately.
@MainActor
enum GridDevelopSession {
    private static var photosPlayed = false
    private static var favoritesPlayed = false
    private static var photosSeedIDs: Set<String> = []
    private static var favoritesSeedIDs: Set<String> = []

    enum Grid { case photos, favorites }

    static func shouldDevelop(grid: Grid, id: String, isInitialPass: Bool) -> Bool {
        switch grid {
        case .photos:
            if photosPlayed { return false }
            if isInitialPass {
                photosSeedIDs.insert(id)
                return true
            }
            return photosSeedIDs.contains(id) && !photosPlayed
        case .favorites:
            if favoritesPlayed { return false }
            if isInitialPass {
                favoritesSeedIDs.insert(id)
                return true
            }
            return favoritesSeedIDs.contains(id) && !favoritesPlayed
        }
    }

    static func markPlayed(_ grid: Grid) {
        switch grid {
        case .photos: photosPlayed = true
        case .favorites: favoritesPlayed = true
        }
    }

    static func staggerDelay(row: Int, col: Int) -> Double {
        let step = min(row + col, DarkroomMotion.developMaxSteps - 1)
        return Double(step) * DarkroomMotion.developStagger
    }
}

struct DevelopModifier: ViewModifier {
    let enabled: Bool
    let delay: Double
    @State private var progress: CGFloat = 0

    @ViewBuilder
    func body(content: Content) -> some View {
        if enabled {
            content
                .opacity(0.08 + 0.92 * progress)
                .saturation(progress)
                .brightness(-0.15 + 0.15 * progress)
                .task(id: enabled) {
                    // 延迟动画会被父级刷新取消，progress 停在 0，格子就一直近乎透明。到点强制走完。
                    progress = 0
                    withAnimation(DarkroomMotion.developCurve.delay(delay)) {
                        progress = 1
                    }
                    let wait = delay + DarkroomMotion.develop + 0.05
                    try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
                    progress = 1
                }
        } else {
            content
        }
    }
}

extension View {
    func darkroomDevelop(enabled: Bool, delay: Double) -> some View {
        modifier(DevelopModifier(enabled: enabled, delay: delay))
    }
}

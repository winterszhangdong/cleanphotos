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

    func body(content: Content) -> some View {
        content
            .opacity(enabled ? (0.08 + 0.92 * progress) : 1)
            .saturation(enabled ? progress : 1)
            .brightness(enabled ? (-0.15 + 0.15 * progress) : 0)
            .onAppear {
                guard enabled else {
                    progress = 1
                    return
                }
                progress = 0
                withAnimation(DarkroomMotion.developCurve.delay(delay)) {
                    progress = 1
                }
            }
    }
}

extension View {
    func darkroomDevelop(enabled: Bool, delay: Double) -> some View {
        modifier(DevelopModifier(enabled: enabled, delay: delay))
    }
}

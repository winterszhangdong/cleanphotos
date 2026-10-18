import SwiftUI
import Foundation

/// Launch with env CLEANPHOTOS_SHOTS=1 to auto-drive tabs/review for simulator screenshots.
/// Handshake: writes phase name into Documents/cp_shot_phase; waits until
/// Documents/cp_shot_ack contains the same string (written by the host capture script).
/// 审计 / 截图钩子总开关：只在模拟器上生效（真机上即使带了启动参数或环境变量也不会改动用户的收藏 / 垃圾桶）。
/// 性能测量参数（-cpAutoSwipe / -cpfps / -cpLegacy / -cpperf）不受影响 —— 它们要在真机 Release 上测，且自带 PerfDryRun 不落盘。
enum AuditHooks {
    #if targetEnvironment(simulator)
    static let enabled = true
    #else
    static let enabled = false
    #endif
}

enum ScreenshotHarness {
    @MainActor
    static func runIfNeeded(tab: Binding<AppTab>, reviewLaunch: Binding<ReviewLaunch?>, library: PhotoLibraryService) async {
        guard AuditHooks.enabled, ProcessInfo.processInfo.environment["CLEANPHOTOS_SHOTS"] == "1" else { return }

        for _ in 0..<50 {
            if !library.isLoading && (library.totalCount > 0 || library.authorization == .authorized || library.authorization == .limited) {
                break
            }
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
        try? await Task.sleep(nanoseconds: 600_000_000)

        // Seed favorite + trash so Favorites/Trash shots are non-empty
        let ids = library.photosOrdered.prefix(4).map(\.id)
        if library.favoriteCount == 0, let first = ids.first {
            _ = library.favorite(ids: [first], showUndo: false)
        }
        if library.trashCount == 0, ids.count > 1 {
            _ = library.moveToTrash(ids: [ids[1]], showUndo: false)
        }
        try? await Task.sleep(nanoseconds: 400_000_000)

        // 1) Photos grid (also cropped on host for the unseen-dot close-up)
        tab.wrappedValue = .photos
        reviewLaunch.wrappedValue = nil
        try? await Task.sleep(nanoseconds: 700_000_000)
        await waitForShot(named: "photos")

        // 2) Sort / filter menus
        NotificationCenter.default.post(name: .cleanphotosHarnessMenu, object: "sort")
        try? await Task.sleep(nanoseconds: 500_000_000)
        await waitForShot(named: "menu-sort")
        NotificationCenter.default.post(name: .cleanphotosHarnessMenu, object: "filter")
        try? await Task.sleep(nanoseconds: 500_000_000)
        await waitForShot(named: "menu-filter")
        NotificationCenter.default.post(name: .cleanphotosHarnessMenu, object: nil)
        try? await Task.sleep(nanoseconds: 400_000_000)

        // 3) Review (progress bar + chrome)
        if let start = library.visiblePhotos.first?.id {
            reviewLaunch.wrappedValue = ReviewLaunch(scope: .allPhotos, startPhotoId: start, dayId: nil)
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            await waitForShot(named: "review")
            reviewLaunch.wrappedValue = nil
            try? await Task.sleep(nanoseconds: 600_000_000)
        }

        // 4) Favorites  5) Trash
        tab.wrappedValue = .favorites
        try? await Task.sleep(nanoseconds: 700_000_000)
        await waitForShot(named: "favorites")
        tab.wrappedValue = .trash
        try? await Task.sleep(nanoseconds: 700_000_000)
        await waitForShot(named: "trash")

        // 6) Multi-select via the drag-select code path (anchor tile 0 → tile 5)
        tab.wrappedValue = .photos
        try? await Task.sleep(nanoseconds: 600_000_000)
        NotificationCenter.default.post(name: .cleanphotosDragSelect, object: 6)
        try? await Task.sleep(nanoseconds: 700_000_000)
        await waitForShot(named: "multiselect")
        NotificationCenter.default.post(name: .cleanphotosHarnessMenu, object: nil)
        try? await Task.sleep(nanoseconds: 400_000_000)

        // 7) Batch undo capsule, then undo it
        let two = Array(library.visiblePhotos.prefix(2).map(\.id))
        if !two.isEmpty {
            _ = library.moveToTrash(ids: two)
            try? await Task.sleep(nanoseconds: 700_000_000)
            await waitForShot(named: "undo")
            library.undoLast()
            try? await Task.sleep(nanoseconds: 400_000_000)
        }

        await waitForShot(named: "done")
    }

    @MainActor
    private static func waitForShot(named name: String) async {
        writePhase(name)
        // Host script has up to ~60s to screenshot + ack
        for _ in 0..<300 {
            if readAck() == name { return }
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
    }

    private static var docsURL: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    private static func writePhase(_ name: String) {
        let url = docsURL.appendingPathComponent("cp_shot_phase")
        try? name.data(using: .utf8)?.write(to: url, options: .atomic)
        // Clear prior ack so we don't race on a stale match
        let ack = docsURL.appendingPathComponent("cp_shot_ack")
        try? "".data(using: .utf8)?.write(to: ack, options: .atomic)
    }

    private static func readAck() -> String? {
        let url = docsURL.appendingPathComponent("cp_shot_ack")
        guard let data = try? Data(contentsOf: url),
              let s = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !s.isEmpty else { return nil }
        return s
    }
}

extension Notification.Name {
    static let cleanphotosShot = Notification.Name("cleanphotosShot")
    static let cleanphotosEnterMultiselect = Notification.Name("cleanphotosEnterMultiselect")
    static let cleanphotosDragSelect = Notification.Name("cleanphotosDragSelect")
    static let cleanphotosHarnessMenu = Notification.Name("cleanphotosHarnessMenu")
}

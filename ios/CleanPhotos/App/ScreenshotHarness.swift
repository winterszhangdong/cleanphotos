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
        let ids = library.photosOrdered.prefix(6).map(\.id)
        if library.favoriteCount == 0, let first = ids.first {
            _ = library.favorite(ids: [first], showUndo: false)
        }
        if library.trashCount == 0, ids.count > 1 {
            _ = library.moveToTrash(ids: [ids[1]], showUndo: false)
        }
        if let markerId = ids.first {
            let albums = await AlbumLibrary.fetchUserAlbums()
            // 中文系统里微信默认相册标题是「微信」。截图库里没有这个标题时补一个。
            if !albums.contains(where: { $0.title == "微信" }) {
                _ = try? await AlbumLibrary.create(title: "微信", assetIds: [markerId])
                var sawLoading = false
                for _ in 0..<40 {
                    if library.isLoading { sawLoading = true }
                    if sawLoading && !library.isLoading { break }
                    try? await Task.sleep(nanoseconds: 200_000_000)
                }
            }
        }
        try? await Task.sleep(nanoseconds: 400_000_000)

        // 1) Photos grid (also cropped on host for the unseen-dot close-up)
        tab.wrappedValue = .photos
        reviewLaunch.wrappedValue = nil
        try? await Task.sleep(nanoseconds: 1_600_000_000)
        await waitForShot(named: "photos")

        // 2) Sort / filter menus
        NotificationCenter.default.post(name: .cleanphotosHarnessMenu, object: "sort")
        try? await Task.sleep(nanoseconds: 500_000_000)
        await waitForShot(named: "menu-sort")
        NotificationCenter.default.post(name: .cleanphotosHarnessMenu, object: "filter")
        try? await Task.sleep(nanoseconds: 500_000_000)
        await waitForShot(named: "menu-filter")
        NotificationCenter.default.post(name: .cleanphotosHarnessMenu, object: nil)
        try? await Task.sleep(nanoseconds: 300_000_000)
        NotificationCenter.default.post(name: .cleanphotosHarnessMenu, object: "language")
        try? await Task.sleep(nanoseconds: 600_000_000)
        await waitForShot(named: "language")
        NotificationCenter.default.post(name: .cleanphotosHarnessMenu, object: nil)
        try? await Task.sleep(nanoseconds: 400_000_000)

        await showScope("similar", library: library, settle: 4_000_000_000, shot: "similar")
        await showScope("largeFiles", library: library, settle: 500_000_000, shot: "large", untilSizes: true)
        await showScope("screenshots", library: library, settle: 1_200_000_000, shot: "screenshots", untilSizes: true)
        await showScope("chat", library: library, settle: 800_000_000, shot: "chat")
        await showScope("onThisDay", library: library, settle: 800_000_000, shot: "on-this-day")
        await showScope("immersive", library: library, settle: 800_000_000, shot: "immersive")
        NotificationCenter.default.post(name: .cleanphotosHarnessScope, object: "all")
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
        NotificationCenter.default.post(name: .cleanphotosHarnessFiling, object: nil)
        try? await Task.sleep(nanoseconds: 900_000_000)
        await waitForShot(named: "filing")
        NotificationCenter.default.post(name: .cleanphotosHarnessFiling, object: "close")
        try? await Task.sleep(nanoseconds: 400_000_000)
        tab.wrappedValue = .trash
        try? await Task.sleep(nanoseconds: 700_000_000)
        await waitForShot(named: "trash")
        NotificationCenter.default.post(name: .cleanphotosHarnessTrashPreview, object: nil)
        try? await Task.sleep(nanoseconds: 1_200_000_000)
        await waitForShot(named: "trash-preview")

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
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            await waitForShot(named: "undo")
            library.undoLast()
            try? await Task.sleep(nanoseconds: 400_000_000)
        }

        await waitForShot(named: "done")
    }

    @MainActor
    private static func showScope(_ raw: String, library: PhotoLibraryService, settle: UInt64, shot: String, untilSizes: Bool = false) async {
        NotificationCenter.default.post(name: .cleanphotosHarnessScope, object: raw)
        if untilSizes {
            for _ in 0..<40 where !library.sizesReady {
                try? await Task.sleep(nanoseconds: 200_000_000)
            }
        }
        try? await Task.sleep(nanoseconds: settle)
        await waitForShot(named: shot)
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
    static let cleanphotosDragSelect = Notification.Name("cleanphotosDragSelect")
    static let cleanphotosHarnessMenu = Notification.Name("cleanphotosHarnessMenu")
    /// 截图钩子：object 为 LibraryScope.rawValue，或 "immersive"。
    static let cleanphotosHarnessScope = Notification.Name("cleanphotosHarnessScope")
    /// 截图钩子：打开收藏页第一天的整理页。object 为 "close" 时关掉。
    static let cleanphotosHarnessFiling = Notification.Name("cleanphotosHarnessFiling")
    /// 截图钩子：打开垃圾桶第一张的大图预览。
    static let cleanphotosHarnessTrashPreview = Notification.Name("cleanphotosHarnessTrashPreview")
    /// 截图钩子：清理页打开全部照片里的第一段视频。不进 CLEANPHOTOS_SHOTS 那条序列。
    static let cleanphotosHarnessReviewVideo = Notification.Name("cleanphotosHarnessReviewVideo")
}

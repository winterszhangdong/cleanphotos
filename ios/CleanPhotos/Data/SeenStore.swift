import Foundation
import Combine
import UIKit

@MainActor
final class SeenStore: ObservableObject {
    private static let key = "cleanphotos.seenAssetIds"
    @Published private(set) var seenIds: Set<String>

    init() {
        let arr = UserDefaults.standard.stringArray(forKey: Self.key) ?? []
        seenIds = Set(arr)
        // 合并写入还没落盘时切后台 / 被杀前先写掉
        resignObserver = NotificationCenter.default.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.flush() }
        }
    }
    private var resignObserver: NSObjectProtocol?

    func isUnseen(_ id: String) -> Bool { !seenIds.contains(id) }

    func markSeen(_ ids: [String]) {
        var next = seenIds
        ids.forEach { next.insert($0) }
        guard next != seenIds else { return }
        seenIds = next
        if PerfLegacy.on { flush() } else { schedulePersist() }
    }

    /// 清理页每翻一页都会标记看过：几千个 id 的数组整组写 UserDefaults 很贵，合并成 0.5s 内最多一次。
    private var persistItem: DispatchWorkItem?
    private func schedulePersist() {
        persistItem?.cancel()
        let item = DispatchWorkItem { [weak self] in self?.flush() }
        persistItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5, execute: item)
    }

    func flush() {
        persistItem?.cancel(); persistItem = nil
        if PerfDryRun.on { return }
        UserDefaults.standard.set(Array(seenIds), forKey: Self.key)
    }
}

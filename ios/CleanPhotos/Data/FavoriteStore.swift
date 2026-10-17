import Foundation
import Combine

struct FavoriteEntry: Codable, Hashable {
    let photoId: String
    let favoritedAt: TimeInterval
}

@MainActor
final class FavoriteStore: ObservableObject {
    private static let key = "cleanphotos.favorites"
    @Published private(set) var entries: [String: FavoriteEntry] = [:]

    init() {
        if let data = UserDefaults.standard.data(forKey: Self.key),
           let decoded = try? JSONDecoder().decode([FavoriteEntry].self, from: data) {
            entries = Dictionary(uniqueKeysWithValues: decoded.map { ($0.photoId, $0) })
        }
    }

    func contains(_ id: String) -> Bool { entries[id] != nil }

    func add(_ ids: [String]) {
        let now = Date().timeIntervalSince1970
        var next = entries
        for id in ids where next[id] == nil {
            next[id] = FavoriteEntry(photoId: id, favoritedAt: now)
        }
        persist(next)
    }

    func remove(_ ids: [String]) {
        var next = entries
        ids.forEach { next.removeValue(forKey: $0) }
        persist(next)
    }

    private func persist(_ next: [String: FavoriteEntry]) {
        entries = next
        let arr = Array(next.values)
        if let data = try? JSONEncoder().encode(arr), !PerfDryRun.on {
            UserDefaults.standard.set(data, forKey: Self.key)
        }
    }
}

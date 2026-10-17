import Foundation
import Combine

struct TrashEntry: Codable, Hashable {
    let photoId: String
    let movedToTrashAt: TimeInterval
    let wasFavorited: Bool
    let byteSize: Int64
}

@MainActor
final class TrashStore: ObservableObject {
    private static let key = "cleanphotos.trash"
    @Published private(set) var entries: [String: TrashEntry] = [:]

    init() {
        if let data = UserDefaults.standard.data(forKey: Self.key),
           let decoded = try? JSONDecoder().decode([TrashEntry].self, from: data) {
            entries = Dictionary(uniqueKeysWithValues: decoded.map { ($0.photoId, $0) })
        }
    }

    var count: Int { entries.count }
    var totalBytes: Int64 { entries.values.reduce(0) { $0 + max(0, $1.byteSize) } }

    func contains(_ id: String) -> Bool { entries[id] != nil }
    func entry(_ id: String) -> TrashEntry? { entries[id] }

    func move(_ items: [(id: String, wasFavorited: Bool, byteSize: Int64)]) {
        let now = Date().timeIntervalSince1970
        var next = entries
        for item in items {
            next[item.id] = TrashEntry(
                photoId: item.id,
                movedToTrashAt: now,
                wasFavorited: item.wasFavorited,
                byteSize: item.byteSize
            )
        }
        persist(next)
    }

    func restore(_ ids: [String]) -> [TrashEntry] {
        var restored: [TrashEntry] = []
        var next = entries
        for id in ids {
            if let e = next.removeValue(forKey: id) { restored.append(e) }
        }
        persist(next)
        return restored
    }

    func remove(_ ids: [String]) {
        var next = entries
        ids.forEach { next.removeValue(forKey: $0) }
        persist(next)
    }

    private func persist(_ next: [String: TrashEntry]) {
        entries = next
        let arr = Array(next.values)
        if let data = try? JSONEncoder().encode(arr), !PerfDryRun.on {
            UserDefaults.standard.set(data, forKey: Self.key)
        }
    }
}

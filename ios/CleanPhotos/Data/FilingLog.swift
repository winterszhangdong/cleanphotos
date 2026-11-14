import Foundation
import Combine

/// 装袋记录（收藏页「已冲洗」）。UserDefaults，一张照片只留最近一次。
/// 字段：dayKey、asset id、相册标题、时间。不改 FavoriteStore。
@MainActor
final class FilingLog: ObservableObject {
    private static let key = "cleanphotos.filingLog"

    @Published private(set) var rows: [FilingLogRow] = []

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        guard let data = defaults.data(forKey: Self.key) else { return }
        guard let decoded = try? JSONDecoder().decode([FilingLogRow].self, from: data) else {
            preconditionFailure("装袋记录格式非法")
        }
        rows = decoded
    }

    func record(assetIds: [String], albumTitle: String, dayKey: String, at: TimeInterval = Date().timeIntervalSince1970) {
        let adding = assetIds.map { FilingLogRow(assetId: $0, albumTitle: albumTitle, dayKey: dayKey, filedAt: at) }
        persist(FilingLogRules.upsert(rows, adding: adding))
    }

    func remove(assetIds: [String]) {
        persist(FilingLogRules.remove(rows, ids: Set(assetIds)))
    }

    /// 完整访问权下，把图库里已经查不到的 id 清掉。查询异常时什么都不做。
    func pruneDisappeared(candidates: [String], found: Set<String>?, libraryCount: Int) {
        guard let drop = FilingPrune.idsToDrop(stored: candidates, found: found, libraryCount: libraryCount), !drop.isEmpty else { return }
        remove(assetIds: drop)
    }

    /// 按规范化标题取最后一次装进去的时间，给托盘排序用。
    func lastFiledAtByTitle() -> [String: TimeInterval] {
        var map: [String: TimeInterval] = [:]
        for row in rows {
            let key = AlbumNameRules.normalize(row.albumTitle)
            map[key] = max(map[key] ?? -.greatestFiniteMagnitude, row.filedAt)
        }
        return map
    }

    private func persist(_ next: [FilingLogRow]) {
        rows = next
        guard !PerfDryRun.on else { return }
        let data = try? JSONEncoder().encode(next)
        precondition(data != nil, "装袋记录编码失败")
        defaults.set(data, forKey: Self.key)
    }
}

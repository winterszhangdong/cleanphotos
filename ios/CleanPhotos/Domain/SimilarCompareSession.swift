import Foundation

/// 连着的对比页下滑合成一条撤回。过了期就另起，期限从这一滑重算。
struct SimilarSwipeUndo: Equatable {
    var ids: [String]
    var shownAt: Date

    static func merge(_ current: SimilarSwipeUndo?, id: String, now: Date, lifetime: TimeInterval) -> SimilarSwipeUndo {
        if let current, now.timeIntervalSince(current.shownAt) < lifetime {
            var ids = current.ids
            if !ids.contains(id) { ids.append(id) }
            return SimilarSwipeUndo(ids: ids, shownAt: now)
        }
        return SimilarSwipeUndo(ids: [id], shownAt: now)
    }
}

/// 对比页正在看的这一组。下滑过阈值把眼前这一张送进垃圾桶，并从这一页拿掉。
/// 已经标成移走也要拿掉，不能因为标记没变就停住。
struct SimilarCompareSession: Equatable {
    var photos: [String]
    var marks: [String: SimilarMark]
    var trashed: [String]
    var undo: SimilarSwipeUndo?

    /// 列表上至少两张才显示。只剩一张时对比页还可以留着看。
    static func showsOnList(photoCount: Int) -> Bool { photoCount >= 2 }

    /// 上滑留下。已经是留下，或不在这一页，则不变。
    mutating func keep(_ id: String) {
        guard photos.contains(id), marks[id] != .keep else { return }
        marks[id] = .keep
    }

    /// 下滑过阈值。不在这一页则不动。返回是否已经从对比页拿掉。
    @discardableResult
    mutating func trash(_ id: String, now: Date, lifetime: TimeInterval) -> Bool {
        guard let index = photos.firstIndex(of: id) else { return false }
        photos.remove(at: index)
        marks.removeValue(forKey: id)
        if !trashed.contains(id) { trashed.append(id) }
        undo = SimilarSwipeUndo.merge(undo, id: id, now: now, lifetime: lifetime)
        return true
    }

    static func routeIds(_ key: String) -> Set<String> {
        Set(key.split(separator: ",").map(String.init))
    }

    /// 路由上的组 key 可能还是最初那一串。先按路由 key，再按落在这串里的照片 id。
    /// 多条都对上时，和路由重叠更多的那条优先。
    static func remainderStored(
        routeKey: String,
        remainder: [String: CompareRemainder]
    ) -> (key: String, value: CompareRemainder)? {
        if let hit = remainder[routeKey] { return (routeKey, hit) }
        let ids = routeIds(routeKey)
        guard !ids.isEmpty else { return nil }
        let matches = remainder.filter { ids.contains($0.value.photoId) }
        guard let best = matches.max(by: { lhs, rhs in
            let left = routeIds(lhs.key).intersection(ids).count
            let right = routeIds(rhs.key).intersection(ids).count
            if left != right { return left < right }
            return lhs.key < rhs.key
        }) else { return nil }
        return (best.key, best.value)
    }

    /// 路由对上用这一组；对不上就跟重叠至少两张的后继。一张都不剩才没有。
    /// 组已经不在结果里时，用 remainder 把最后一张留在这一页，不把它加回列表。
    static func compareMatch(
        routeKey: String,
        groups: [SimilarGroupRef],
        hiddenIds: Set<String>,
        remainder: CompareRemainder?
    ) -> CompareMatch? {
        if let source = groups.first(where: { $0.key == routeKey }) ?? successor(routeKey: routeKey, groups: groups) {
            let photos = source.photoIds.filter { !hiddenIds.contains($0) }
            guard !photos.isEmpty else { return nil }
            return CompareMatch(sourceKey: source.key, photoIds: photos, fromRemainder: false)
        }
        guard let photo = remainder, !hiddenIds.contains(photo.photoId) else { return nil }
        return CompareMatch(sourceKey: routeKey, photoIds: [photo.photoId], fromRemainder: true)
    }

    /// 扫描之后，从列表消失、又没有并进新组的最后一张。已经下滑删掉的不算。
    static func soleRemainder(
        previous: [SimilarGroupRef],
        next: [SimilarGroupRef],
        hiddenIds: Set<String>,
        marks: [String: [String: SimilarMark]],
        kept: [String: CompareRemainder]
    ) -> [String: CompareRemainder] {
        let present = Set(next.flatMap(\.photoIds))
        var result: [String: CompareRemainder] = [:]
        for (key, item) in kept where !hiddenIds.contains(item.photoId) && !present.contains(item.photoId) {
            result[key] = item
        }
        for old in previous {
            if next.contains(where: { $0.key == old.key }) || successor(routeKey: old.key, groups: next) != nil {
                continue
            }
            let still = old.photoIds.filter { !hiddenIds.contains($0) && !present.contains($0) }
            guard still.count == 1, let photoId = still.first else { continue }
            let mark = marks[old.key]?[photoId] ?? (photoId == old.recommendedId ? SimilarMark.keep : .remove)
            result[old.key] = CompareRemainder(photoId: photoId, mark: mark)
        }
        return result
    }

    /// 新组至少盖住旧标记里的两张时，把还在的标记带过去。盖不住返回 nil，调用方用默认标记。
    static func migratedMarks(
        previous: [String: [String: SimilarMark]],
        photoIds: [String],
        recommendedId: String
    ) -> [String: SimilarMark]? {
        let donor = previous.values
            .map { marks in (marks, photoIds.filter { marks[$0] != nil }.count) }
            .filter { $0.1 >= 2 }
            .max { lhs, rhs in
                if lhs.1 != rhs.1 { return lhs.1 < rhs.1 }
                return lhs.0.count < rhs.0.count
            }?.0
        guard let donor else { return nil }
        var out: [String: SimilarMark] = [:]
        out.reserveCapacity(photoIds.count)
        for id in photoIds {
            out[id] = donor[id] ?? (id == recommendedId ? .keep : .remove)
        }
        return out
    }

    /// 上滑留下。已经是留下则原样返回。路由对不上返回 nil，不新增一条。
    static func keptRemainder(
        routeKey: String,
        photoId: String,
        remainder: [String: CompareRemainder]
    ) -> [String: CompareRemainder]? {
        guard let stored = remainderStored(routeKey: routeKey, remainder: remainder),
              stored.value.photoId == photoId else { return nil }
        if stored.value.mark == .keep { return remainder }
        var next = remainder
        next[stored.key] = CompareRemainder(photoId: photoId, mark: .keep)
        return next
    }

    /// 下滑删掉对上的那一张。对不上返回 nil。
    static func trashedRemainder(
        routeKey: String,
        photoId: String,
        remainder: [String: CompareRemainder]
    ) -> [String: CompareRemainder]? {
        guard let stored = remainderStored(routeKey: routeKey, remainder: remainder),
              stored.value.photoId == photoId else { return nil }
        var next = remainder
        next.removeValue(forKey: stored.key)
        return next
    }

    /// 扫描把组 key 换成少了几张、但仍有至少两张的新组时，跟过去。
    static func successor(routeKey: String, groups: [SimilarGroupRef]) -> SimilarGroupRef? {
        let ids = routeIds(routeKey)
        guard ids.count >= 2 else { return nil }
        let ranked: [(SimilarGroupRef, Int)] = groups.compactMap { group in
            let overlap = group.photoIds.reduce(into: 0) { if ids.contains($1) { $0 += 1 } }
            guard overlap >= 2 else { return nil }
            return (group, overlap)
        }
        return ranked.max { lhs, rhs in
            let left = Double(lhs.1) / Double(lhs.0.photoIds.count)
            let right = Double(rhs.1) / Double(rhs.0.photoIds.count)
            if left != right { return left < right }
            if lhs.1 != rhs.1 { return lhs.1 < rhs.1 }
            return lhs.0.key < rhs.0.key
        }?.0
    }
}

/// 扫描把组拿掉之后，对比页还留着的最后一张，以及它当时的标记。
struct CompareRemainder: Equatable {
    var photoId: String
    var mark: SimilarMark
}

struct SimilarGroupRef: Equatable {
    var key: String
    var photoIds: [String]
    var recommendedId: String
}

struct CompareMatch: Equatable {
    var sourceKey: String
    var photoIds: [String]
    var fromRemainder: Bool
}

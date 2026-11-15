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
}

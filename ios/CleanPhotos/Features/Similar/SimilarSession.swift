import Combine
import Foundation
import Photos
import UIKit

struct SimilarCard: Identifiable, Equatable {
    var id: String { key }
    var key: String
    var photos: [PhotoItem]
    var recommendedId: String
    var sharpness: [String: Double]
    var earliest: Date

    static func == (lhs: SimilarCard, rhs: SimilarCard) -> Bool {
        lhs.key == rhs.key && lhs.recommendedId == rhs.recommendedId && lhs.sharpness == rhs.sharpness
            && lhs.photos.map(\.id) == rhs.photos.map(\.id)
    }
}

struct SimilarKeptUndo: Identifiable, Equatable {
    let id = UUID()
    let record: String
    let groupKey: String
    let sampleId: String
    let shownAt = Date()
}

/// 对比页这一刻在看的内容。`onList` 为假时是扫描拿掉之后留下的最后一张，底部胶囊不提交。
struct SimilarCompareDeck: Equatable {
    var sourceKey: String
    var photos: [PhotoItem]
    var marks: [String: SimilarMark]
    var recommendedId: String
    var sharpness: [String: Double]
    var onList: Bool
}

private struct RemainderHold {
    var photo: PhotoItem
    var mark: SimilarMark
}

/// 这一进程里只比对一次。切走再回来直接用上次的分组和哈希。
@MainActor
final class SimilarSession: ObservableObject {
    static let shared = SimilarSession()

    enum Phase: Equatable { case idle, comparing, ready }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var done = 0
    @Published private(set) var total = 0
    @Published private(set) var groups: [SimilarCard] = []
    @Published private(set) var marks: [String: [String: SimilarMark]] = [:]
    @Published private(set) var keptUndo: SimilarKeptUndo?
    @Published private(set) var moving = false

    /// 列表不显示不足两张的组。自己滑到只剩一张时组还在 `groups` 里，对比页继续停着。
    /// 扫描把组拿掉之后，最后一张只留在 `remainder`，不写回 `groups`。
    var listedGroups: [SimilarCard] {
        groups.filter { SimilarCompareSession.showsOnList(photoCount: $0.photos.count) }
    }

    /// 扫描拿掉、或 key 收短之后，对比页还留着的最后一张。不出现在列表上。
    @Published private var remainder: [String: RemainderHold] = [:]

    private var library: PhotoLibraryService?
    private var scanTask: Task<Void, Never>?
    private var libraryTick: AnyCancellable?
    private let store: SimilarHandledStore
    private var hidden: [String: SimilarCard] = [:]

    private var hashMap: [String: UInt64] = [:]
    private var hashGot = 0
    private var hashExpected = 0
    private var hashCont: CheckedContinuation<[String: UInt64], Never>?

    private var sharpScores: [String: Double] = [:]
    private var sharpBytes: [String: Int64] = [:]
    private var sharpGot = 0
    private var sharpExpected = 0
    private var sharpCont: CheckedContinuation<(scores: [String: Double], bytes: [String: Int64]), Never>?

    private struct TrashWatch {
        let token: UUID
        let record: String?
        let groupKey: String
        let ids: Set<String>
        let bannerId: UUID?
        var seenInTrash = false
        var bannerGone = false
    }

    private var watch: TrashWatch?
    private var watchTimeout: Task<Void, Never>?

    /// 对比页下滑已经从组里拿掉、还可能撤回的照片。
    private struct SwipeSlot {
        var photo: PhotoItem
        var mark: SimilarMark
        var groupKey: String
        var recommendedId: String
        var sharpness: [String: Double]
        var earliest: Date
        var fromRemainder: Bool = false
    }

    private var swiped: [String: SwipeSlot] = [:]

    init(store: SimilarHandledStore = .shared) {
        self.store = store
    }

    func start(_ library: PhotoLibraryService) {
        self.library = library
        if libraryTick == nil {
            libraryTick = library.objectWillChange.sink { [weak self] _ in
                Task { @MainActor in self?.syncTrashUndo() }
            }
        }
        guard scanTask == nil else { return }
        phase = .comparing
        scanTask = Task { await self.scan() }
    }

    func mark(groupKey: String, photoId: String, mark: SimilarMark) {
        if let card = groups.first(where: { $0.key == groupKey }),
           card.photos.contains(where: { $0.id == photoId }) {
            var map = marks[groupKey] ?? [:]
            guard map[photoId] != mark else { return }
            map[photoId] = mark
            marks[groupKey] = map
            return
        }
        guard mark == .keep else { return }
        let domain = domainRemainder
        guard let updated = SimilarCompareSession.keptRemainder(routeKey: groupKey, photoId: photoId, remainder: domain),
              let stored = SimilarCompareSession.remainderStored(routeKey: groupKey, remainder: domain),
              updated[stored.key]?.mark != stored.value.mark,
              var hold = remainder[stored.key] else { return }
        hold.mark = .keep
        remainder[stored.key] = hold
    }

    /// 路由上的组还在就用它；扫描换成新组时跟过去。组没了就用还留着的最后一张。
    func deck(routeKey: String) -> SimilarCompareDeck? {
        let refs = groups.map {
            SimilarGroupRef(key: $0.key, photoIds: $0.photos.map(\.id), recommendedId: $0.recommendedId)
        }
        let domain = domainRemainder
        let held = SimilarCompareSession.remainderStored(routeKey: routeKey, remainder: domain)?.value
        guard let match = SimilarCompareSession.compareMatch(
            routeKey: routeKey,
            groups: refs,
            hiddenIds: [],
            remainder: held
        ) else { return nil }
        if match.fromRemainder {
            guard let stored = SimilarCompareSession.remainderStored(routeKey: routeKey, remainder: domain),
                  let hold = remainder[stored.key],
                  hold.photo.id == stored.value.photoId else { return nil }
            return SimilarCompareDeck(
                sourceKey: routeKey,
                photos: [hold.photo],
                marks: [hold.photo.id: hold.mark],
                recommendedId: hold.photo.id,
                sharpness: [:],
                onList: false
            )
        }
        guard let card = groups.first(where: { $0.key == match.sourceKey }) else { return nil }
        let photos = card.photos.filter { match.photoIds.contains($0.id) }
        guard !photos.isEmpty else { return nil }
        return SimilarCompareDeck(
            sourceKey: card.key,
            photos: photos,
            marks: marks[card.key] ?? [:],
            recommendedId: card.recommendedId,
            sharpness: card.sharpness,
            onList: true
        )
    }

    /// 对比页下滑过阈值：眼前这一张立刻进垃圾桶，并从这一组拿掉。已经标成移走也删。
    /// 连着的下滑并进同一条撤回，4 秒从这一滑重算。底部胶囊提交是另一条，不走这里。
    @discardableResult
    func swipeTrash(groupKey: String, photoId: String, now: Date = Date()) -> Bool {
        guard !moving, let library else { return false }
        guard let cardIndex = groups.firstIndex(where: { $0.key == groupKey }),
              groups[cardIndex].photos.contains(where: { $0.id == photoId }) else {
            return trashRemainder(routeKey: groupKey, photoId: photoId, now: now)
        }
        let card = groups[cardIndex]
        var deck = SimilarCompareSession(
            photos: card.photos.map(\.id),
            marks: marks[groupKey] ?? [:],
            trashed: [],
            undo: currentBurst(at: now)
        )
        guard deck.trash(photoId, now: now, lifetime: UndoBanner.lifetime) else { return false }
        guard let photoIndex = card.photos.firstIndex(where: { $0.id == photoId }) else { return false }
        let photo = card.photos[photoIndex]
        let slot = SwipeSlot(
            photo: photo,
            mark: (marks[groupKey] ?? [:])[photoId] ?? .remove,
            groupKey: card.key,
            recommendedId: card.recommendedId,
            sharpness: card.sharpness,
            earliest: card.earliest
        )
        var nextGroups = groups
        var nextMarks = marks
        var photos = card.photos
        photos.remove(at: photoIndex)
        if photos.isEmpty {
            nextGroups.remove(at: cardIndex)
            nextMarks[groupKey] = nil
        } else {
            var updated = card
            updated.photos = photos
            nextGroups[cardIndex] = updated
            nextMarks[groupKey] = deck.marks
        }
        groups = nextGroups
        marks = nextMarks
        swiped[photoId] = slot
        let moved = library.moveToTrash(ids: [photoId], showUndo: false)
        if moved != 1 {
            swiped.removeValue(forKey: photoId)
            reinsert(slot)
            return false
        }
        library.presentUndo(.movedToTrash(deck.undo?.ids ?? [photoId]), sample: photoId, burst: true)
        return true
    }

    /// 组已经不在扫描结果里。上滑只改 remainder 的标记；这里把这一张送进垃圾桶，不新建一组。
    private func trashRemainder(routeKey: String, photoId: String, now: Date) -> Bool {
        guard !moving, let library else { return false }
        let domain = domainRemainder
        guard SimilarCompareSession.trashedRemainder(routeKey: routeKey, photoId: photoId, remainder: domain) != nil,
              let stored = SimilarCompareSession.remainderStored(routeKey: routeKey, remainder: domain),
              let hold = remainder[stored.key] else { return false }
        let slot = SwipeSlot(
            photo: hold.photo,
            mark: hold.mark,
            groupKey: stored.key,
            recommendedId: hold.photo.id,
            sharpness: [:],
            earliest: hold.photo.creationDate,
            fromRemainder: true
        )
        remainder.removeValue(forKey: stored.key)
        swiped[photoId] = slot
        let moved = library.moveToTrash(ids: [photoId], showUndo: false)
        if moved != 1 {
            swiped.removeValue(forKey: photoId)
            reinsert(slot)
            return false
        }
        let burst = SimilarSwipeUndo.merge(currentBurst(at: now), id: photoId, now: now, lifetime: UndoBanner.lifetime)
        library.presentUndo(.movedToTrash(burst.ids), sample: photoId, burst: true)
        return true
    }

    private var domainRemainder: [String: CompareRemainder] {
        remainder.mapValues { CompareRemainder(photoId: $0.photo.id, mark: $0.mark) }
    }

    private func currentBurst(at now: Date) -> SimilarSwipeUndo? {
        guard let banner = library?.undoBanner, banner.burst,
              case .movedToTrash(let ids) = banner.kind,
              now.timeIntervalSince(banner.shownAt) < UndoBanner.lifetime else { return nil }
        return SimilarSwipeUndo(ids: ids, shownAt: banner.shownAt)
    }

    /// 移走的进垃圾桶。留下的还有至少两张时写已处理记录，组从列表消失。
    @discardableResult
    func commit(groupKey: String) -> Bool {
        guard !moving, let library, let card = groups.first(where: { $0.key == groupKey }) else { return false }
        let map = marks[groupKey] ?? [:]
        let removeIds = card.photos.map(\.id).filter { map[$0] != .keep }
        let keepIds = Set(card.photos.map(\.id).filter { map[$0] == .keep })
        moving = true
        defer { moving = false }
        if !removeIds.isEmpty {
            let moved = library.moveToTrash(ids: removeIds, showUndo: true)
            if moved != removeIds.count {
                if moved > 0 { library.undoLast() }
                return false
            }
        } else if library.undoBanner?.burst == true {
            // 提交盖住还没过期的下滑撤回。没有新的移走时，这条不再留着。
            library.clearUndo()
        }
        let record = SimilarRules.handledKey(remainingIds: keepIds)
        if let record { store.add(record) }
        hidden[card.key] = card
        groups.removeAll { $0.key == card.key }
        if removeIds.isEmpty, let record {
            keptUndo = SimilarKeptUndo(record: record, groupKey: card.key, sampleId: card.photos[0].id)
        } else if !removeIds.isEmpty {
            let banner = library.undoBanner
            var trashed = Set(removeIds)
            if case .movedToTrash(let ids) = banner?.kind { trashed = Set(ids) }
            watchTimeout?.cancel()
            watch = TrashWatch(
                token: UUID(),
                record: record,
                groupKey: card.key,
                ids: trashed,
                bannerId: banner?.id
            )
        }
        return true
    }

    func undoKept() {
        guard let undo = keptUndo else { return }
        if Date().timeIntervalSince(undo.shownAt) >= UndoBanner.lifetime {
            keptUndo = nil
            return
        }
        keptUndo = nil
        store.remove(undo.record)
        restore(undo.groupKey)
    }

    func expireKept(_ id: UUID) {
        guard keptUndo?.id == id else { return }
        keptUndo = nil
    }

    private func scan() async {
        guard let library else { return }
        await waitForLibrary(library)
        let snap = library.photosOrdered
        let refs: [SimilarPhotoRef] = snap.compactMap { photo in
            guard photo.asset.mediaType == .image, !photo.isFavorite, !photo.isTrashed else { return nil }
            guard photo.creationDate.timeIntervalSince1970 > 0 else { return nil }
            let w = photo.asset.pixelWidth
            let h = photo.asset.pixelHeight
            let pixels = (w > 0 && h > 0) ? Int64(w) * Int64(h) : 0
            return SimilarPhotoRef(id: photo.id, takenAt: photo.creationDate, pixelCount: pixels, byteSize: 0)
        }
        let candidates = SimilarRules.hashCandidateIds(refs)
        total = candidates.count
        done = 0
        let manager = library.imageManager
        let hashes = await collectHashes(candidates, manager: manager)
        let preliminary = SimilarRules.group(refs, hashes: hashes)
        let memberIds = unique(preliminary.flatMap(\.ids))
        total = candidates.count + memberIds.count
        let sharp = await collectSharpness(memberIds, manager: manager)
        var scored = refs
        for i in scored.indices {
            if let bytes = sharp.bytes[scored[i].id] { scored[i].byteSize = bytes }
        }
        let handled = store.records()
        let grouped = SimilarRules.group(scored, hashes: hashes, sharpness: sharp.scores)
            .filter { !SimilarRules.isSuppressed(groupIds: $0.ids, handledKeys: handled) }
        let byId = Dictionary(uniqueKeysWithValues: snap.map { ($0.id, $0) })
        publish(grouped, photos: byId)
        phase = .ready
    }

    private func waitForLibrary(_ library: PhotoLibraryService) async {
        var idleEmpty = 0
        while !Task.isCancelled {
            if library.isLoading || library.isPartial {
                idleEmpty = 0
                try? await Task.sleep(nanoseconds: 50_000_000)
                continue
            }
            if !library.photosOrdered.isEmpty { return }
            let access = library.authorization == .authorized || library.authorization == .limited
            if !access {
                try? await Task.sleep(nanoseconds: 50_000_000)
                continue
            }
            idleEmpty += 1
            if idleEmpty >= 20 { return }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
    }

    private func collectHashes(_ ids: [String], manager: PHCachingImageManager) async -> [String: UInt64] {
        let ids = unique(ids)
        guard !ids.isEmpty else { return [:] }
        hashMap = [:]
        hashGot = 0
        hashExpected = ids.count
        return await withCheckedContinuation { cont in
            hashCont = cont
            SimilarImaging.hash(ids: ids, manager: manager) { [weak self] id, hash in
                Task { @MainActor in self?.noteHash(id, hash: hash) }
            }
        }
    }

    private func noteHash(_ id: String, hash: UInt64?) {
        if let hash { hashMap[id] = hash }
        hashGot += 1
        done += 1
        guard hashGot == hashExpected, let cont = hashCont else { return }
        hashCont = nil
        cont.resume(returning: hashMap)
    }

    private func collectSharpness(
        _ ids: [String],
        manager: PHCachingImageManager
    ) async -> (scores: [String: Double], bytes: [String: Int64]) {
        let ids = unique(ids)
        guard !ids.isEmpty else { return ([:], [:]) }
        sharpScores = [:]
        sharpBytes = [:]
        sharpGot = 0
        sharpExpected = ids.count
        return await withCheckedContinuation { cont in
            sharpCont = cont
            SimilarImaging.sharpness(ids: ids, manager: manager) { [weak self] id, sample in
                Task { @MainActor in self?.noteSharp(id, sample: sample) }
            }
        }
    }

    private func noteSharp(_ id: String, sample: SimilarSharpSample) {
        if let score = sample.score { sharpScores[id] = score }
        sharpBytes[id] = sample.byteSize
        sharpGot += 1
        done += 1
        guard sharpGot == sharpExpected, let cont = sharpCont else { return }
        sharpCont = nil
        cont.resume(returning: (scores: sharpScores, bytes: sharpBytes))
    }

    private func publish(_ grouped: [SimilarGrouping], photos: [String: PhotoItem]) {
        let previousGroups = groups
        let previousMarks = marks
        var cards: [SimilarCard] = []
        var nextMarks = previousMarks
        for group in grouped {
            let items = group.ids.compactMap { photos[$0] }
            guard items.count >= 2 else { continue }
            let card = SimilarCard(
                key: group.key,
                photos: items,
                recommendedId: group.recommendedId,
                sharpness: group.sharpness,
                earliest: group.earliest
            )
            cards.append(card)
            if nextMarks[card.key] == nil {
                let ids = items.map(\.id)
                nextMarks[card.key] = SimilarCompareSession.migratedMarks(
                    previous: previousMarks,
                    photoIds: ids,
                    recommendedId: card.recommendedId
                ) ?? Dictionary(uniqueKeysWithValues: ids.map { id in
                    (id, id == card.recommendedId ? SimilarMark.keep : .remove)
                })
            }
        }
        let live = Set(cards.map(\.key))
        nextMarks = nextMarks.filter { live.contains($0.key) }
        let previousRefs = previousGroups.map {
            SimilarGroupRef(key: $0.key, photoIds: $0.photos.map(\.id), recommendedId: $0.recommendedId)
        }
        let nextRefs = cards.map {
            SimilarGroupRef(key: $0.key, photoIds: $0.photos.map(\.id), recommendedId: $0.recommendedId)
        }
        let nextDomain = SimilarCompareSession.soleRemainder(
            previous: previousRefs,
            next: nextRefs,
            hiddenIds: Set(swiped.keys),
            marks: previousMarks,
            kept: domainRemainder
        )
        var lookup: [String: PhotoItem] = [:]
        for card in previousGroups {
            for photo in card.photos { lookup[photo.id] = photo }
        }
        for hold in remainder.values { lookup[hold.photo.id] = hold.photo }
        var nextRemainder: [String: RemainderHold] = [:]
        for (key, item) in nextDomain {
            guard let photo = lookup[item.photoId] else { continue }
            nextRemainder[key] = RemainderHold(photo: photo, mark: item.mark)
        }
        // 先写下最后一张，再换组。对比页不会在两组都空的那一帧被关掉。
        remainder = nextRemainder
        marks = nextMarks
        groups = cards
    }

    private func restore(_ key: String) {
        guard let card = hidden.removeValue(forKey: key) else { return }
        groups.removeAll { $0.key == key }
        groups.append(card)
        groups.sort { a, b in
            if a.earliest != b.earliest { return a.earliest > b.earliest }
            return a.key < b.key
        }
    }

    private func syncTrashUndo() {
        syncSwipeUndo()
        guard var watch, let library else { return }
        let trash = Set(library.trashItems.map(\.id))
        if watch.ids.allSatisfy({ trash.contains($0) }) { watch.seenInTrash = true }
        let bannerGone = watch.bannerId == nil || library.undoBanner?.id != watch.bannerId
        if bannerGone && !watch.bannerGone {
            watch.bannerGone = true
            armWatchTimeout(watch.token)
        }
        self.watch = watch
        // 见过它们进了垃圾桶、又全部离开：撤回已经生效。没见过之前不在这里判断，
        // 移走后的列表刷新是异步的，刚提交时它们还不在 trashItems 里。
        if watch.seenInTrash && watch.ids.allSatisfy({ !trash.contains($0) }) {
            undoTrashWatch()
        }
    }

    private func armWatchTimeout(_ token: UUID) {
        watchTimeout?.cancel()
        watchTimeout = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled, self.watch?.token == token, let library = self.library else { return }
            let trash = Set(library.trashItems.map(\.id))
            let pending = self.watch?.ids ?? []
            if pending.allSatisfy({ !trash.contains($0) }) {
                // 横幅已经没了，照片也不在垃圾桶列表里：包括撤回发生在列表刷新之前的情况。
                self.undoTrashWatch()
            } else if self.watch?.token == token {
                self.watch = nil
            }
        }
    }

    /// 撤回把下滑的照片移出垃圾桶时放回这一组。横幅过期或被提交盖住，则不再放回。
    private func syncSwipeUndo() {
        guard let library, !swiped.isEmpty else { return }
        let returned = swiped.keys.filter { !library.trash.contains($0) }.sorted { a, b in
            photoOrder(swiped[a]!.photo, swiped[b]!.photo)
        }
        for id in returned {
            guard let slot = swiped.removeValue(forKey: id) else { continue }
            reinsert(slot)
        }
        guard !swiped.isEmpty else { return }
        let banner = library.undoBanner
        let covered = banner == nil || banner?.burst != true
        if covered && swiped.keys.allSatisfy({ library.trash.contains($0) }) {
            swiped.removeAll()
        }
    }

    private func reinsert(_ slot: SwipeSlot) {
        if slot.fromRemainder {
            remainder[slot.groupKey] = RemainderHold(photo: slot.photo, mark: slot.mark)
            return
        }
        var next = groups
        if let idx = next.firstIndex(where: { $0.key == slot.groupKey }) {
            var card = next[idx]
            if !card.photos.contains(where: { $0.id == slot.photo.id }) {
                card.photos.append(slot.photo)
                card.photos.sort(by: photoOrder)
            }
            next[idx] = card
        } else {
            next.append(SimilarCard(
                key: slot.groupKey,
                photos: [slot.photo],
                recommendedId: slot.recommendedId,
                sharpness: slot.sharpness,
                earliest: slot.earliest
            ))
            next.sort { a, b in
                if a.earliest != b.earliest { return a.earliest > b.earliest }
                return a.key < b.key
            }
        }
        groups = next
        var map = marks[slot.groupKey] ?? [:]
        map[slot.photo.id] = slot.mark
        marks[slot.groupKey] = map
    }

    private func photoOrder(_ a: PhotoItem, _ b: PhotoItem) -> Bool {
        if a.creationDate != b.creationDate { return a.creationDate < b.creationDate }
        return a.id < b.id
    }

    private func undoTrashWatch() {
        guard let watch else { return }
        if let record = watch.record { store.remove(record) }
        let key = watch.groupKey
        self.watch = nil
        watchTimeout?.cancel()
        restore(key)
    }

    private func unique(_ ids: [String]) -> [String] {
        var seen = Set<String>()
        return ids.filter { seen.insert($0).inserted }
    }
}

struct SimilarSharpSample: Sendable {
    var score: Double?
    var byteSize: Int64
}

private final class SimilarManagerBox: @unchecked Sendable {
    let manager: PHCachingImageManager
    init(_ manager: PHCachingImageManager) { self.manager = manager }
}

private final class SimilarCursor: @unchecked Sendable {
    private let lock = NSLock()
    private var index = 0
    func next() -> Int {
        lock.lock()
        defer { lock.unlock() }
        let value = index
        index += 1
        return value
    }
}

/// 像素哈希和清晰度。同时最多算 2 张。单张失败记成空，不让整页失败。
enum SimilarImaging {
    nonisolated static func hash(
        ids: [String],
        manager: PHCachingImageManager,
        onEach: @escaping @Sendable (String, UInt64?) -> Void
    ) {
        let box = SimilarManagerBox(manager)
        run(ids, work: { id -> UInt64? in
            guard let image = request(box.manager, id: id, side: 96) else { return nil }
            return SimilarPixels.dHash(image)
        }, onEach: onEach)
    }

    nonisolated static func sharpness(
        ids: [String],
        manager: PHCachingImageManager,
        onEach: @escaping @Sendable (String, SimilarSharpSample) -> Void
    ) {
        let box = SimilarManagerBox(manager)
        run(ids, work: { id -> SimilarSharpSample in
            let fetched = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil)
            guard let asset = fetched.firstObject else { return SimilarSharpSample(score: nil, byteSize: 0) }
            let bytes = AssetSizeCache.shared.bytes(for: asset)
            guard let image = request(box.manager, asset: asset, side: 512) else {
                return SimilarSharpSample(score: nil, byteSize: bytes)
            }
            return SimilarSharpSample(score: SimilarPixels.sharpness(image), byteSize: bytes)
        }, onEach: onEach)
    }

    private nonisolated static func run<T: Sendable>(
        _ ids: [String],
        work: @escaping @Sendable (String) -> T,
        onEach: @escaping @Sendable (String, T) -> Void
    ) {
        guard !ids.isEmpty else { return }
        let workers = min(SimilarRules.hashParallelism, ids.count)
        let cursor = SimilarCursor()
        let queue = DispatchQueue(label: "cleanphotos.similar", qos: .userInitiated, attributes: .concurrent)
        for _ in 0..<workers {
            queue.async {
                while true {
                    let index = cursor.next()
                    if index >= ids.count { return }
                    let id = ids[index]
                    onEach(id, work(id))
                }
            }
        }
    }

    private nonisolated static func request(_ manager: PHCachingImageManager, id: String, side: CGFloat) -> UIImage? {
        let fetched = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil)
        guard let asset = fetched.firstObject else { return nil }
        return request(manager, asset: asset, side: side)
    }

    private nonisolated static func request(_ manager: PHCachingImageManager, asset: PHAsset, side: CGFloat) -> UIImage? {
        let opts = PHImageRequestOptions()
        opts.deliveryMode = .highQualityFormat
        opts.resizeMode = .exact
        opts.isNetworkAccessAllowed = true
        opts.isSynchronous = true
        var image: UIImage?
        manager.requestImage(
            for: asset,
            targetSize: CGSize(width: side, height: side),
            contentMode: .aspectFit,
            options: opts
        ) { img, _ in
            image = img
        }
        return image
    }
}

enum SimilarPixels {
    static func dHash(_ image: UIImage) -> UInt64? {
        guard let luma = luma(image, width: SimilarRules.dHashWidth, height: SimilarRules.dHashHeight) else { return nil }
        return SimilarRules.dHash(luma: luma)
    }

    /// 长边约 512px 的灰度图。解出来比这小就用原尺寸；不到 3×3 不算。
    static func sharpness(_ image: UIImage) -> Double? {
        let orientedW = max(1, Int((image.size.width * max(image.scale, 1)).rounded()))
        let orientedH = max(1, Int((image.size.height * max(image.scale, 1)).rounded()))
        let long = max(orientedW, orientedH)
        let scale = long > 512 ? 512.0 / Double(long) : 1
        let w = max(1, Int((Double(orientedW) * scale).rounded()))
        let h = max(1, Int((Double(orientedH) * scale).rounded()))
        guard w >= 3, h >= 3, let luma = luma(image, width: w, height: h) else { return nil }
        return SimilarRules.laplacianVariance(luma: luma, width: w, height: h)
    }

    private static func luma(_ image: UIImage, width: Int, height: Int) -> [Int]? {
        guard width > 0, height > 0 else { return nil }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format)
        let rendered = renderer.image { _ in
            image.draw(in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        guard let cg = rendered.cgImage else { return nil }
        var buf = [UInt8](repeating: 0, count: width * height * 4)
        let drew = buf.withUnsafeMutableBytes { raw -> Bool in
            guard let base = raw.baseAddress,
                  let ctx = CGContext(
                    data: base,
                    width: width,
                    height: height,
                    bitsPerComponent: 8,
                    bytesPerRow: width * 4,
                    space: CGColorSpaceCreateDeviceRGB(),
                    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
                  ) else { return false }
            ctx.interpolationQuality = .medium
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drew else { return nil }
        var out: [Int] = []
        out.reserveCapacity(width * height)
        var i = 0
        while i + 2 < buf.count {
            let r = Int(buf[i])
            let g = Int(buf[i + 1])
            let b = Int(buf[i + 2])
            out.append((r * 77 + g * 150 + b * 29) >> 8)
            i += 4
        }
        return out.count == width * height ? out : nil
    }
}

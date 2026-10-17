import Foundation
import Photos

/// 线程安全的文件大小缓存：只在真正要显示/统计大小时才查 PHAssetResource。
final class AssetSizeCache: @unchecked Sendable {
    static let shared = AssetSizeCache()
    struct Info { let bytes: Int64; let name: String? }
    private var map: [String: Info] = [:]
    private let lock = NSLock()

    func bytes(for asset: PHAsset) -> Int64 { info(for: asset).bytes }

    /// 原始文件名（不含扩展名）；清理页元信息行用，和大小一次查出（PHAssetResource 在主线程上每张几毫秒）。
    func name(for asset: PHAsset) -> String? { info(for: asset).name }

    func info(for asset: PHAsset) -> Info {
        let id = asset.localIdentifier
        lock.lock()
        if let v = map[id] { lock.unlock(); return v }
        lock.unlock()
        let v = Self.compute(asset)
        lock.lock(); map[id] = v; lock.unlock()
        return v
    }

    /// 后台预热（比如清理页接下来几张），不阻塞主线程。
    func prefetch(_ assets: [PHAsset]) {
        guard !assets.isEmpty else { return }
        DispatchQueue.global(qos: .utility).async {
            for a in assets { _ = self.info(for: a) }
        }
    }

    private static func compute(_ asset: PHAsset) -> Info {
        let resources = PHAssetResource.assetResources(for: asset)
        let name = resources.first.map { ($0.originalFilename as NSString).deletingPathExtension }
        guard let r = resources.first(where: { $0.type == .photo || $0.type == .fullSizePhoto }) ?? resources.first else { return Info(bytes: 0, name: name) }
        let bytes = (r.value(forKey: "fileSize") as? NSNumber)?.int64Value ?? 0
        return Info(bytes: bytes, name: name)
    }
}

struct PhotoItem: Identifiable, Hashable {
    let id: String
    let asset: PHAsset
    let creationDate: Date
    var isUnseen: Bool
    var isFavorite: Bool
    var isTrashed: Bool
    /// 文件大小按需取（PHAssetResource 每张要几毫秒，加载时整库算一遍会卡十几秒），结果进 AssetSizeCache。
    var byteSize: Int64 { AssetSizeCache.shared.bytes(for: asset) }

    func hash(into hasher: inout Hasher) { hasher.combine(id) }
    static func == (lhs: PhotoItem, rhs: PhotoItem) -> Bool { lhs.id == rhs.id }
}

struct PhotoDaySection: Identifiable, Hashable {
    let id: String
    let date: Date
    let items: [PhotoItem]
    /// All items for the day (for review session / select-all), not just first 8.
    let allItems: [PhotoItem]
    let totalCount: Int
    let unseenCount: Int

    var isDone: Bool { unseenCount == 0 }
}

struct PhotoYearGroup: Identifiable, Hashable {
    let id: String
    let year: Int
    let days: [PhotoDaySection]
}

enum ReviewScope: Hashable {
    case allPhotos
    case favorites
}

enum UndoKind: Equatable {
    case favorited([String])
    case unfavorited([String])
    case movedToTrash([String])
    case restored([String])
}

struct UndoBanner: Equatable, Identifiable {
    let id = UUID()
    let kind: UndoKind
    let sampleId: String?
    /// 清理页单张操作的副文案（「可释放 2.4 MB」/「9月21日 · 第 3 张」）；照片页批量没有副文案。
    var subtitle: String? = nil
    /// true = 清理页单张（主文案「已收藏」），false = 批量（「已收藏 N 张」）。
    var single: Bool = false

    var isAmber: Bool {
        switch kind {
        case .favorited, .unfavorited: return true
        default: return false
        }
    }
}

/// 首页排序（Android PhotoSortOrder）。
enum PhotoSortOrder: String {
    case newestFirst, oldestFirst
}

/// 首页筛选（Android PhotoFilterMode）。
enum PhotoFilterMode: String {
    case allPhotos, unseenOnly
}

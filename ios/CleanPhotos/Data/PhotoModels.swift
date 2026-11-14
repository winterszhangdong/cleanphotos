import Foundation
import Photos

/// 线程安全的文件大小缓存：只在真正要显示/统计大小时才查 PHAssetResource。
final class AssetSizeCache: @unchecked Sendable {
    static let shared = AssetSizeCache()
    struct Info { let bytes: Int64; let name: String?; let originalFilename: String? }
    private var map: [String: Info] = [:]
    private let lock = NSLock()

    func bytes(for asset: PHAsset) -> Int64 { info(for: asset).bytes }

    /// 已经算过的大小（没算过返回 nil，不触发 PHAssetResource 查询）。
    func cachedBytes(_ id: String) -> Int64? {
        lock.lock(); defer { lock.unlock() }
        return map[id]?.bytes
    }

    /// 已经算过的原始文件名（含扩展名）。没算过返回 nil，不查 PHAssetResource。
    func cachedOriginalFilename(_ id: String) -> String? {
        lock.lock(); defer { lock.unlock() }
        return map[id]?.originalFilename
    }

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
        let original = resources.first?.originalFilename
        let name = original.map { ($0 as NSString).deletingPathExtension }
        guard let r = resources.first(where: { $0.type == .photo || $0.type == .fullSizePhoto || $0.type == .video }) ?? resources.first else {
            return Info(bytes: 0, name: name, originalFilename: original)
        }
        // fileSize 是非公开属性：先确认还在，免得哪个系统版本去掉后 value(forKey:) 抛 NSUnknownKeyException 直接崩
        guard r.responds(to: NSSelectorFromString("fileSize")) else { return Info(bytes: 0, name: name, originalFilename: original) }
        let bytes = (r.value(forKey: "fileSize") as? NSNumber)?.int64Value ?? 0
        return Info(bytes: bytes, name: name, originalFilename: original)
    }
}

struct PhotoItem: Identifiable, Hashable {
    let id: String
    let asset: PHAsset
    let creationDate: Date
    var isUnseen: Bool
    var isFavorite: Bool
    var isTrashed: Bool
    /// 视频走和照片同一套网格。时长只在视频上有意义。
    var isVideo: Bool = false
    var duration: TimeInterval = 0
    /// PHAsset.mediaSubtypes 含 .photoScreenshot。相册标题和文件名另算，不在这里。
    var systemScreenshot: Bool = false
    /// 文件大小按需取（PHAssetResource 每张要几毫秒，加载时整库算一遍会卡十几秒），结果进 AssetSizeCache。
    var byteSize: Int64 { AssetSizeCache.shared.bytes(for: asset) }

    func hash(into hasher: inout Hasher) { hasher.combine(id) }
    static func == (lhs: PhotoItem, rhs: PhotoItem) -> Bool { lhs.id == rhs.id }
}

enum SectionHeaderKind: Hashable {
    case day, month, none
}

struct PhotoDaySection: Identifiable, Hashable {
    let id: String
    let date: Date
    /// 网格上露出来的缩略图。全部不截断；那年今天默认前 8 张。
    let items: [PhotoItem]
    /// 这一组在当前过滤后的全部照片（含没画出来的），全选和进清理页用它。
    let allItems: [PhotoItem]
    /// 统计池里的张数：当前范围，还没叠「只看没看过」。
    let totalCount: Int
    /// 统计池里还没看过的张数。
    let unseenCount: Int
    /// 被 8 张上限挡住的其余照片。不截断、按月、平铺为空。
    var overflow: [PhotoItem] = []
    var header: SectionHeaderKind = .day

    var isDone: Bool { unseenCount == 0 }
    var seenCount: Int { totalCount - unseenCount }
}

/// 某一范围已经算好的列表。左右滑时邻居页直接用它，不用等停稳再重算。
struct ScopePage {
    var groups: [PhotoYearGroup]
    var flat: [PhotoItem]
    var scopeCount: Int
    var visibleCount: Int
    var visibleBytes: Int64
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
    /// 撤回条存活时长（PhotoLibraryService 的计时和胶囊底部倒计时线共用）。
    static let lifetime: Double = 4
    let id = UUID()
    let kind: UndoKind
    let sampleId: String?
    /// 出现时间：胶囊被暂时藏起（拖动 / 小结页）再露出时，倒计时线从剩余时间接着走，而不是重新满格。
    let shownAt = Date()
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

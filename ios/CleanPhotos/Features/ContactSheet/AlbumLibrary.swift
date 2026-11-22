import Foundation
import Photos

/// 用户自建相册（PHAssetCollectionType.album / .albumRegular）。智能相册加不进去，不列在托盘里。
struct AlbumMembership: Hashable {
    var id: String
    var title: String
    var count: Int
    var memberIds: Set<String>
    /// 按拍摄时间降序，最新在前，最多 5 张。
    var recentIds: [String] = []
    var recentAssets: [PHAsset] = []

    func hash(into hasher: inout Hasher) {
        hasher.combine(id)
        hasher.combine(title)
        hasher.combine(count)
        hasher.combine(memberIds)
        hasher.combine(recentIds)
    }

    static func == (lhs: AlbumMembership, rhs: AlbumMembership) -> Bool {
        lhs.id == rhs.id && lhs.title == rhs.title && lhs.count == rhs.count
            && lhs.memberIds == rhs.memberIds && lhs.recentIds == rhs.recentIds
    }
}

struct AlbumAddResult: Sendable {
    var albumId: String
    var added: [String]
    var failed: [String]
}

enum AlbumWriteError: Error {
    case failed
}

enum AlbumLibrary {
    /// 图库里的自建相册：标题、张数、成员 localIdentifier。
    static func fetchUserAlbums() async -> [AlbumMembership] {
        await Task.detached(priority: .userInitiated) {
            let collections = PHAssetCollection.fetchAssetCollections(with: .album, subtype: .albumRegular, options: nil)
            let box = MembershipBox()
            collections.enumerateObjects { collection, _, _ in
                let members = Self.members(in: collection)
                box.albums.append(AlbumMembership(
                    id: collection.localIdentifier,
                    title: collection.localizedTitle ?? "",
                    count: members.ids.count,
                    memberIds: members.ids,
                    recentIds: members.recent.map(\.localIdentifier),
                    recentAssets: members.recent
                ))
            }
            return box.albums
        }.value
    }

    /// 完整访问权下回查这些 id 还在不在。查不到的集合是空，不是 nil（调用方再用 FilingPrune 判断要不要信）。
    static func foundIds(_ ids: [String]) async -> Set<String> {
        guard !ids.isEmpty else { return [] }
        return await Task.detached(priority: .utility) {
            let opts = PHFetchOptions()
            opts.includeHiddenAssets = true
            let result = PHAsset.fetchAssets(withLocalIdentifiers: ids, options: opts)
            return Self.identifiers(result)
        }.value
    }

    /// 全量成员 id，另按 creationDate 降序留前 5 张资产。
    private static func members(in collection: PHAssetCollection) -> (ids: Set<String>, recent: [PHAsset]) {
        let opts = PHFetchOptions()
        opts.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        let result = PHAsset.fetchAssets(in: collection, options: opts)
        var ids = Set<String>()
        var recent: [PHAsset] = []
        recent.reserveCapacity(5)
        result.enumerateObjects { asset, _, _ in
            ids.insert(asset.localIdentifier)
            if recent.count < 5 { recent.append(asset) }
        }
        return (ids, recent)
    }

    static func add(assetIds: [String], albumId: String) async throws -> AlbumAddResult {
        let assets = fetchAssets(assetIds)
        let found = identifiers(assets)
        let missing = assetIds.filter { !found.contains($0) }
        guard assets.count > 0 else {
            return AlbumAddResult(albumId: albumId, added: [], failed: assetIds)
        }
        guard let collection = PHAssetCollection.fetchAssetCollections(withLocalIdentifiers: [albumId], options: nil).firstObject else {
            throw AlbumWriteError.failed
        }
        let flag = Flag()
        try await PHPhotoLibrary.shared().performChanges {
            if let request = PHAssetCollectionChangeRequest(for: collection) {
                request.addAssets(assets)
                flag.ok = true
            }
        }
        guard flag.ok else { throw AlbumWriteError.failed }
        return AlbumAddResult(albumId: albumId, added: Array(found), failed: missing)
    }

    /// 建在用户图库里。系统不让指定 Pictures/ 还是 DCIM/。
    static func create(title: String, assetIds: [String]) async throws -> AlbumAddResult {
        let assets = fetchAssets(assetIds)
        let found = identifiers(assets)
        let missing = assetIds.filter { !found.contains($0) }
        guard assets.count > 0 else {
            throw AlbumWriteError.failed
        }
        let box = PlaceholderBox()
        try await PHPhotoLibrary.shared().performChanges {
            let request = PHAssetCollectionChangeRequest.creationRequestForAssetCollection(withTitle: title)
            request.addAssets(assets)
            box.value = request.placeholderForCreatedAssetCollection
        }
        guard let id = box.value?.localIdentifier else { throw AlbumWriteError.failed }
        return AlbumAddResult(albumId: id, added: Array(found), failed: missing)
    }

    /// 只把这些资产移出相册，不删除照片本身。相册不在或一张都没移出时抛错。
    static func remove(assetIds: [String], albumId: String) async throws {
        guard !assetIds.isEmpty else { return }
        let assets = fetchAssets(assetIds)
        guard assets.count > 0 else { return }
        guard let collection = PHAssetCollection.fetchAssetCollections(withLocalIdentifiers: [albumId], options: nil).firstObject else {
            throw AlbumWriteError.failed
        }
        let flag = Flag()
        try await PHPhotoLibrary.shared().performChanges {
            if let request = PHAssetCollectionChangeRequest(for: collection) {
                request.removeAssets(assets)
                flag.ok = true
            }
        }
        guard flag.ok else { throw AlbumWriteError.failed }
    }

    /// 装袋成功之后另做一次。用户拒绝或系统失败时返回 false，调用方不回滚相册。
    static func markSystemFavorite(_ assetIds: [String]) async -> Bool {
        let assets = fetchAssets(assetIds)
        guard assets.count > 0 else { return false }
        do {
            try await PHPhotoLibrary.shared().performChanges {
                assets.enumerateObjects { asset, _, _ in
                    PHAssetChangeRequest(for: asset).isFavorite = true
                }
            }
            return true
        } catch {
            return false
        }
    }

    private static func fetchAssets(_ ids: [String]) -> PHFetchResult<PHAsset> {
        let opts = PHFetchOptions()
        opts.includeHiddenAssets = true
        return PHAsset.fetchAssets(withLocalIdentifiers: ids, options: opts)
    }

    private static func identifiers(_ assets: PHFetchResult<PHAsset>) -> Set<String> {
        let box = IdBox()
        assets.enumerateObjects { asset, _, _ in box.ids.insert(asset.localIdentifier) }
        return box.ids
    }

    private final class MembershipBox: @unchecked Sendable {
        var albums: [AlbumMembership] = []
    }

    private final class IdBox: @unchecked Sendable {
        var ids = Set<String>()
    }

    private final class Flag: @unchecked Sendable {
        var ok = false
    }

    private final class PlaceholderBox: @unchecked Sendable {
        var value: PHObjectPlaceholder?
    }
}

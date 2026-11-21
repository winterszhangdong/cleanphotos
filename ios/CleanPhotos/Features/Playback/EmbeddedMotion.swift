import Foundation
import Photos

/// 厂商嵌在 JPEG / HEIC 里的视频。只在要播的那一张（以及下一张预取）和相似对比的当前大图上读文件。
/// 读失败抛错，调用方当成静图。识别为「不是动态照片」会记住；失败不记住。
enum EmbeddedMotionCache {
    private struct Memo {
        var video: MotionPhotoVideo?
        var file: URL?
        var located: Bool
    }

    private struct Source {
        var url: URL
        var temporary: Bool
        var scoped: Bool
        func close() {
            if scoped { url.stopAccessingSecurityScopedResource() }
            if temporary { try? FileManager.default.removeItem(at: url) }
        }
    }

    private static let lock = NSLock()
    private static var memo: [String: Memo] = [:]
    private static var order: [String] = []
    private static let cap = 256
    private static let embeddable = ["jpg", "jpeg", "heic", "heif", "avif"]

    static func prefetch(_ asset: PHAsset) {
        guard asset.mediaType == .image, !asset.mediaSubtypes.contains(.photoLive) else { return }
        Task.detached(priority: .utility) {
            _ = try? await file(for: asset)
        }
    }

    /// 只定位，不拷视频。相似对比用来决定要不要写「动态」。
    static func locate(_ asset: PHAsset) async throws -> MotionPhotoVideo? {
        guard asset.mediaType == .image else { return nil }
        let key = asset.localIdentifier
        if let cached = cached(key), cached.located { return cached.video }
        guard let source = try await open(asset) else {
            store(key, Memo(video: nil, file: nil, located: true))
            return nil
        }
        defer { source.close() }
        try Task.checkCancellation()
        let video = try MotionPhotoLocator.locate(FileBytes(url: source.url))
        try Task.checkCancellation()
        store(key, Memo(video: video, file: nil, located: true))
        return video
    }

    /// 定位并把视频段拷到临时文件，给 AVPlayer 播。
    static func file(for asset: PHAsset) async throws -> URL? {
        guard asset.mediaType == .image else { return nil }
        let key = asset.localIdentifier
        if let cached = cached(key) {
            if cached.located, cached.video == nil { return nil }
            if let file = cached.file, FileManager.default.fileExists(atPath: file.path) { return file }
            if cached.located, let video = cached.video {
                return try await materialize(asset, video: video, key: key)
            }
        }
        guard let source = try await open(asset) else {
            store(key, Memo(video: nil, file: nil, located: true))
            return nil
        }
        defer { source.close() }
        try Task.checkCancellation()
        let bytes = try FileBytes(url: source.url)
        let video = try MotionPhotoLocator.locate(bytes)
        guard let video else {
            try Task.checkCancellation()
            store(key, Memo(video: nil, file: nil, located: true))
            return nil
        }
        try Task.checkCancellation()
        let url = try copySegment(from: bytes, video: video, key: key)
        try Task.checkCancellation()
        store(key, Memo(video: video, file: url, located: true))
        return url
    }

    private static func materialize(_ asset: PHAsset, video: MotionPhotoVideo, key: String) async throws -> URL? {
        guard let source = try await open(asset) else { return nil }
        defer { source.close() }
        try Task.checkCancellation()
        let url = try copySegment(from: FileBytes(url: source.url), video: video, key: key)
        try Task.checkCancellation()
        store(key, Memo(video: video, file: url, located: true))
        return url
    }

    private static func cached(_ key: String) -> Memo? {
        lock.lock()
        defer { lock.unlock() }
        guard let found = memo[key] else { return nil }
        if let index = order.firstIndex(of: key) {
            order.remove(at: index)
            order.append(key)
        }
        return found
    }

    private static func store(_ key: String, _ value: Memo) {
        lock.lock()
        defer { lock.unlock() }
        memo[key] = value
        if let index = order.firstIndex(of: key) { order.remove(at: index) }
        order.append(key)
        while order.count > cap {
            let eldest = order.removeFirst()
            memo[eldest] = nil
        }
    }

    /// 有本地路径就随机读。没有路径时，只有看起来像 JPEG / HEIC 的原文件才整段拷出来再读。
    private static func open(_ asset: PHAsset) async throws -> Source? {
        let resources = PHAssetResource.assetResources(for: asset)
        guard let resource = resources.first(where: { $0.type == .photo })
                ?? resources.first(where: { $0.type == .fullSizePhoto }) else {
            return nil
        }
        if let url = directFileURL(resource) {
            let scoped = url.startAccessingSecurityScopedResource()
            return Source(url: url, temporary: false, scoped: scoped)
        }
        let ext = (resource.originalFilename as NSString).pathExtension.lowercased()
        guard embeddable.contains(ext) else { return nil }
        let url = try await stream(resource)
        return Source(url: url, temporary: true, scoped: false)
    }

    /// `privateFileURL` 和文件大小一样是非公开属性。先确认选择子还在，避免缺了直接崩。
    private static func directFileURL(_ resource: PHAssetResource) -> URL? {
        let key = "privateFileURL"
        guard resource.responds(to: NSSelectorFromString(key)) else { return nil }
        guard let url = resource.value(forKey: key) as? URL, url.isFileURL else { return nil }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard FileManager.default.isReadableFile(atPath: url.path) else { return nil }
        return url
    }

    private static func stream(_ resource: PHAssetResource) async throws -> URL {
        let folder = try folderURL()
        let dest = folder.appendingPathComponent("orig-\(UUID().uuidString)")
        FileManager.default.createFile(atPath: dest.path, contents: nil)
        let handle = try FileHandle(forWritingTo: dest)
        let gate = StreamGate()
        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = false
        let manager = PHAssetResourceManager.default()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<URL, Error>) in
                gate.arm(cont)
                let idBox = ResourceID()
                let id = manager.requestData(for: resource, options: options, dataReceivedHandler: { data in
                    guard !gate.cancelled else { return }
                    do { try handle.write(contentsOf: data) }
                    catch {
                        gate.writeError = error
                        if let current = idBox.value { manager.cancelDataRequest(current) }
                    }
                }, completionHandler: { error in
                    try? handle.close()
                    if gate.cancelled {
                        try? FileManager.default.removeItem(at: dest)
                        gate.fail(CancellationError())
                        return
                    }
                    if let writeError = gate.writeError {
                        try? FileManager.default.removeItem(at: dest)
                        gate.fail(writeError)
                        return
                    }
                    if let error {
                        try? FileManager.default.removeItem(at: dest)
                        gate.fail(error)
                        return
                    }
                    gate.succeed(dest)
                })
                idBox.value = id
                gate.request = id
                if gate.cancelled || gate.writeError != nil { manager.cancelDataRequest(id) }
            }
        } onCancel: {
            gate.cancelled = true
            if let id = gate.request { manager.cancelDataRequest(id) }
            gate.fail(CancellationError())
        }
    }

    private static func copySegment(from bytes: FileBytes, video: MotionPhotoVideo, key: String) throws -> URL {
        let folder = try folderURL()
        let safe = key.replacingOccurrences(of: "/", with: "_")
        let dest = folder.appendingPathComponent("\(safe)-\(video.offset)-\(video.length).mp4")
        if FileManager.default.fileExists(atPath: dest.path) {
            try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: dest.path)
            return dest
        }
        let partial = folder.appendingPathComponent(UUID().uuidString + ".part")
        FileManager.default.createFile(atPath: partial.path, contents: nil)
        do {
            let out = try FileHandle(forWritingTo: partial)
            defer { try? out.close() }
            var position = video.offset
            var remaining = video.length
            while remaining > 0 {
                try Task.checkCancellation()
                let count = Int(min(remaining, 256 * 1024))
                let chunk = try bytes.read(position: position, length: count)
                try out.write(contentsOf: chunk)
                position += Int64(count)
                remaining -= Int64(count)
            }
        } catch {
            try? FileManager.default.removeItem(at: partial)
            throw error
        }
        if FileManager.default.fileExists(atPath: dest.path) {
            try? FileManager.default.removeItem(at: partial)
            return dest
        }
        do {
            try FileManager.default.moveItem(at: partial, to: dest)
        } catch {
            try? FileManager.default.removeItem(at: partial)
            if FileManager.default.fileExists(atPath: dest.path) { return dest }
            throw error
        }
        trim(folder)
        return dest
    }

    private static func folderURL() throws -> URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("cleanphotos-motion", isDirectory: true)
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }

    private static func trim(_ folder: URL) {
        let files = (try? FileManager.default.contentsOfDirectory(
            at: folder, includingPropertiesForKeys: [.contentModificationDateKey]
        ))?.filter { $0.pathExtension == "mp4" } ?? []
        guard files.count > 8 else { return }
        let dated = files.map { url -> (URL, Date) in
            let date = (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantPast
            return (url, date)
        }.sorted { $0.1 < $1.1 }
        for item in dated.dropLast(8) {
            try? FileManager.default.removeItem(at: item.0)
        }
    }
}

private final class ResourceID: @unchecked Sendable {
    var value: PHAssetResourceDataRequestID?
}

private final class StreamGate: @unchecked Sendable {
    var request: PHAssetResourceDataRequestID?
    var cancelled = false
    var writeError: Error?
    private var continuation: CheckedContinuation<URL, Error>?
    private var resumed = false
    private let lock = NSLock()

    func arm(_ cont: CheckedContinuation<URL, Error>) {
        lock.lock()
        continuation = cont
        lock.unlock()
    }

    func succeed(_ url: URL) { finish { $0.resume(returning: url) } }
    func fail(_ error: Error) { finish { $0.resume(throwing: error) } }

    private func finish(_ body: (CheckedContinuation<URL, Error>) -> Void) {
        lock.lock()
        guard !resumed, let continuation else {
            lock.unlock()
            return
        }
        resumed = true
        self.continuation = nil
        lock.unlock()
        body(continuation)
    }
}

import Foundation

enum VideoCompressWriteError: Error, Equatable {
    case unsupported
    case cancelled
    case failed
}

enum VideoCompressFailure: Error, Equatable {
    case notOnDevice
    case unsupported
    case alreadySmall
    case notEnoughSpace
    case durationMismatch
    case cancelled
    case failed
}

struct VideoCompressSource: Equatable {
    var id: String
    var creationDate: Date?
    var latitude: Double?
    var longitude: Double?
    var originalFilename: String?
    var appFavorite: Bool
    var systemFavorite: Bool
}

struct VideoCompressMediaFacts: Equatable {
    var width: Int
    var height: Int
    /// 播放帧率。nil 或 ≤0 按 30。
    var framesPerSecond: Double?
    var byteSize: Int64
    var duration: TimeInterval
}

enum VideoCompressMedia: Equatable {
    case notOnDevice
    case unsupported
    case ready(VideoCompressMediaFacts)
}

struct VideoCompressSaveRequest: Equatable {
    var fileURL: URL
    var filename: String
    var creationDate: Date?
    var latitude: Double?
    var longitude: Double?
    var systemFavorite: Bool
}

/// 假 writer 能测的编排：成功才收藏、记看过、移入垃圾桶；失败、取消、时长不符、不在本机则都不做。
enum VideoCompressJob {
    @MainActor
    static func run(
        source: VideoCompressSource,
        media: VideoCompressMedia,
        freeBytes: Int64,
        makeTemp: () -> URL,
        write: (_ plan: VideoCompressPlan.Output, _ url: URL, _ onProgress: @escaping (Double) -> Void) async throws -> TimeInterval,
        save: (VideoCompressSaveRequest) async throws -> String,
        addFavorite: (String) -> Void,
        markSeen: (String) -> Void,
        moveToTrash: (String) -> Void,
        deleteFile: (URL) -> Void,
        isCancelled: () -> Bool,
        onProgress: @escaping (Double) -> Void
    ) async -> Result<String, VideoCompressFailure> {
        if isCancelled() { return .failure(.cancelled) }
        let facts: VideoCompressMediaFacts
        switch media {
        case .notOnDevice:
            return .failure(.notOnDevice)
        case .unsupported:
            return .failure(.unsupported)
        case .ready(let ready):
            facts = ready
        }
        guard facts.duration > 0, facts.duration.isFinite, facts.width > 0, facts.height > 0 else {
            return .failure(.unsupported)
        }
        let plan = VideoCompressPlan.output(
            width: facts.width,
            height: facts.height,
            framesPerSecond: facts.framesPerSecond,
            byteSize: facts.byteSize,
            duration: facts.duration
        )
        if VideoCompressPlan.shouldSkip(
            width: facts.width,
            height: facts.height,
            byteSize: facts.byteSize,
            duration: facts.duration,
            output: plan
        ) {
            return .failure(.alreadySmall)
        }
        let estimate = VideoCompressPlan.estimatedBytes(videoBitrate: plan.videoBitrate, duration: facts.duration)
        guard VideoCompressPlan.hasRoom(freeBytes: freeBytes, estimated: estimate) else {
            return .failure(.notEnoughSpace)
        }
        if isCancelled() { return .failure(.cancelled) }

        let url = makeTemp()
        let outputDuration: TimeInterval
        do {
            outputDuration = try await write(plan, url) { fraction in
                onProgress(min(1, max(0, fraction)))
            }
        } catch VideoCompressWriteError.cancelled {
            deleteFile(url)
            return .failure(.cancelled)
        } catch is CancellationError {
            deleteFile(url)
            return .failure(.cancelled)
        } catch VideoCompressWriteError.unsupported {
            deleteFile(url)
            return .failure(.unsupported)
        } catch {
            deleteFile(url)
            return .failure(.failed)
        }
        if isCancelled() {
            deleteFile(url)
            return .failure(.cancelled)
        }
        guard VideoCompressPlan.durationAccepted(original: facts.duration, output: outputDuration) else {
            deleteFile(url)
            return .failure(.durationMismatch)
        }
        let request = VideoCompressSaveRequest(
            fileURL: url,
            filename: VideoCompressPlan.outputFilename(source.originalFilename),
            creationDate: source.creationDate,
            latitude: source.latitude,
            longitude: source.longitude,
            systemFavorite: source.systemFavorite
        )
        let newId: String
        do {
            newId = try await save(request)
        } catch VideoCompressWriteError.cancelled {
            deleteFile(url)
            return .failure(.cancelled)
        } catch is CancellationError {
            deleteFile(url)
            return .failure(.cancelled)
        } catch {
            deleteFile(url)
            return .failure(.failed)
        }
        deleteFile(url)
        if source.appFavorite { addFavorite(newId) }
        markSeen(newId)
        moveToTrash(source.id)
        return .success(newId)
    }
}

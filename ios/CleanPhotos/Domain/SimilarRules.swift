import Foundation

/// 相似照片的纯规则。只依赖 Foundation，给单测和界面共用。
/// 时间窗和汉明距离都是闭区间：刚好 10 秒、距离刚好 10 仍算同一组。
/// 只和前一张比；取图失败、没有哈希的不参与比较，两侧仍在 10 秒内可以连上。
enum SimilarMark: String, Equatable, Sendable {
    case keep
    case remove
}

struct SimilarPhotoRef: Equatable, Sendable {
    var id: String
    var takenAt: Date?
    var pixelCount: Int64
    var byteSize: Int64
    var isImage: Bool = true
}

struct SimilarGrouping: Equatable, Sendable {
    var ids: [String]
    var recommendedId: String
    var sharpness: [String: Double]
    /// 组里最早一张的拍摄时间。列表按它倒序。
    var earliest: Date

    var key: String { ids.joined(separator: ",") }
}

enum SimilarRules {
    static let maxGap: TimeInterval = 10
    static let maxHamming = 10
    static let hashParallelism = 2
    static let dHashWidth = 9
    static let dHashHeight = 8
    static let dHashLength = dHashWidth * dHashHeight

    static func hamming(_ a: UInt64, _ b: UInt64) -> Int {
        (a ^ b).nonzeroBitCount
    }

    /// 9×8 亮度，按行比较相邻像素。左边更亮则该位为 1。bit 0 是第一行最左一对，从低位往高位排。
    static func dHash(luma: [Int]) -> UInt64 {
        precondition(luma.count == dHashLength, "dHash 需要 \(dHashLength) 个亮度值，实际 \(luma.count)")
        var hash: UInt64 = 0
        var bit = 0
        for y in 0..<dHashHeight {
            let row = y * dHashWidth
            for x in 0..<(dHashWidth - 1) {
                if luma[row + x] > luma[row + x + 1] {
                    hash |= UInt64(1) << UInt64(bit)
                }
                bit += 1
            }
        }
        return hash
    }

    /// 4 邻域拉普拉斯方差。纯色为 0。边缘像素不参与。
    static func laplacianVariance(luma: [Int], width: Int, height: Int) -> Double {
        precondition(luma.count == width * height, "亮度个数 \(luma.count) 与 \(width)×\(height) 不符")
        precondition(width >= 3 && height >= 3, "拉普拉斯至少需要 3×3，实际 \(width)×\(height)")
        var sum = 0.0
        var sumSq = 0.0
        var n = 0.0
        for y in 1..<(height - 1) {
            let row = y * width
            for x in 1..<(width - 1) {
                let v = Double(
                    luma[row - width + x] + luma[row + width + x] + luma[row + x - 1] + luma[row + x + 1]
                        - 4 * luma[row + x]
                )
                sum += v
                sumSq += v * v
                n += 1
            }
        }
        let mean = sum / n
        return sumSq / n - mean * mean
    }

    /// 10 秒内有邻居的图片。孤立的一张、视频、没有拍摄时间的都不算哈希。
    static func hashCandidateIds(_ photos: [SimilarPhotoRef]) -> [String] {
        let sorted = eligible(photos)
        guard sorted.count >= 2 else { return [] }
        var out: [String] = []
        for i in sorted.indices {
            let t = sorted[i].takenAt!
            let prevClose = i > 0 && t.timeIntervalSince(sorted[i - 1].takenAt!) <= maxGap
            let nextClose = i < sorted.count - 1 && sorted[i + 1].takenAt!.timeIntervalSince(t) <= maxGap
            if prevClose || nextClose { out.append(sorted[i].id) }
        }
        return out
    }

    /// 成组。`hashes` 里没有的照片跳过。组按最早一张的时间倒序。
    static func group(
        _ photos: [SimilarPhotoRef],
        hashes: [String: UInt64],
        sharpness: [String: Double] = [:]
    ) -> [SimilarGrouping] {
        let hashed = eligible(photos).filter { hashes[$0.id] != nil }
        guard hashed.count >= 2 else { return [] }
        var runs: [[SimilarPhotoRef]] = []
        var run: [SimilarPhotoRef] = []
        for i in hashed.indices {
            let photo = hashed[i]
            let joins: Bool
            if i == 0 {
                joins = false
            } else {
                let prev = hashed[i - 1]
                let gap = photo.takenAt!.timeIntervalSince(prev.takenAt!)
                let distance = hamming(hashes[photo.id]!, hashes[prev.id]!)
                joins = gap <= maxGap && distance <= maxHamming
            }
            if joins {
                run.append(photo)
            } else {
                if run.count >= 2 { runs.append(run) }
                run = [photo]
            }
        }
        if run.count >= 2 { runs.append(run) }
        return runs.map { members in
            let ids = members.map(\.id)
            let scores = sharpness.filter { ids.contains($0.key) }
            let recommended = chooseRecommended(members, sharpness: scores)
            return SimilarGrouping(
                ids: ids,
                recommendedId: recommended,
                sharpness: scores,
                earliest: members[0].takenAt!
            )
        }
        .sorted { a, b in
            if a.earliest != b.earliest { return a.earliest > b.earliest }
            return a.ids[0] < b.ids[0]
        }
    }

    /// 清晰度高的优先；没有清晰度的排最后。其后：像素数、文件大小、更早的拍摄时间、更小的 id。
    static func chooseRecommended(_ photos: [SimilarPhotoRef], sharpness: [String: Double] = [:]) -> String {
        precondition(!photos.isEmpty)
        return photos.reduce(photos[0]) { best, photo in
            beats(photo, best, sharpness: sharpness) ? photo : best
        }.id
    }

    /// 还会留在相册里的 id。少于两张不记。升序后用逗号连接。
    static func handledKey(remainingIds: Set<String>) -> String? {
        guard remainingIds.count >= 2 else { return nil }
        return remainingIds.sorted().joined(separator: ",")
    }

    /// 一组的 id 是某条已处理记录的子集（含相等）时，这组不再出现。
    static func isSuppressed(groupIds: [String], handledKeys: some Sequence<String>) -> Bool {
        let group = Set(groupIds)
        guard group.count >= 2 else { return false }
        for key in handledKeys {
            let record = Set(key.split(separator: ",").map(String.init))
            if group.isSubset(of: record) { return true }
        }
        return false
    }

    private static func eligible(_ photos: [SimilarPhotoRef]) -> [SimilarPhotoRef] {
        photos
            .filter { $0.isImage && ($0.takenAt.map { $0.timeIntervalSince1970 > 0 } ?? false) }
            .sorted { a, b in
                if a.takenAt! != b.takenAt! { return a.takenAt! < b.takenAt! }
                return a.id < b.id
            }
    }

    private static func beats(_ photo: SimilarPhotoRef, _ best: SimilarPhotoRef, sharpness: [String: Double]) -> Bool {
        let ps = sharpness[photo.id]
        let bs = sharpness[best.id]
        switch (ps, bs) {
        case (nil, nil):
            break
        case (nil, _?):
            return false
        case (_?, nil):
            return true
        case let (ps?, bs?) where ps != bs:
            return ps > bs
        default:
            break
        }
        if photo.pixelCount != best.pixelCount { return photo.pixelCount > best.pixelCount }
        if photo.byteSize != best.byteSize { return photo.byteSize > best.byteSize }
        let pt = photo.takenAt?.timeIntervalSince1970 ?? .greatestFiniteMagnitude
        let bt = best.takenAt?.timeIntervalSince1970 ?? .greatestFiniteMagnitude
        if pt != bt { return pt < bt }
        return photo.id < best.id
    }
}

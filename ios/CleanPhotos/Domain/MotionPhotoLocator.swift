import Foundation

/// 内嵌视频在原文件里的字节范围。识别不到就是 nil。
struct MotionPhotoVideo: Equatable {
    var offset: Int64
    var length: Int64
}

enum MotionPhotoReadError: Error {
    case outOfBounds
    case unavailable
    case malformed(String)
}

/// 随机读。解析只靠这个接口，不碰 PhotoKit。
protocol RandomBytes {
    var size: Int64 { get }
    func read(position: Int64, length: Int) throws -> Data
}

struct ArrayBytes: RandomBytes {
    let data: Data
    var size: Int64 { Int64(data.count) }

    func read(position: Int64, length: Int) throws -> Data {
        let start = Int(position)
        if position != Int64(start) || start < 0 || length < 0 || start + length > data.count {
            throw MotionPhotoReadError.outOfBounds
        }
        return data.subdata(in: start..<(start + length))
    }
}

/// 本地文件的随机读。调用方负责文件还在。
final class FileBytes: RandomBytes {
    let size: Int64
    private let handle: FileHandle

    init(url: URL) throws {
        handle = try FileHandle(forReadingFrom: url)
        size = Int64(try handle.seekToEnd())
    }

    deinit { try? handle.close() }

    func read(position: Int64, length: Int) throws -> Data {
        if position < 0 || length < 0 || position > size || Int64(length) > size - position {
            throw MotionPhotoReadError.outOfBounds
        }
        try handle.seek(toOffset: UInt64(position))
        let data = try handle.read(upToCount: length) ?? Data()
        if data.count != length { throw MotionPhotoReadError.outOfBounds }
        return data
    }
}

/// 厂商动态照片识别。规则与安卓 `MotionPhotoLocator` 一致，只依赖 Foundation。
/// 识别不到返回 nil。读接口抛出的错误不在这里吞。
enum MotionPhotoLocator {
    private static let headLimit = 512 * 1024
    private static let scanWindow = 64 * 1024
    private static let liveSlack: Int64 = 64
    private static let ftyp = Data("ftyp".utf8)
    private static let moov = Data("moov".utf8)
    private static let mpvd = Data("mpvd".utf8)
    private static let seft = Data("SEFT".utf8)
    private static let sefh = Data("SEFH".utf8)
    private static let xmpHeader = Data("http://ns.adobe.com/xap/1.0/\u{0}".utf8)
    private static let exifHeader = Data("Exif\u{0}\u{0}".utf8)
    private static let heifBrands: Set<String> = ["heic", "heix", "hevc", "hevx", "mif1", "msf1", "heim", "heis", "avif", "avis"]

    private static let semantic = regex(
        #"Item:Semantic\s*=\s*"([^"]*)"|<(?:[\w.]+:)?Semantic>([^<]*)</(?:[\w.]+:)?Semantic>"#
    )
    private static let lengthPattern = regex(
        #"Item:Length\s*=\s*"(\d+)"|<(?:[\w.]+:)?Length>(\d+)</(?:[\w.]+:)?Length>"#
    )
    private static let motionPhotoValue = named("MotionPhoto")
    private static let microVideoValue = named("MicroVideo")
    private static let microOffsetValue = named("MicroVideoOffset")
    private static let oliveVersionValue = named("OLivePhotoVersion")
    private static let videoLengthValue = named("VideoLength")
    private static let liveFooter = regex(#"^LIVE_(\d+)$"#)
    private static let directoryBlock = regex(
        #"<(?:[\w.]+:)?Directory\b[^>]*>(.*?)</(?:[\w.]+:)?Directory>"#,
        dotAll: true
    )
    /// 本地名恰好是 Item 或 li。`Item:Semantic` 这种带后缀的名字不是 Item 元素。
    private static let itemElement = regex(
        #"<(?:[\w.]+:)?(?:Item|li)(?![\w:-])([^>]*?)(?:/>|>(.*?)</(?:[\w.]+:)?(?:Item|li)(?![\w:-])>)"#,
        dotAll: true
    )

    static func locate(_ source: RandomBytes) throws -> MotionPhotoVideo? {
        if source.size < 12 { return nil }
        let headLen = Int(min(source.size, Int64(headLimit)))
        let head = try source.read(position: 0, length: headLen)
        if head.count >= 2 && head[0] == 0xFF && head[1] == 0xD8 {
            return try locateJpeg(source, head)
        }
        return try locateHeif(source, head)
    }

    /// 从 offset 走顶层 box。见到完整的 moov 后，非法尾巴截掉；moov 之前就越界则失败。
    static func validate(_ source: RandomBytes, offset: Int64) throws -> MotionPhotoVideo? {
        if offset < 0 || offset + 8 > source.size { return nil }
        let first = try source.read(position: offset, length: 8)
        if !first.subdata(in: 4..<8).elementsEqual(ftyp) { return nil }
        var pos = offset
        var end = offset
        var sawMoov = false
        while pos + 8 <= source.size {
            let hdr = pos == offset ? first : try source.read(position: pos, length: 8)
            let type = hdr.subdata(in: 4..<8)
            if !printable(type) { break }
            guard let box = try readBox(source, pos, hdr) else { break }
            if type.elementsEqual(moov) { sawMoov = true }
            pos += box.size
            end = pos
            if box.extendsToEof { break }
        }
        if !sawMoov || end <= offset { return nil }
        return MotionPhotoVideo(offset: offset, length: end - offset)
    }

    private static func locateJpeg(_ source: RandomBytes, _ head: Data) throws -> MotionPhotoVideo? {
        let parsed = try parseJpegHead(head)
        var oppoMarked = false
        for xmp in parsed.xmps {
            // 同一份 XMP 可能同时在 Exif 0x02BC 和 APP1。每一份目录单独倒推，不把两份 Length 加在一起。
            for block in directoryBlocks(xmp) {
                if let offset = try googleOffset(xmp, block, source.size),
                   let video = try validate(source, offset: offset) {
                    return video
                }
            }
            if let offset = microOffset(xmp, source.size),
               let video = try validate(source, offset: offset) {
                return video
            }
            if isOppo(xmp) {
                oppoMarked = true
                if let offset = oppoOffset(xmp, source.size),
                   let video = try validate(source, offset: offset) {
                    return video
                }
            }
        }
        if let offset = try samsungOffset(source),
           let video = try validate(source, offset: offset) {
            return video
        }
        if let offset = try honorOffset(source) {
            if let video = try validate(source, offset: offset) { return video }
            if let video = try scanFtyp(source, max(0, offset - liveSlack)) { return video }
        }
        let make = parsed.make?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let scan = oppoMarked || make == "HUAWEI" || make == "HONOR"
        if !scan { return nil }
        guard let sos = parsed.sosContent, let eoi = try findEoi(source, sos) else { return nil }
        return try scanFtyp(source, eoi)
    }

    private static func locateHeif(_ source: RandomBytes, _ head: Data) throws -> MotionPhotoVideo? {
        if head.count < 12 || !head.subdata(in: 4..<8).elementsEqual(ftyp) { return nil }
        let brand = latin1(head.subdata(in: 8..<12))
        if !heifBrands.contains(brand) { return nil }
        var pos: Int64 = 0
        while pos + 8 <= source.size {
            let hdr = try source.read(position: pos, length: 8)
            guard let box = try readBox(source, pos, hdr) else { return nil }
            if hdr.subdata(in: 4..<8).elementsEqual(mpvd) {
                return try validate(source, offset: pos + Int64(box.headerSize))
            }
            if box.extendsToEof { return nil }
            pos += box.size
        }
        return nil
    }

    private struct BoxHeader {
        var size: Int64
        var headerSize: Int
        var extendsToEof: Bool
    }

    private static func readBox(_ source: RandomBytes, _ pos: Int64, _ hdr: Data) throws -> BoxHeader? {
        let size32 = beU32(hdr, 0)
        switch size32 {
        case 0:
            return BoxHeader(size: source.size - pos, headerSize: 8, extendsToEof: true)
        case 1:
            if pos + 16 > source.size { return nil }
            let large = try beU64(source.read(position: pos + 8, length: 8), 0)
            if large < 16 || pos + large > source.size { return nil }
            return BoxHeader(size: large, headerSize: 16, extendsToEof: false)
        default:
            if size32 < 8 || pos + size32 > source.size { return nil }
            return BoxHeader(size: size32, headerSize: 8, extendsToEof: false)
        }
    }

    private struct JpegHead {
        var xmps: [String]
        var make: String?
        var sosContent: Int64?
    }

    private static func parseJpegHead(_ head: Data) throws -> JpegHead {
        var xmps: [String] = []
        var make: String?
        var pos = 2
        var sos: Int64?
        while pos + 3 < head.count {
            if head[pos] != 0xFF { break }
            while pos < head.count && head[pos] == 0xFF { pos += 1 }
            if pos >= head.count { break }
            let marker = Int(head[pos])
            pos += 1
            if marker == 0xDA {
                if pos + 2 > head.count { break }
                let len = be16(head, pos)
                if len < 2 { break }
                sos = Int64(pos + len)
                break
            }
            if marker == 0xD9 { break }
            if marker == 0x01 || (0xD0...0xD8).contains(marker) { continue }
            if pos + 2 > head.count { break }
            let len = be16(head, pos)
            if len < 2 || pos + len > head.count { break }
            if marker == 0xE1 {
                let payload = head.subdata(in: (pos + 2)..<(pos + len))
                if let xmp = xmpFromApp1(payload) { xmps.append(xmp) }
                if let exif = exifFields(payload) {
                    if make == nil { make = exif.0 }
                    if let xmp = exif.1 { xmps.append(xmp) }
                }
            }
            pos += len
        }
        return JpegHead(xmps: xmps, make: make, sosContent: sos)
    }

    private static func xmpFromApp1(_ payload: Data) -> String? {
        if payload.count <= xmpHeader.count || !payload.prefix(xmpHeader.count).elementsEqual(xmpHeader) {
            return nil
        }
        return latin1(payload.subdata(in: xmpHeader.count..<payload.count))
    }

    /// Exif APP1：Make（0x010F）和 XMP（0x02BC，vivo 把动态照片 XMP 写在这里）。
    private static func exifFields(_ payload: Data) -> (String?, String?)? {
        if payload.count <= exifHeader.count + 8 || !payload.prefix(exifHeader.count).elementsEqual(exifHeader) {
            return nil
        }
        let tiff = payload.subdata(in: exifHeader.count..<payload.count)
        if tiff.count < 8 { return nil }
        let le = tiff[0] == UInt8(ascii: "I") && tiff[1] == UInt8(ascii: "I")
        let be = tiff[0] == UInt8(ascii: "M") && tiff[1] == UInt8(ascii: "M")
        if !le && !be { return nil }
        func u16(_ o: Int) -> Int { le ? le16(tiff, o) : be16(tiff, o) }
        func u32(_ o: Int) -> Int64 { le ? leU32(tiff, o) : beU32(tiff, o) }
        let ifd = Int(u32(4))
        if ifd < 8 || ifd + 2 > tiff.count { return nil }
        let count = u16(ifd)
        var make: String?
        var xmp: String?
        if count < 0 { return nil }
        for i in 0..<count {
            let entry = ifd + 2 + i * 12
            if entry + 12 > tiff.count { break }
            let tag = u16(entry)
            let type = u16(entry + 2)
            let n = u32(entry + 4)
            guard let width = typeWidth(type) else { continue }
            if n > Int64(Int.max) / Int64(width) { continue }
            let bytes = Int(n) * width
            let at = bytes <= 4 ? entry + 8 : Int(u32(entry + 8))
            if at < 0 || bytes < 0 || at + bytes > tiff.count { continue }
            if tag == 0x010F && type == 2 {
                let raw = latin1(tiff.subdata(in: at..<(at + bytes)))
                make = raw.split(separator: "\u{0}", maxSplits: 1, omittingEmptySubsequences: false).first.map {
                    String($0).trimmingCharacters(in: .whitespacesAndNewlines)
                }
            } else if tag == 0x02BC {
                xmp = latin1(tiff.subdata(in: at..<(at + bytes)))
            }
        }
        return (make, xmp)
    }

    private static func typeWidth(_ type: Int) -> Int? {
        switch type {
        case 1, 2, 7: return 1
        case 3: return 2
        case 4, 9: return 4
        case 5, 10: return 8
        default: return nil
        }
    }

    private struct DirItem {
        var semantic: String
        var length: Int64?
    }

    private struct Hit {
        var at: Int
        var groups: [String]
    }

    private static func directoryItems(_ xml: String) throws -> [DirItem] {
        // 无关的 rdf:li（例如 dc:subject）没有 Semantic，不算目录项。
        // 真实项缺 Length 时保留 nil，不改走就近配对，也不能借用相邻项的 Length。
        let real = try matches(itemElement, in: xml).compactMap { hit -> DirItem? in
            let chunk = group(hit, 1) + group(hit, 2)
            guard let semantic = try semanticOf(chunk) else { return nil }
            return DirItem(semantic: semantic, length: try lengthOf(chunk))
        }
        if !real.isEmpty { return real }
        return try looseItems(xml)
    }

    /// 没有带 Semantic 的 Item / li 时（vivo 把属性直接写在 Description 上），每个 Length 只就近配一次。
    private static func looseItems(_ xml: String) throws -> [DirItem] {
        let semantics = try matches(semantic, in: xml).compactMap { hit -> (Int, String)? in
            let value = group(hit, 1).isEmpty ? group(hit, 2) : group(hit, 1)
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty { return nil }
            return (hit.at, trimmed)
        }
        if semantics.isEmpty { return [] }
        let lengths = try matches(lengthPattern, in: xml).map { hit -> (Int, Int64) in
            let raw = group(hit, 1).isEmpty ? group(hit, 2) : group(hit, 1)
            guard let value = Int64(raw) else { throw MotionPhotoReadError.malformed("Length") }
            return (hit.at, value)
        }
        var used = Array(repeating: false, count: lengths.count)
        return semantics.map { at, semantic in
            var best = -1
            var bestDistance = Int.max
            for (index, length) in lengths.enumerated() where !used[index] {
                let distance = abs(length.0 - at)
                if distance < bestDistance {
                    bestDistance = distance
                    best = index
                }
            }
            let length: Int64?
            if best >= 0 {
                used[best] = true
                length = lengths[best].1
            } else {
                length = nil
            }
            return DirItem(semantic: semantic, length: length)
        }
    }

    private static func semanticOf(_ xml: String) throws -> String? {
        guard let hit = try matches(semantic, in: xml).first else { return nil }
        let value = group(hit, 1).isEmpty ? group(hit, 2) : group(hit, 1)
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func lengthOf(_ xml: String) throws -> Int64? {
        guard let hit = try matches(lengthPattern, in: xml).first else { return nil }
        let raw = group(hit, 1).isEmpty ? group(hit, 2) : group(hit, 1)
        guard let value = Int64(raw) else { throw MotionPhotoReadError.malformed("Length") }
        return value
    }

    /// 没有 Directory 元素时，整段 XMP 就是一份目录（属性形式）。
    private static func directoryBlocks(_ xml: String) -> [String] {
        let blocks = (try? matches(directoryBlock, in: xml))?.compactMap { hit -> String? in
            let inner = group(hit, 1)
            return inner.isEmpty ? nil : inner
        } ?? []
        return blocks.isEmpty ? [xml] : blocks
    }

    private static func googleOffset(_ xml: String, _ itemsXml: String, _ size: Int64) throws -> Int64? {
        if namedValue(xml, motionPhotoValue) != "1" { return nil }
        let items = try directoryItems(itemsXml)
        guard let start = items.firstIndex(where: { $0.semantic == "MotionPhoto" }) else { return nil }
        var sum: Int64 = 0
        for item in items[start...] {
            guard let length = item.length, length >= 0 else { return nil }
            if sum > Int64.max - length { return nil }
            sum += length
        }
        if sum <= 0 || sum > size { return nil }
        return size - sum
    }

    private static func microOffset(_ xml: String, _ size: Int64) -> Int64? {
        if namedValue(xml, microVideoValue) != "1" { return nil }
        guard let length = namedValue(xml, microOffsetValue).flatMap(Int64.init) else { return nil }
        if length <= 0 || length > size { return nil }
        return size - length
    }

    private static func isOppo(_ xml: String) -> Bool {
        if xml.contains("ns.oplus.com/photo/1.0/camera/") || xml.contains("ns.oppo.com/photo/1.0/camera/") {
            return true
        }
        return namedValue(xml, oliveVersionValue) != nil
    }

    private static func oppoOffset(_ xml: String, _ size: Int64) -> Int64? {
        guard let length = namedValue(xml, videoLengthValue).flatMap(Int64.init) else { return nil }
        if length <= 0 || length > size { return nil }
        return size - length
    }

    private static func samsungOffset(_ source: RandomBytes) throws -> Int64? {
        if source.size < 8 { return nil }
        let tail = try source.read(position: source.size - 8, length: 8)
        if !tail.subdata(in: 4..<8).elementsEqual(seft) { return nil }
        let back = leU32(tail, 0)
        let sefhAt = source.size - 8 - back
        if sefhAt < 0 || sefhAt + 12 > source.size { return nil }
        let header = try source.read(position: sefhAt, length: 12)
        if !header.prefix(4).elementsEqual(sefh) { return nil }
        let count = leU32(header, 8)
        if count <= 0 || count > 64 { return nil }
        let indexBytes = count * 12
        if sefhAt + 12 + indexBytes > source.size - 8 { return nil }
        let index = try source.read(position: sefhAt + 12, length: Int(indexBytes))
        for i in 0..<Int(count) {
            let at = i * 12
            let type = le16(index, at + 2)
            if type != 0x0A30 { continue }
            let backOff = leU32(index, at + 4)
            let recordLen = leU32(index, at + 8)
            let dataPos = sefhAt - backOff
            if dataPos < 0 || recordLen < 12 || dataPos + recordLen > source.size { continue }
            // 记录前 64 字节里找 ftyp，起点 = 命中处 − 4。有 mpv2 头时 ftyp 在头后面，同样命中。
            let prefixLen = Int(min(recordLen, 64))
            let prefix = try source.read(position: dataPos, length: prefixLen)
            let ftypAt = indexOf(prefix, ftyp)
            if ftypAt < 4 { continue }
            let videoAt = dataPos + Int64(ftypAt - 4)
            if videoAt + 8 <= source.size { return videoAt }
        }
        return nil
    }

    private static func honorOffset(_ source: RandomBytes) throws -> Int64? {
        if source.size < 20 { return nil }
        let text = trimEnd(latin1(try source.read(position: source.size - 20, length: 20)), " ", "\u{0}")
        guard let hit = try matches(liveFooter, in: text).first else { return nil }
        guard let length = Int64(group(hit, 1)), length > 0 else { return nil }
        let offset = source.size - 20 - length
        if offset < 0 { return nil }
        return offset
    }

    private static func findEoi(_ source: RandomBytes, _ start: Int64) throws -> Int64? {
        var pos = start
        while pos < source.size {
            let len = Int(min(Int64(scanWindow), source.size - pos))
            if len <= 0 { return nil }
            let buf = try source.read(position: pos, length: len)
            var i = 0
            var advanced = true
            while i < buf.count {
                if buf[i] != 0xFF {
                    i += 1
                    continue
                }
                if i + 1 >= buf.count {
                    if pos + Int64(i) + 1 >= source.size { return nil }
                    pos += Int64(i)
                    advanced = false
                    break
                }
                let next = Int(buf[i + 1])
                if next == 0xD9 { return pos + Int64(i) + 2 }
                i += next == 0xFF ? 1 : 2
            }
            if advanced { pos += Int64(len) }
        }
        return nil
    }

    private static func scanFtyp(_ source: RandomBytes, _ start: Int64) throws -> MotionPhotoVideo? {
        var pos = start
        while pos + 8 <= source.size {
            let len = Int(min(Int64(scanWindow), source.size - pos))
            if len < 8 { return nil }
            let buf = try source.read(position: pos, length: len)
            var from = 0
            while from <= buf.count - 4 {
                let at = indexOf(buf, ftyp, start: from)
                if at < 0 { break }
                if at >= 4, let video = try validate(source, offset: pos + Int64(at - 4)) {
                    return video
                }
                from = at + 1
            }
            if pos + Int64(len) >= source.size { break }
            pos += Int64(len - 8)
        }
        return nil
    }

    private static func namedValue(_ xml: String, _ pattern: NSRegularExpression) -> String? {
        guard let hit = try? matches(pattern, in: xml).first else { return nil }
        let raw = group(hit, 1).isEmpty ? group(hit, 2) : group(hit, 1)
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func group(_ hit: Hit, _ index: Int) -> String {
        index < hit.groups.count ? hit.groups[index] : ""
    }

    private static func matches(_ pattern: NSRegularExpression, in text: String) throws -> [Hit] {
        let range = NSRange(text.startIndex..., in: text)
        return pattern.matches(in: text, range: range).map { match in
            var groups: [String] = []
            groups.reserveCapacity(match.numberOfRanges)
            for index in 0..<match.numberOfRanges {
                let r = match.range(at: index)
                if r.location == NSNotFound {
                    groups.append("")
                } else if let swift = Range(r, in: text) {
                    groups.append(String(text[swift]))
                } else {
                    groups.append("")
                }
            }
            return Hit(at: match.range.location, groups: groups)
        }
    }

    private static func printable(_ type: Data) -> Bool {
        type.count == 4 && type.allSatisfy { $0 >= 0x20 && $0 <= 0x7E }
    }

    private static func indexOf(_ data: Data, _ needle: Data, start: Int = 0) -> Int {
        if needle.isEmpty || start > data.count - needle.count || start < 0 { return -1 }
        var i = start
        let last = data.count - needle.count
        while i <= last {
            if data[i..<(i + needle.count)].elementsEqual(needle) { return i }
            i += 1
        }
        return -1
    }

    private static func latin1(_ data: Data) -> String {
        String(data: data, encoding: .isoLatin1) ?? ""
    }

    private static func trimEnd(_ text: String, _ chars: Character...) -> String {
        let drop = Set(chars)
        var end = text.endIndex
        while end > text.startIndex {
            let prev = text.index(before: end)
            if drop.contains(text[prev]) { end = prev } else { break }
        }
        return String(text[..<end])
    }

    private static func regex(_ pattern: String, dotAll: Bool = false) -> NSRegularExpression {
        var options: NSRegularExpression.Options = []
        if dotAll { options.insert(.dotMatchesLineSeparators) }
        return try! NSRegularExpression(pattern: pattern, options: options)
    }

    private static func named(_ local: String) -> NSRegularExpression {
        let pattern = "(?:^|[\\s<])(?:[\\w.]+:)?\(local)\\s*=\\s*\"([^\"]*)\"|<(?:[\\w.]+:)?\(local)>([^<]*)</(?:[\\w.]+:)?\(local)>"
        return regex(pattern)
    }

    private static func be16(_ b: Data, _ o: Int) -> Int {
        (Int(b[o]) << 8) | Int(b[o + 1])
    }

    private static func beU32(_ b: Data, _ o: Int) -> Int64 {
        (Int64(b[o]) << 24) | (Int64(b[o + 1]) << 16) | (Int64(b[o + 2]) << 8) | Int64(b[o + 3])
    }

    private static func beU64(_ b: Data, _ o: Int) -> Int64 {
        var value: UInt64 = 0
        for i in 0..<8 { value = (value << 8) | UInt64(b[o + i]) }
        return Int64(bitPattern: value)
    }

    private static func le16(_ b: Data, _ o: Int) -> Int {
        Int(b[o]) | (Int(b[o + 1]) << 8)
    }

    private static func leU32(_ b: Data, _ o: Int) -> Int64 {
        Int64(b[o]) | (Int64(b[o + 1]) << 8) | (Int64(b[o + 2]) << 16) | (Int64(b[o + 3]) << 24)
    }
}

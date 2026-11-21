import XCTest

private func + (lhs: Data, rhs: Data) -> Data {
    var copy = lhs
    copy.append(rhs)
    return copy
}

/// 动态照片识别。每个分支用合成字节，不读真实照片。
final class MotionPhotoLocatorTests: XCTestCase {
    func testGoogleAttributesIgnorePaddingAndTrimIllegalTail() throws {
        let video = mp4()
        let trailer = oversizeBox("uuid")
        let xml = """
        <rdf:Description Camera:MotionPhoto="1">
          <Container:Directory>
            <rdf:li Item:Semantic="Primary" Item:Length="0" Item:Padding="8"/>
            <rdf:li Item:Semantic="MotionPhoto" Item:Length="\(video.count)" Item:Padding="50"/>
            <rdf:li Item:Semantic="GainMap" Item:Length="\(trailer.count)"/>
          </Container:Directory>
        </rdf:Description>
        """
        let file = jpeg(xmpApp1(xml), video + trailer)
        let found = try MotionPhotoLocator.locate(ArrayBytes(data: file))
        try assertVideo(file, found, Int64(file.count - video.count - trailer.count), Int64(video.count))
    }

    func testDuplicateGoogleXmpIsNotAddedTwice() throws {
        let video = mp4()
        let xml = """
        <rdf:Description Camera:MotionPhoto="1">
          <Container:Directory>
            <rdf:li Item:Semantic="Primary" Item:Length="0"/>
            <rdf:li Item:Semantic="MotionPhoto" Item:Length="\(video.count)"/>
          </Container:Directory>
        </rdf:Description>
        """
        let packet = xmpApp1(xml)
        let file = jpeg(packet + packet, video)
        let found = try MotionPhotoLocator.locate(ArrayBytes(data: file))
        try assertVideo(file, found, Int64(file.count - video.count), Int64(video.count))
    }

    func testGoogleElements() throws {
        let video = mp4()
        let xml = """
        <Container:Item>
          <Camera:MotionPhoto>1</Camera:MotionPhoto>
          <Item:Semantic>MotionPhoto</Item:Semantic>
          <Item:Length>\(video.count)</Item:Length>
          <Item:Padding>8</Item:Padding>
        </Container:Item>
        """
        let file = jpeg(xmpApp1(xml), video)
        let found = try MotionPhotoLocator.locate(ArrayBytes(data: file))
        try assertVideo(file, found, Int64(file.count - video.count), Int64(video.count))
    }

    func testMicroVideoOffsetFromEnd() throws {
        let video = mp4()
        let xml = #"GCamera:MicroVideo="1" GCamera:MicroVideoOffset="\#(video.count)""#
        let file = jpeg(xmpApp1(xml), video)
        let found = try MotionPhotoLocator.locate(ArrayBytes(data: file))
        try assertVideo(file, found, Int64(file.count - video.count), Int64(video.count))
    }

    func testVivoExifXmpTrimsUuidTail() throws {
        let video = mp4()
        let trailer = oversizeBox("uuid")
        let xml = """
        Camera:MotionPhoto="1"
        Item:Semantic="MotionPhoto" Item:Length="\(video.count + trailer.count)"
        """
        let file = jpeg(exifApp1(make: nil, xmp: xml), video + trailer)
        let found = try MotionPhotoLocator.locate(ArrayBytes(data: file))
        try assertVideo(file, found, Int64(file.count - video.count - trailer.count), Int64(video.count))
    }

    func testOppoVideoLengthTrimsJxrsTail() throws {
        let video = mp4()
        let trailer = oversizeBox("jxrs")
        let xml = """
        xmlns:OpCamera="http://ns.oplus.com/photo/1.0/camera/"
        OpCamera:OLivePhotoVersion="1" OpCamera:VideoLength="\(video.count + trailer.count)"
        """
        let file = jpeg(xmpApp1(xml), video + trailer)
        let found = try MotionPhotoLocator.locate(ArrayBytes(data: file))
        try assertVideo(file, found, Int64(file.count - video.count - trailer.count), Int64(video.count))
    }

    func testOppoFallsBackToEoiScanWhenLengthMisses() throws {
        let video = mp4()
        let xml = """
        xmlns:OpCamera="http://ns.oppo.com/photo/1.0/camera/"
        OpCamera:OLivePhotoVersion="1" OpCamera:VideoLength="4"
        """
        let file = jpeg(xmpApp1(xml), video)
        let found = try MotionPhotoLocator.locate(ArrayBytes(data: file))
        try assertVideo(file, found, Int64(file.count - video.count), Int64(video.count))
    }

    func testSamsungSeftSkipsMpv2Header() throws {
        let video = mp4()
        let record = cat(
            Data([0, 0, 0x30, 0x0A]),
            le32(16),
            latin1("MotionPhoto_Data"),
            latin1("mpv2"),
            Data(count: 8),
            video
        )
        let tail = samsungTail(record)
        let file = jpeg(Data(), tail)
        let found = try MotionPhotoLocator.locate(ArrayBytes(data: file))
        try assertVideo(file, found, Int64(file.count - tail.count + record.count - video.count), Int64(video.count))
    }

    func testSamsungSeftWithoutMpv2Header() throws {
        let video = mp4()
        let key = "MotionPhoto_Data"
        let record = cat(
            Data([0, 0]),
            Data([0x30, 0x0A]),
            le32(Int64(key.count)),
            latin1(key),
            video
        )
        let tail = samsungTail(record)
        let file = jpeg(Data(), tail)
        let found = try MotionPhotoLocator.locate(ArrayBytes(data: file))
        try assertVideo(file, found, Int64(file.count - tail.count + record.count - video.count), Int64(video.count))
    }

    func testHonorLiveFooter() throws {
        let video = mp4()
        let footer = latin1(padEnd("LIVE_\(video.count)", to: 20))
        let file = jpeg(Data(), video + footer)
        let found = try MotionPhotoLocator.locate(ArrayBytes(data: file))
        try assertVideo(file, found, Int64(file.count) - 20 - Int64(video.count), Int64(video.count))
    }

    func testLiveFooterLateBy20BytesScansBackwardWithoutMake() throws {
        let video = mp4()
        let footer = latin1(padEnd("LIVE_\(video.count)", to: 20))
        let file = jpeg(Data(), video + Data(count: 20) + footer)
        let found = try MotionPhotoLocator.locate(ArrayBytes(data: file))
        try assertVideo(file, found, Int64(file.count) - 40 - Int64(video.count), Int64(video.count))
    }

    func testHeicMpvdPayloadIsTheVideo() throws {
        let video = mp4()
        let file = heic(video)
        let found = try MotionPhotoLocator.locate(ArrayBytes(data: file))
        let offset = try XCTUnwrap(found).offset
        XCTAssertEqual(latin1String(file.subdata(in: Int(offset + 4)..<Int(offset + 8))), "ftyp")
        XCTAssertEqual(found?.length, Int64(video.count))
    }

    func testHuaweiMakeScansAfterEoi() throws {
        let video = mp4()
        let file = jpeg(exifApp1(make: "Huawei", xmp: nil), video)
        let found = try MotionPhotoLocator.locate(ArrayBytes(data: file))
        try assertVideo(file, found, Int64(file.count - video.count), Int64(video.count))
    }

    func testOtherMakeDoesNotScan() throws {
        let video = mp4()
        let file = jpeg(exifApp1(make: "Canon", xmp: nil), video)
        XCTAssertNil(try MotionPhotoLocator.locate(ArrayBytes(data: file)))
    }

    func testPlainJpegIsNil() throws {
        XCTAssertNil(try MotionPhotoLocator.locate(ArrayBytes(data: jpeg(Data(), Data()))))
    }

    func testOffsetNotOnFtypIsNil() throws {
        let file = jpeg(Data(), mp4())
        let xml = #"GCamera:MicroVideo="1" GCamera:MicroVideoOffset="\#(file.count)""#
        let miss = jpeg(xmpApp1(xml), Data())
        XCTAssertNil(try MotionPhotoLocator.locate(ArrayBytes(data: miss)))
    }

    func testMoovExtendingPastEofIsNil() throws {
        let ftyp = box("ftyp", latin1("isom"))
        let moov = be32(1000) + latin1("moov")
        XCTAssertNil(try MotionPhotoLocator.validate(ArrayBytes(data: ftyp + moov), offset: 0))
    }

    func testGoogleAttributeOrderAndPaddingBetweenFields() throws {
        let video = mp4()
        let trailer = oversizeBox("uuid")
        let xml = """
        <rdf:Description Camera:MotionPhoto="1">
          <Container:Directory>
            <rdf:li Item:Length="0" Item:Padding="8" Item:Semantic="Primary"/>
            <rdf:li Item:Length="\(video.count)" Item:Padding="50" Item:Semantic="MotionPhoto"/>
            <rdf:li Item:Length="\(trailer.count)" Item:Semantic="GainMap"/>
          </Container:Directory>
        </rdf:Description>
        """
        let file = jpeg(xmpApp1(xml), video + trailer)
        let found = try MotionPhotoLocator.locate(ArrayBytes(data: file))
        try assertVideo(file, found, Int64(file.count - video.count - trailer.count), Int64(video.count))
    }

    func testElementLengthBeforeSemantic() throws {
        let video = mp4()
        let xml = """
        <Container:Item>
          <Camera:MotionPhoto>1</Camera:MotionPhoto>
          <Item:Length>\(video.count)</Item:Length>
          <Item:Semantic>MotionPhoto</Item:Semantic>
        </Container:Item>
        """
        let file = jpeg(xmpApp1(xml), video)
        let found = try MotionPhotoLocator.locate(ArrayBytes(data: file))
        try assertVideo(file, found, Int64(file.count - video.count), Int64(video.count))
    }

    func testPrimaryWithoutLengthDoesNotTakeTheNextLength() throws {
        let video = mp4()
        let xml = """
        <rdf:Description Camera:MotionPhoto="1">
          <Container:Directory>
            <rdf:li Item:Semantic="Primary"/>
            <rdf:li Item:Length="\(video.count)" Item:Semantic="MotionPhoto"/>
          </Container:Directory>
        </rdf:Description>
        """
        let file = jpeg(xmpApp1(xml), video)
        let found = try MotionPhotoLocator.locate(ArrayBytes(data: file))
        try assertVideo(file, found, Int64(file.count - video.count), Int64(video.count))
    }

    func testEarlierDirectoryWithHugeLengthDoesNotBlockTheNext() throws {
        let video = mp4()
        let xml = """
        <rdf:Description Camera:MotionPhoto="1">
          <Container:Directory>
            <rdf:li Item:Semantic="MotionPhoto" Item:Length="999999999"/>
          </Container:Directory>
          <Container:Directory>
            <rdf:li Item:Semantic="Primary" Item:Length="0"/>
            <rdf:li Item:Semantic="MotionPhoto" Item:Length="\(video.count)"/>
          </Container:Directory>
        </rdf:Description>
        """
        let file = jpeg(xmpApp1(xml), video)
        let found = try MotionPhotoLocator.locate(ArrayBytes(data: file))
        try assertVideo(file, found, Int64(file.count - video.count), Int64(video.count))
    }

    func testVivoLengthBeforeSemantic() throws {
        let video = mp4()
        let trailer = oversizeBox("uuid")
        let xml = """
        Camera:MotionPhoto="1"
        Item:Length="\(video.count + trailer.count)" Item:Semantic="MotionPhoto"
        """
        let file = jpeg(exifApp1(make: nil, xmp: xml), video + trailer)
        let found = try MotionPhotoLocator.locate(ArrayBytes(data: file))
        try assertVideo(file, found, Int64(file.count - video.count - trailer.count), Int64(video.count))
    }

    func testVivoDescriptionIgnoresUnrelatedRdfLi() throws {
        let video = mp4()
        let trailer = oversizeBox("uuid")
        let xml = """
        <rdf:Description Camera:MotionPhoto="1" Item:Length="\(video.count + trailer.count)" Item:Semantic="MotionPhoto">
          <dc:subject>
            <rdf:Bag>
              <rdf:li>holiday</rdf:li>
            </rdf:Bag>
          </dc:subject>
        </rdf:Description>
        """
        let file = jpeg(exifApp1(make: nil, xmp: xml), video + trailer)
        let found = try MotionPhotoLocator.locate(ArrayBytes(data: file))
        try assertVideo(file, found, Int64(file.count - video.count - trailer.count), Int64(video.count))
    }

    func testVivoDescriptionReversedAttributesIgnoreUnrelatedRdfLi() throws {
        let video = mp4()
        let trailer = oversizeBox("uuid")
        let xml = """
        <rdf:Description Camera:MotionPhoto="1" Item:Semantic="MotionPhoto" Item:Length="\(video.count + trailer.count)">
          <dc:subject>
            <rdf:Bag>
              <rdf:li>holiday</rdf:li>
            </rdf:Bag>
          </dc:subject>
        </rdf:Description>
        """
        let file = jpeg(exifApp1(make: nil, xmp: xml), video + trailer)
        let found = try MotionPhotoLocator.locate(ArrayBytes(data: file))
        try assertVideo(file, found, Int64(file.count - video.count - trailer.count), Int64(video.count))
    }

    func testRealItemMissingLengthDoesNotBorrowNeighbor() throws {
        let video = mp4()
        let xml = """
        <rdf:Description Camera:MotionPhoto="1">
          <Container:Directory>
            <rdf:li Item:Semantic="MotionPhoto"/>
            <rdf:li Item:Length="\(video.count)"/>
          </Container:Directory>
        </rdf:Description>
        """
        let file = jpeg(xmpApp1(xml), video)
        XCTAssertNil(try MotionPhotoLocator.locate(ArrayBytes(data: file)))
    }

    func testFileBytesMatchesArrayBytes() throws {
        let video = mp4()
        let xml = #"GCamera:MicroVideo="1" GCamera:MicroVideoOffset="\#(video.count)""#
        let file = jpeg(xmpApp1(xml), video)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("motion-photo-fixture-\(UUID().uuidString).jpg")
        try file.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let fromFile = try MotionPhotoLocator.locate(FileBytes(url: url))
        let fromMemory = try MotionPhotoLocator.locate(ArrayBytes(data: file))
        XCTAssertEqual(fromFile, fromMemory)
        try assertVideo(file, fromFile, Int64(file.count - video.count), Int64(video.count))
    }

    private func assertVideo(_ file: Data, _ found: MotionPhotoVideo?, _ offset: Int64, _ length: Int64) throws {
        let video = try XCTUnwrap(found, "应识别到视频 offset=\(offset)")
        XCTAssertEqual(video.offset, offset)
        XCTAssertEqual(video.length, length)
        let at = Int(offset)
        XCTAssertEqual(latin1String(file.subdata(in: (at + 4)..<(at + 8))), "ftyp")
    }

    private func mp4() -> Data { box("ftyp", latin1("isom")) + box("moov", Data()) }

    private func box(_ type: String, _ payload: Data) -> Data {
        be32(Int64(8 + payload.count)) + latin1(type) + payload
    }

    private func oversizeBox(_ type: String) -> Data { be32(0x00FF) + latin1(type) }

    private func samsungTail(_ record: Data) -> Data {
        let entry = cat(le16(0), le16(0x0A30), le32(Int64(record.count)), le32(Int64(record.count)))
        let sefh = cat(latin1("SEFH"), le32(106), le32(1), entry)
        return cat(record, sefh, le32(Int64(sefh.count)), latin1("SEFT"))
    }

    private func heic(_ video: Data) -> Data {
        let ftyp = box("ftyp", latin1("heic") + Data(count: 4))
        let meta = box("meta", Data(count: 4))
        let mpvd = box("mpvd", video)
        return cat(ftyp, meta, mpvd)
    }

    private func jpeg(_ segment: Data, _ after: Data) -> Data {
        let sos = cat(Data([0xFF, 0xDA]), be16(8), Data(count: 6))
        return cat(Data([0xFF, 0xD8]), segment, sos, Data([0x00]), Data([0xFF, 0xD9]), after)
    }

    private func xmpApp1(_ xml: String) -> Data {
        app1(latin1("http://ns.adobe.com/xap/1.0/\u{0}") + latin1(xml))
    }

    private func exifApp1(make: String?, xmp: String?) -> Data { app1(exifPayload(make: make, xmp: xmp)) }

    private func app1(_ payload: Data) -> Data {
        cat(Data([0xFF, 0xE1]), be16(payload.count + 2), payload)
    }

    private func exifPayload(make: String?, xmp: String?) -> Data {
        struct Field { var tag: Int; var type: Int; var bytes: Data }
        var fields: [Field] = []
        if let make { fields.append(Field(tag: 0x010F, type: 2, bytes: latin1(make + "\u{0}"))) }
        if let xmp { fields.append(Field(tag: 0x02BC, type: 7, bytes: latin1(xmp))) }
        let dataStart = 8 + 2 + fields.count * 12 + 4
        var cursor = dataStart
        var entries: [Data] = []
        var blobs: [Data] = []
        for field in fields {
            let value: Data
            if field.bytes.count <= 4 {
                value = field.bytes + Data(count: 4 - field.bytes.count)
            } else {
                value = le32(Int64(cursor))
                cursor += field.bytes.count
                blobs.append(field.bytes)
            }
            entries.append(cat(le16(field.tag), le16(field.type), le32(Int64(field.bytes.count)), value))
        }
        return cat(
            latin1("Exif\u{0}\u{0}"),
            latin1("II"),
            le16(42),
            le32(8),
            le16(fields.count),
            cat(entries),
            le32(0),
            cat(blobs)
        )
    }

    private func latin1(_ text: String) -> Data { Data(text.utf8) }

    private func latin1String(_ data: Data) -> String {
        String(data: data, encoding: .isoLatin1) ?? ""
    }

    private func padEnd(_ text: String, to count: Int) -> String {
        if text.count >= count { return text }
        return text + String(repeating: " ", count: count - text.count)
    }

    private func be16(_ value: Int) -> Data { Data([UInt8((value >> 8) & 0xFF), UInt8(value & 0xFF)]) }

    private func be32(_ value: Int64) -> Data {
        Data([
            UInt8((value >> 24) & 0xFF),
            UInt8((value >> 16) & 0xFF),
            UInt8((value >> 8) & 0xFF),
            UInt8(value & 0xFF),
        ])
    }

    private func le16(_ value: Int) -> Data { Data([UInt8(value & 0xFF), UInt8((value >> 8) & 0xFF)]) }

    private func le32(_ value: Int64) -> Data {
        Data([
            UInt8(value & 0xFF),
            UInt8((value >> 8) & 0xFF),
            UInt8((value >> 16) & 0xFF),
            UInt8((value >> 24) & 0xFF),
        ])
    }

    private func cat(_ parts: Data...) -> Data { cat(parts) }

    private func cat(_ parts: [Data]) -> Data {
        var out = Data()
        for part in parts { out.append(part) }
        return out
    }
}

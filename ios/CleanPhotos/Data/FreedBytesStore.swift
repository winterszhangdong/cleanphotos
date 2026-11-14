import Foundation
import Combine

/// 累计已确认永久删除的字节（只增不减）。未知大小不计入。只从垃圾桶页主线程读写。
final class FreedBytesStore: ObservableObject {
    static let key = "cleanphotos.totalFreedBytes"

    @Published private(set) var total: Int64
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let object = defaults.object(forKey: Self.key) {
            guard let number = object as? NSNumber else {
                preconditionFailure("total freed bytes 不是数字")
            }
            total = number.int64Value
        } else {
            total = 0
        }
    }

    /// bytes 必须 > 0。这一批合计为 0 时不要调用。
    func add(_ bytes: Int64) {
        precondition(bytes > 0, "bytes 必须 > 0：\(bytes)")
        let next = total + bytes
        total = next
        guard !PerfDryRun.on else { return }
        defaults.set(next, forKey: Self.key)
    }
}

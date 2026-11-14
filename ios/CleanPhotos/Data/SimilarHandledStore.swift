import Foundation

/// 已处理的相似组。进程重启后哈希会重算，这些 key 仍把对应组藏掉。
/// 每条是「还会留在相册里的 id」升序逗号连接，至少两张。
final class SimilarHandledStore {
    static let shared = SimilarHandledStore()
    private let defaultsKey = "cleanphotos.similar.handled"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func records() -> Set<String> {
        Set(defaults.stringArray(forKey: defaultsKey) ?? [])
    }

    func add(_ key: String) {
        var all = records()
        all.insert(key)
        defaults.set(all.sorted(), forKey: defaultsKey)
    }

    func remove(_ key: String) {
        var all = records()
        guard all.remove(key) != nil else { return }
        defaults.set(all.sorted(), forKey: defaultsKey)
    }
}

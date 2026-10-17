import Foundation

/// Android core/util/FileSizeFormatter：「3.1 MB」「31 MB」「1.2 GB」。
/// 1024 进制；数值 < 10 保留一位小数，≥ 10 取整；最小单位 KB（不足 1 KB 显示「0.4 KB」而不是字节）。
enum FileSizeFormatter {
    private static let units = ["KB", "MB", "GB", "TB"]

    static func format(_ bytes: Int64) -> String {
        var value = Double(max(0, bytes)) / 1024
        var unit = 0
        // 按四舍五入后的值进位，避免出现「1024 KB」「10.0 MB」
        while value >= 1023.5 && unit < units.count - 1 {
            value /= 1024
            unit += 1
        }
        let text = value < 9.95 ? String(format: "%.1f", value) : String(format: "%.0f", value)
        return "\(text) \(units[unit])"
    }

    /// 数字与单位分开（「清空后可释放 1.2 GB」里数字大字、单位小字）。
    static func parts(_ bytes: Int64) -> (number: String, unit: String) {
        let s = format(bytes)
        guard let r = s.range(of: " ", options: .backwards) else { return (s, "") }
        return (String(s[..<r.lowerBound]), String(s[r.upperBound...]))
    }
}

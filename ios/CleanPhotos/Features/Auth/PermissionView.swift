import SwiftUI

enum PermissionMode {
    case request
    case denied
}

/// 首次授权（Android PhotoPermissionScreen）：三张打包示意相纸反复显影 + 开始之前 + 三条说明；
/// 底部主按钮距底 72、「只选部分照片」距底 36（都从 home indicator 上沿量）；拒绝后主按钮改为去系统设置，不显示「只选部分」。
struct PermissionView: View {
    let mode: PermissionMode
    @EnvironmentObject private var library: PhotoLibraryService

    var body: some View {
        ZStack {
            DarkroomColors.room.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 0) {
                DevelopingPrintsArt()
                    .padding(.top, 32)
                Text("perm_eyebrow")
                    .font(DarkroomType.eyebrow)
                    .tracking(12 * 0.12)
                    .foregroundStyle(DarkroomColors.dim)
                    .padding(.top, 8)
                    .padding(.bottom, 4)
                Text("perm_title")
                    .font(DarkroomType.permTitle)
                    .foregroundStyle(DarkroomColors.paper)
                    .accessibilityAddTraits(.isHeader)
                VStack(alignment: .leading, spacing: 0) {
                    permRow(num: "01", title: "perm_item1_title", body: "perm_item1_body")
                    permRow(num: "02", title: "perm_item2_title", body: "perm_item2_body")
                    permRow(num: "03", title: "perm_item3_title", body: "perm_item3_body")
                }
                .padding(.top, 24)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 24)

            VStack(spacing: 0) {
                Spacer()
                Button(action: request) {
                    Text(mode == .denied ? "perm_open_settings" : "perm_allow")
                        .font(Font.custom("NotoSansSC-SemiBold", size: 15))
                        .foregroundStyle(DarkroomColors.room)
                        .frame(maxWidth: .infinity)
                        .frame(height: 52)
                        .background(DarkroomColors.paper, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("perm-allow")
                ZStack(alignment: .bottom) {
                    Color.clear
                    if mode != .denied {
                        // iOS 系统授权弹窗本身带「限制访问」，与 Android 14+ 一样两个入口都走同一个请求
                        Button(action: request) {
                            Text("perm_partial")
                                .font(Font.custom("NotoSansSC-Regular", size: 13))
                                .foregroundStyle(DarkroomColors.dim)
                                .padding(.horizontal, 16)
                                .frame(minHeight: 44)
                        }
                        .buttonStyle(.plain)
                        .padding(.bottom, 23) // 文字底距底 36：44pt 点击区里 13pt 文字下方约留 13
                        .accessibilityIdentifier("perm-partial")
                    }
                }
                .frame(height: 72)
            }
            .padding(.horizontal, 24)
        }
    }

    private func request() {
        Task {
            if mode == .denied {
                library.openSystemSettings()
            } else {
                await library.requestAccess()
            }
        }
    }

    private func permRow(num: String, title: LocalizedStringKey, body: LocalizedStringKey) -> some View {
        VStack(spacing: 0) {
            Rectangle().fill(DarkroomColors.rim).frame(height: 1 / UIScreen.main.scale).frame(maxWidth: .infinity)
            HStack(alignment: .top, spacing: 12) {
                Text(num)
                    .font(DarkroomFonts.plexMono(size: 11))
                    .monospacedDigit()
                    .foregroundStyle(DarkroomColors.dim)
                    .frame(width: 32, height: 32)
                    .background(DarkroomColors.tray, in: Circle())
                    .overlay(Circle().strokeBorder(DarkroomColors.rim, lineWidth: 1 / UIScreen.main.scale))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 0) {
                    Text(title)
                        .font(Font.custom("NotoSansSC-Medium", size: 14))
                        .foregroundStyle(DarkroomColors.paper)
                    Text(body)
                        .font(Font.custom("NotoSansSC-Regular", size: 12))
                        .foregroundStyle(DarkroomColors.dim)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .accessibilityElement(children: .combine)
                Spacer(minLength: 0)
            }
            .padding(.vertical, 12)
        }
    }
}

/// Android DevelopingPrintsArt：三张相纸（左 −72/−9°、右 +72/+8°、中上移 8，先画左右再画中间），
/// 每张 4.8s 一轮：0→45% 显影、45→85% 停、85→100% 褪回（draft ease-out），左延迟 300ms、右 600ms。
/// 显影 = brightness 0.05→1、contrast 0.6→1、saturate 0→1；减弱动态效果时停在显影完成。
struct DevelopingPrintsArt: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var t0 = Date()
    private static let cycle = 4.8
    private static let stops: [(Double, Double)] = [(0, 0), (0.45, 1), (0.85, 1), (1, 0)]

    var body: some View {
        TimelineView(.animation(paused: reduceMotion)) { tl in
            let t = tl.date.timeIntervalSince(t0)
            ZStack(alignment: .top) {
                printCard("PermPrintLeft", p(t, delay: 0.3)).rotationEffect(.degrees(-9)).offset(x: -72)
                printCard("PermPrintRight", p(t, delay: 0.6)).rotationEffect(.degrees(8)).offset(x: 72)
                printCard("PermPrintCenter", p(t, delay: 0)).offset(y: -8)
            }
            .frame(maxWidth: .infinity, alignment: .top)
        }
        .frame(height: 232, alignment: .top)
        .accessibilityHidden(true)
    }

    private func p(_ t: Double, delay: Double) -> Double {
        if reduceMotion { return 1 }
        let local = t - delay
        guard local > 0 else { return 0 }
        let phase = local.truncatingRemainder(dividingBy: Self.cycle) / Self.cycle
        return TutorialTimeline.keyframes(phase, Self.stops)
    }

    private func printCard(_ name: String, _ p: Double) -> some View {
        Image(name)
            .resizable()
            .scaledToFill()
            .frame(width: 108, height: 126)
            .clipped()
            .colorMultiply(Color(white: 0.05 + 0.95 * p))
            .contrast(0.6 + 0.4 * p)
            .saturation(p)
            .padding(.horizontal, 6).padding(.top, 6).padding(.bottom, 20)
            .background(DarkroomColors.printPaper)
            .clipShape(RoundedRectangle(cornerRadius: 4))
            .shadow(color: DarkroomColors.printShadow, radius: 16, y: 16)
            .padding(.top, 24)
    }
}

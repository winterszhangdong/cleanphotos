import Photos
import SwiftUI

struct SimilarThumbView: View {
    let item: PhotoItem
    let side: CGFloat
    var mark: SimilarMark
    var framed: Bool = false
    var label: String
    var onTap: () -> Void

    @EnvironmentObject private var library: PhotoLibraryService
    @State private var image: UIImage?
    @State private var token: ThumbnailToken?

    var body: some View {
        Button(action: onTap) {
            ZStack(alignment: .topTrailing) {
                DarkroomColors.tileFill
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .accessibilityHidden(true)
                        .scaledToFill()
                        .frame(width: side, height: side)
                        .clipped()
                }
                if mark == .remove {
                    SimilarRemoveBadge()
                        .padding(8)
                }
            }
            .frame(width: side, height: side)
            .clipped()
            .overlay {
                if framed {
                    Rectangle().strokeBorder(DarkroomColors.paper, lineWidth: 2)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
        .onAppear { load() }
        .onDisappear { cancel() }
    }

    private func load() {
        guard image == nil else { return }
        if let hit = library.thumbnails.cached(id: item.id, side: side, style: .normal) {
            self.image = hit
            return
        }
        token = library.thumbnails.request(asset: item.asset, side: side, style: .normal) { img, _ in
            self.image = img
        }
    }

    private func cancel() {
        token?.cancel()
        token = nil
    }
}

/// 移走：22pt 相纸白圆，没有黑边，垃圾桶图标。照片本身保持原色。
struct SimilarRemoveBadge: View {
    var body: some View {
        Circle()
            .fill(DarkroomColors.paper)
            .frame(width: 22, height: 22)
            .overlay {
                Image(systemName: "trash.fill")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(DarkroomColors.room)
            }
            .accessibilityHidden(true)
    }
}

struct SimilarCommitButton: View {
    let title: String
    var enabled: Bool = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(Font.custom("NotoSansSC-Medium", size: 13))
                .foregroundStyle(DarkroomColors.paper)
                .lineLimit(1)
                .frame(maxWidth: .infinity)
                .frame(height: 36)
                .background {
                    Capsule()
                        .fill(DarkroomColors.glass)
                        .background(.ultraThinMaterial, in: Capsule())
                }
                .overlay(Capsule().strokeBorder(DarkroomColors.glassRim, lineWidth: 1))
                .opacity(enabled ? 1 : 0.45)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

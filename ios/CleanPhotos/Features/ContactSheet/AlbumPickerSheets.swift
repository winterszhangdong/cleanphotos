import SwiftUI

/// 新袋子：空标签上写名字。和已有相册同名（忽略大小写和首尾空格）就合并，不另建。
struct NewAlbumSheet: View {
    let count: Int
    let albums: [FilingAlbumRef]
    let locale: Locale
    let onCancel: () -> Void
    let onCommit: (String) -> Void

    @State private var name = ""
    @FocusState private var focused: Bool

    var body: some View {
        let trimmed = AlbumNameRules.sanitize(name).trimmingCharacters(in: .whitespacesAndNewlines)
        let valid = AlbumNameRules.isValid(trimmed)
        let merge = valid && AlbumNameRules.existing(name: trimmed, albums: albums) != nil
        VStack(alignment: .leading, spacing: 16) {
            Text(FilingCopy.newAlbumTitle(count, locale: locale))
                .font(Font.custom("NotoSansSC-SemiBold", size: 18))
                .foregroundStyle(DarkroomColors.paper)
            TextField(FilingCopy.albumPlaceholder(locale), text: $name)
                .focused($focused)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .font(DarkroomType.body)
                .foregroundStyle(DarkroomColors.paper)
                .padding(.horizontal, 14)
                .frame(height: 44)
                .background(DarkroomColors.tray2, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(DarkroomColors.rim2, lineWidth: 1))
                .onSubmit { if valid { onCommit(trimmed) } }
            Text(status(trimmed: trimmed, valid: valid, merge: merge))
                .font(DarkroomType.dayMeta)
                .foregroundStyle(DarkroomColors.dim)
                .frame(minHeight: 16)
            HStack(spacing: 12) {
                Button(action: onCancel) {
                    Text(L10n.string("cancel", locale: locale))
                        .font(Font.custom("NotoSansSC-Medium", size: 14))
                        .foregroundStyle(DarkroomColors.paper)
                        .frame(maxWidth: .infinity)
                        .frame(height: 44)
                        .background(DarkroomColors.tray2, in: Capsule())
                }
                .buttonStyle(.plain)
                Button(action: { onCommit(trimmed) }) {
                    Text(FilingCopy.putIn(locale))
                        .font(Font.custom("NotoSansSC-SemiBold", size: 14))
                        .foregroundStyle(DarkroomColors.room)
                        .frame(maxWidth: .infinity)
                        .frame(height: 44)
                        .background(DarkroomColors.paper, in: Capsule())
                }
                .buttonStyle(.plain)
                .disabled(!valid)
                .opacity(valid ? 1 : 0.3)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(DarkroomColors.room)
        .preferredColorScheme(.dark)
        .presentationDetents([.height(280)])
        .presentationDragIndicator(.visible)
        .presentationBackground(DarkroomColors.room)
        .onAppear { focused = true }
    }

    private func status(trimmed: String, valid: Bool, merge: Bool) -> String {
        guard valid else { return " " }
        return merge ? FilingCopy.mergeHint(locale) : FilingCopy.pathNew(trimmed, locale: locale)
    }
}

/// 「还有 N 个」：按标题搜，点一行就放进去。不做拼音。
struct AlbumSearchSheet: View {
    let albums: [FilingAlbumRef]
    let locale: Locale
    let showHint: Bool
    let onPick: (FilingAlbumRef) -> Void
    let onCreate: (String) -> Void

    @State private var query = ""

    private var filtered: [FilingAlbumRef] { AlbumSearch.filter(albums, query: query) }
    private var createName: String? { AlbumSearch.createName(albums, query: query) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(FilingCopy.trayTitle(locale))
                .font(Font.custom("NotoSansSC-SemiBold", size: 18))
                .foregroundStyle(DarkroomColors.paper)
            if showHint {
                Text(FilingCopy.parkedHint(locale))
                    .font(DarkroomType.dayMeta)
                    .foregroundStyle(DarkroomColors.dim)
            }
            HStack(spacing: 8) {
                DarkroomIconView(icon: .search, size: 16)
                    .foregroundStyle(DarkroomColors.dim)
                TextField(FilingCopy.searchHint(locale), text: $query)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(DarkroomType.body)
                    .foregroundStyle(DarkroomColors.paper)
                if !query.isEmpty {
                    Button(FilingCopy.searchClear(locale)) { query = "" }
                        .font(Font.custom("NotoSansSC-Medium", size: 13))
                        .foregroundStyle(DarkroomColors.dim)
                        .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 12)
            .frame(height: 40)
            .background(DarkroomColors.tray2, in: Capsule())
            ScrollView {
                LazyVStack(spacing: 0) {
                    if let createName {
                        Button {
                            onCreate(createName)
                        } label: {
                            row(title: FilingCopy.createRow(createName, locale: locale), meta: FilingCopy.pathNew(createName, locale: locale))
                        }
                        .buttonStyle(.plain)
                    }
                    ForEach(filtered, id: \.id) { album in
                        Button {
                            onPick(album)
                        } label: {
                            row(title: album.title.isEmpty ? "—" : album.title, meta: meta(album))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .scrollIndicators(.hidden)
        }
        .padding(.horizontal, 20)
        .padding(.top, 20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(DarkroomColors.room)
        .preferredColorScheme(.dark)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationBackground(DarkroomColors.room)
    }

    private func row(title: String, meta: String) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Text(title)
                    .font(Font.custom("NotoSansSC-Medium", size: 15))
                    .foregroundStyle(DarkroomColors.paper)
                    .lineLimit(1)
                Spacer(minLength: 8)
                Text(meta)
                    .font(DarkroomType.dayRight)
                    .foregroundStyle(DarkroomColors.dim)
                    .lineLimit(1)
            }
            .frame(height: 52)
            Rectangle().fill(DarkroomColors.rim).frame(height: 1)
        }
    }

    private func meta(_ album: FilingAlbumRef) -> String {
        var parts = [FilingCopy.albumCount(album.count, locale: locale)]
        if let when = filedWhen(album) { parts.append(when) }
        return parts.joined(separator: " · ")
    }

    private func filedWhen(_ album: FilingAlbumRef) -> String? {
        let kind = LastFiledRules.kind(filedAt: album.lastFiledAt, now: Date().timeIntervalSince1970, calendar: .current)
        switch kind {
        case nil: return nil
        case .today: return FilingCopy.timeToday(locale)
        case .daysAgo(let n): return FilingCopy.timeDaysAgo(n, locale: locale)
        case .on(let date):
            let format = FilingCopy.chinese(locale) ? "M月d日" : "MMM d"
            return DateFormatCache.string(date, format: format, locale: locale)
        }
    }
}

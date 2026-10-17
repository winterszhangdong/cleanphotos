import SwiftUI
import Photos

struct RootView: View {
    @EnvironmentObject private var library: PhotoLibraryService
    @EnvironmentObject private var language: LanguageStore

    var body: some View {
        Group {
            switch library.authorization {
            case .notDetermined:
                PermissionView(mode: .request)
            case .denied, .restricted:
                PermissionView(mode: .denied)
            case .authorized, .limited:
                MainTabView()
            @unknown default:
                PermissionView(mode: .request)
            }
        }
        .environment(\.locale, language.effectiveLocale)
        .onAppear { PerfLog.once("rootAppear", "auth=\(library.authorization.rawValue)") }
        .task {
            await library.refreshAuthorization()
        }
    }
}

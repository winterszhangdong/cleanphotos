import SwiftUI
import Photos

struct RootView: View {
    @EnvironmentObject private var library: PhotoLibraryService
    @EnvironmentObject private var language: LanguageStore
    @Environment(\.scenePhase) private var scenePhase

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
        // 从系统设置改完照片权限回来：不用重开 App
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await library.refreshAuthorizationIfChanged() } }
        }
    }
}

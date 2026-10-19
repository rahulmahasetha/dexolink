import SwiftUI

@main
struct DexoLinkiOSApp: App {
    @StateObject private var appState = iOSAppState.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onChange(of: scenePhase) { phase in
                    switch phase {
                    case .active:
                        // Refresh clipboard and local device discovery when app becomes active
                        if appState.autoClipboardSync, let clip = UIPasteboard.general.string, !clip.isEmpty {
                            appState.pushClipboardText(clip)
                        }
                    case .background:
                        print("[DexoLink iOS] App entered background")
                    default:
                        break
                    }
                }
        }
    }
}

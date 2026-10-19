import SwiftUI

public struct ContentView: View {
    @ObservedObject var appState = iOSAppState.shared

    public init() {}

    public var body: some View {
        TabView {
            TransferView(appState: appState)
                .tabItem {
                    Label("Transfer", systemImage: "arrow.up.and.down.circle.fill")
                }

            ClipboardView(appState: appState)
                .tabItem {
                    Label("Clipboard", systemImage: "doc.on.clipboard.fill")
                }

            ContinuityView(appState: appState)
                .tabItem {
                    Label("Continuity", systemImage: "safari.fill")
                }

            DevicesView(appState: appState)
                .tabItem {
                    Label("Devices", systemImage: "gearshape.fill")
                }
        }
        .tint(.blue)
    }
}

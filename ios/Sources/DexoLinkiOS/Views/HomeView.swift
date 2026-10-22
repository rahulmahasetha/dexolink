import SwiftUI
#if canImport(UIKit)
import UIKit
#endif
#if canImport(UniformTypeIdentifiers)
import UniformTypeIdentifiers
#endif

public struct HomeView: View {
    @ObservedObject var appState: iOSAppState
    var onNavigateQR: () -> Void = {}
    
    @State private var showingReceiveSheet = false
    @State private var showingNotificationsSheet = false
    @State private var showingConnectIPSheet = false
    @State private var showingFilePicker = false
    @State private var manualHostInput = ""
    @State private var manualPortInput = "52840"
    @State private var selectedDeviceForMenu: String? = nil

    public init(appState: iOSAppState, onNavigateQR: @escaping () -> Void = {}) {
        self.appState = appState
        self.onNavigateQR = onNavigateQR
    }

    public var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                // 1. Header (Logo, Name, "Rahul's Samsung · Visible", Bell with red dot)
                HStack(spacing: 12) {
                    // App Logo: blue squircle with white DexoLink infinity icon
                    ZStack {
                        RoundedRectangle(cornerRadius: 14)
                            .fill(Color(hex: "2563EB"))
                            .frame(width: 48, height: 48)

                        Image(systemName: "infinity")
                            .font(.system(size: 24, weight: .bold))
                            .foregroundColor(.white)
                    }

                    // Title and Device Subtitle
                    VStack(alignment: .leading, spacing: 2) {
                        Text("DexoLink")
                            .font(.system(size: 22, weight: .bold))
                            .foregroundColor(Color(hex: "0F172A"))

                        Text("\(appState.localDeviceName) · Visible")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundColor(Color(hex: "64748B"))
                    }

                    Spacer()

                    // Notification Bell Button with red dot
                    Button(action: {
                        showingNotificationsSheet = true
                    }) {
                        ZStack(alignment: .topTrailing) {
                            RoundedRectangle(cornerRadius: 14)
                                .fill(Color.white)
                                .frame(width: 44, height: 44)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 14)
                                        .stroke(Color(hex: "E2E8F0"), lineWidth: 1)
                                )

                            Image(systemName: "bell.fill")
                                .font(.system(size: 18))
                                .foregroundColor(Color(hex: "0F172A"))
                                .frame(width: 44, height: 44)

                            // Red dot indicator
                            Circle()
                                .fill(Color(hex: "EF4444"))
                                .frame(width: 8, height: 8)
                                .offset(x: -10, y: 10)
                        }
                    }
                    .buttonStyle(.plain)
                }
                .padding(.top, 8)

                // 2. Action Tiles: Send, Receive, Scan QR
                HStack(spacing: 12) {
                    // Tile 1: Send (Solid blue)
                    Button(action: {
                        showingFilePicker = true
                    }) {
                        VStack(spacing: 8) {
                            Image(systemName: "arrow.up")
                                .font(.system(size: 22, weight: .bold))
                                .foregroundColor(.white)

                            Text("Send")
                                .font(.system(size: 15, weight: .bold))
                                .foregroundColor(.white)
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 106)
                        .background(Color(hex: "2563EB"))
                        .cornerRadius(20)
                    }
                    .buttonStyle(.plain)

                    // Tile 2: Receive (White card)
                    Button(action: {
                        showingReceiveSheet = true
                    }) {
                        VStack(spacing: 8) {
                            Image(systemName: "arrow.down")
                                .font(.system(size: 22, weight: .bold))
                                .foregroundColor(Color(hex: "2563EB"))

                            Text("Receive")
                                .font(.system(size: 15, weight: .bold))
                                .foregroundColor(Color(hex: "0F172A"))
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 106)
                        .background(Color.white)
                        .cornerRadius(20)
                        .overlay(
                            RoundedRectangle(cornerRadius: 20)
                                .stroke(Color(hex: "E2E8F0"), lineWidth: 1)
                        )
                    }
                    .buttonStyle(.plain)

                    // Tile 3: Scan QR (White card)
                    Button(action: {
                        onNavigateQR()
                    }) {
                        VStack(spacing: 8) {
                            Image(systemName: "qrcode.viewfinder")
                                .font(.system(size: 22, weight: .bold))
                                .foregroundColor(Color(hex: "2563EB"))

                            Text("Scan QR")
                                .font(.system(size: 15, weight: .bold))
                                .foregroundColor(Color(hex: "0F172A"))
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 106)
                        .background(Color.white)
                        .cornerRadius(20)
                        .overlay(
                            RoundedRectangle(cornerRadius: 20)
                                .stroke(Color(hex: "E2E8F0"), lineWidth: 1)
                        )
                    }
                    .buttonStyle(.plain)
                }

                // 3. Current devices Section
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 8) {
                        Text("Current devices")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundColor(Color(hex: "334155"))

                        // Badge: "3 connected" or "${count} connected"
                        Text("\(connectedDevicesList.count) connected")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(Color(hex: "2563EB"))
                            .padding(.horizontal, 10)
                            .padding(.vertical, 4)
                            .background(Color(hex: "DBEAFE"))
                            .cornerRadius(12)

                        Spacer()

                        // Scan Network button ((●))
                        Button(action: {
                            appState.refreshDiscovery()
                        }) {
                            ZStack {
                                RoundedRectangle(cornerRadius: 12)
                                    .fill(Color.white)
                                    .frame(width: 40, height: 40)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 12)
                                            .stroke(Color(hex: "E2E8F0"), lineWidth: 1)
                                    )

                                Image(systemName: "antenna.radiowaves.left.and.right")
                                    .font(.system(size: 17))
                                    .foregroundColor(Color(hex: "2563EB"))
                            }
                        }
                        .buttonStyle(.plain)
                    }

                    // White Card Container
                    VStack(spacing: 0) {
                        if connectedDevicesList.isEmpty {
                            HStack(spacing: 12) {
                                Image(systemName: "antenna.radiowaves.left.and.right")
                                    .foregroundColor(Color(hex: "94A3B8"))
                                    .font(.system(size: 20))

                                VStack(alignment: .leading, spacing: 2) {
                                    Text("No devices connected")
                                        .font(.system(size: 14, weight: .semibold))
                                        .foregroundColor(Color(hex: "334155"))
                                    Text("Select a device below or scan QR to connect")
                                        .font(.system(size: 12))
                                        .foregroundColor(Color(hex: "64748B"))
                                }
                                Spacer()
                            }
                            .padding(16)
                        } else {
                            ForEach(Array(connectedDevicesList.enumerated()), id: \.element.id) { index, dev in
                                HStack(spacing: 14) {
                                    Image(systemName: dev.systemIcon)
                                        .font(.system(size: 22))
                                        .foregroundColor(Color(hex: "2563EB"))
                                        .frame(width: 28)

                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(dev.name)
                                            .font(.system(size: 15, weight: .bold))
                                            .foregroundColor(Color(hex: "2563EB"))
                                        Text(dev.status)
                                            .font(.system(size: 12))
                                            .foregroundColor(Color(hex: "64748B"))
                                    }

                                    Spacer()

                                    // Red Disconnect Button
                                    Button(action: {
                                        appState.disconnect()
                                    }) {
                                        Text("Disconnect")
                                            .font(.system(size: 13, weight: .bold))
                                            .foregroundColor(Color(hex: "DC2626"))
                                            .padding(.horizontal, 14)
                                            .padding(.vertical, 7)
                                            .background(Color(hex: "FEE2E2"))
                                            .cornerRadius(20)
                                    }
                                    .buttonStyle(.plain)

                                    // ⋮ Menu
                                    Menu {
                                        Button("Send File") {
                                            showingFilePicker = true
                                        }
                                        Button("Unpair Device", role: .destructive) {
                                            appState.unpairDevice(deviceId: dev.id)
                                        }
                                    } label: {
                                        Image(systemName: "ellipsis")
                                            .rotationEffect(.degrees(90))
                                            .font(.system(size: 18))
                                            .foregroundColor(Color(hex: "64748B"))
                                            .frame(width: 28, height: 36)
                                    }
                                }
                                .padding(.horizontal, 16)
                                .padding(.vertical, 14)

                                if index < connectedDevicesList.count - 1 {
                                    Divider()
                                        .background(Color(hex: "F1F5F9"))
                                        .padding(.leading, 56)
                                }
                            }
                        }
                    }
                    .background(Color.white)
                    .cornerRadius(24)
                    .overlay(
                        RoundedRectangle(cornerRadius: 24)
                            .stroke(Color(hex: "E2E8F0"), lineWidth: 1)
                    )
                }

                // 4. Available devices Section
                VStack(alignment: .leading, spacing: 10) {
                    Text("Available devices")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(Color(hex: "334155"))

                    VStack(spacing: 0) {
                        ForEach(Array(availableDevicesList.enumerated()), id: \.element.id) { index, dev in
                            HStack(spacing: 14) {
                                Image(systemName: "ipad.and.iphone")
                                    .font(.system(size: 22))
                                    .foregroundColor(Color(hex: "2563EB"))
                                    .frame(width: 28)

                                VStack(alignment: .leading, spacing: 2) {
                                    Text(dev.name)
                                        .font(.system(size: 15, weight: .bold))
                                        .foregroundColor(Color(hex: "0F172A"))
                                    Text("Nearby")
                                        .font(.system(size: 12))
                                        .foregroundColor(Color(hex: "64748B"))
                                }

                                Spacer()

                                // Blue Connect Button
                                Button(action: {
                                    appState.pairDevice(dev)
                                }) {
                                    Text("Connect")
                                        .font(.system(size: 14, weight: .bold))
                                        .foregroundColor(Color(hex: "2563EB"))
                                        .padding(.horizontal, 16)
                                        .padding(.vertical, 7)
                                        .background(Color(hex: "DBEAFE"))
                                        .cornerRadius(20)
                                }
                                .buttonStyle(.plain)
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 14)

                            Divider()
                                .background(Color(hex: "F1F5F9"))
                                .padding(.leading, 56)
                        }

                        // Connect by IP address row
                        Button(action: {
                            showingConnectIPSheet = true
                        }) {
                            HStack(spacing: 14) {
                                Image(systemName: "wifi")
                                    .font(.system(size: 20))
                                    .foregroundColor(Color(hex: "2563EB"))
                                    .frame(width: 28)

                                Text("Connect by IP address")
                                    .font(.system(size: 15, weight: .bold))
                                    .foregroundColor(Color(hex: "0F172A"))

                                Spacer()
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 16)
                        }
                        .buttonStyle(.plain)
                    }
                    .background(Color.white)
                    .cornerRadius(24)
                    .overlay(
                        RoundedRectangle(cornerRadius: 24)
                            .stroke(Color(hex: "E2E8F0"), lineWidth: 1)
                    )
                }

                Spacer().frame(height: 100)
            }
            .padding(.horizontal, 20)
        }
        .background(Color(hex: "F3F6FC"))
        .sheet(isPresented: $showingReceiveSheet) {
            ReceiveSheetView(appState: appState, onShowQR: {
                showingReceiveSheet = false
                onNavigateQR()
            })
        }
        .sheet(isPresented: $showingNotificationsSheet) {
            NotificationsSheetView(appState: appState)
        }
        .sheet(isPresented: $showingConnectIPSheet) {
            ConnectIPSheetView(appState: appState)
        }
        .sheet(isPresented: $showingFilePicker) {
            FilePickerBridge { url in
                appState.sendFile(url: url, targetDevice: appState.discoveredDevices.first)
            }
        }
    }

    private var connectedDevicesList: [DisplayedDevice] {
        var list: [DisplayedDevice] = []
        if appState.isConnected {
            list.append(DisplayedDevice(
                id: "active",
                name: appState.connectedDeviceName,
                status: "Connected",
                systemIcon: iconForDeviceName(appState.connectedDeviceName)
            ))
        }
        for dev in appState.pairedDevices {
            if !list.contains(where: { $0.name.lowercased() == dev.name.lowercased() }) {
                list.append(DisplayedDevice(
                    id: dev.id,
                    name: dev.name,
                    status: appState.isConnected ? "Connected" : "Connected",
                    systemIcon: iconForDeviceName(dev.name)
                ))
            }
        }
        return list
    }

    private var availableDevicesList: [DiscoveredDevice] {
        return appState.discoveredDevices.filter { dev in
            !connectedDevicesList.contains(where: { $0.name.lowercased() == dev.name.lowercased() })
        }
    }

    private func iconForDeviceName(_ name: String) -> String {
        let n = name.lowercased()
        if n.contains("mac") || n.contains("laptop") {
            return "laptopcomputer"
        } else if n.contains("pc") || n.contains("windows") {
            return "desktopcomputer"
        } else {
            return "iphone"
        }
    }
}

// Model for Displayed Device
struct DisplayedDevice: Identifiable {
    let id: String
    let name: String
    let status: String
    let systemIcon: String
}

// Sheet: Receive Readiness
struct ReceiveSheetView: View {
    @ObservedObject var appState: iOSAppState
    var onShowQR: () -> Void
    @Environment(\.dismiss) var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Image(systemName: "arrow.down.circle.fill")
                    .font(.system(size: 54))
                    .foregroundColor(Color(hex: "2563EB"))
                    .padding(.top, 20)

                Text("Ready to Receive Files")
                    .font(.title2)
                    .fontWeight(.bold)
                    .foregroundColor(Color(hex: "0F172A"))

                Text("Your iPhone '\(appState.localDeviceName)' is listening on local port 52840. Other devices can send files directly or scan your QR code.")
                    .font(.subheadline)
                    .foregroundColor(Color(hex: "64748B"))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)

                Spacer()

                Button(action: onShowQR) {
                    Text("Show My QR Code")
                        .font(.headline)
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .frame(height: 52)
                        .background(Color(hex: "2563EB"))
                        .cornerRadius(16)
                }
                .padding(.horizontal, 24)

                Button("Done") {
                    dismiss()
                }
                .foregroundColor(Color(hex: "64748B"))
                .padding(.bottom, 20)
            }
            .navigationTitle("Receive")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

// Sheet: Notifications
struct NotificationsSheetView: View {
    @ObservedObject var appState: iOSAppState
    @Environment(\.dismiss) var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section(header: Text("Device Status")) {
                    HStack {
                        Text("Local Name")
                        Spacer()
                        Text(appState.localDeviceName).foregroundColor(.secondary)
                    }
                    HStack {
                        Text("Status")
                        Spacer()
                        Text(appState.isConnected ? "Connected to \(appState.connectedDeviceName)" : "Visible on Wi-Fi")
                            .foregroundColor(appState.isConnected ? .green : .blue)
                    }
                }

                Section(header: Text("Recent Transfers")) {
                    if appState.transferHistory.isEmpty {
                        Text("No recent transfers").foregroundColor(.secondary)
                    } else {
                        ForEach(appState.transferHistory) { item in
                            HStack {
                                Text(item.fileName)
                                Spacer()
                                Text(item.isIncoming ? "Received" : "Sent").font(.caption).foregroundColor(.secondary)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Notifications")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
    }
}

// Sheet: Connect by IP
struct ConnectIPSheetView: View {
    @ObservedObject var appState: iOSAppState
    @State private var host = ""
    @State private var port = "52840"
    @Environment(\.dismiss) var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section(header: Text("Remote Device IP")) {
                    TextField("192.168.1.xxx", text: $host)
                        .autocapitalization(.none)
                        .disableAutocorrection(true)
                    TextField("52840", text: $port)
                        .keyboardType(.numberPad)
                }

                Button("Connect") {
                    if !host.isEmpty {
                        let p = UInt16(port) ?? 52840
                        appState.connectToRemoteEndpoint(host: host, port: p)
                        dismiss()
                    }
                }
                .disabled(host.isEmpty)
            }
            .navigationTitle("Connect by IP")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
}

// Native UIDocumentPickerViewController Bridge
struct FilePickerBridge: UIViewControllerRepresentable {
    var onPick: (URL) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    class Coordinator: NSObject, UIDocumentPickerDelegate {
        let parent: FilePickerBridge
        init(_ parent: FilePickerBridge) { self.parent = parent }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            if let first = urls.first {
                parent.onPick(first)
            }
        }
    }
}

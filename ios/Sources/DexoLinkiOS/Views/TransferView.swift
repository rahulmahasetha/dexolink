import SwiftUI
import PhotosUI

struct TransferView: View {
    @ObservedObject var appState: iOSAppState
    @State private var selectedPhotos: [PhotosPickerItem] = []
    @State private var showDocumentPicker = false
    @State private var selectedDevice: DiscoveredDevice?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    // Status Header Card
                    HStack(spacing: 12) {
                        Circle()
                            .fill(appState.isConnected ? Color.green : Color.orange)
                            .frame(width: 12, height: 12)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(appState.isConnected ? "Connected" : "Ready for Connection")
                                .font(.subheadline)
                                .fontWeight(.bold)
                            Text(appState.connectedDeviceName)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }

                        Spacer()

                        Image(systemName: "wifi")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(.blue)
                    }
                    .padding()
                    .background(Color(.secondarySystemBackground))
                    .cornerRadius(14)

                    // Target Device Selector (Active when Multiple Paired Devices is enabled)
                    if appState.isMultiplePairedDevicesEnabled && !appState.pairedDevices.isEmpty {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Target Device")
                                .font(.caption)
                                .fontWeight(.semibold)
                                .foregroundColor(.secondary)

                            Picker("Target Device", selection: $selectedDevice) {
                                Text("Auto (First Available)").tag(nil as DiscoveredDevice?)
                                ForEach(appState.discoveredDevices) { dev in
                                    Text(dev.name).tag(dev as DiscoveredDevice?)
                                }
                            }
                            .pickerStyle(.menu)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .background(Color(.secondarySystemBackground))
                            .cornerRadius(10)
                        }
                    }

                    // Main Action Buttons Card
                    VStack(spacing: 12) {
                        // Photos & Videos Picker
                        PhotosPicker(selection: $selectedPhotos, matching: .any(of: [.images, .videos])) {
                            HStack(spacing: 10) {
                                Image(systemName: "photo.on.rectangle.angled")
                                    .font(.system(size: 18, weight: .bold))
                                Text("Send Photos & Videos")
                                    .font(.headline)
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .font(.caption)
                                    .foregroundColor(.white.opacity(0.8))
                            }
                            .foregroundColor(.white)
                            .padding()
                            .frame(maxWidth: .infinity)
                            .background(Color.blue)
                            .cornerRadius(14)
                        }
                        .onChange(of: selectedPhotos) { newItems in
                            guard !newItems.isEmpty else { return }
                            // Process selected photo
                            for item in newItems {
                                item.loadTransferable(type: Data.self) { result in
                                    if case .success(let data) = result, let data = data {
                                        let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent("Photo_\(UUID().uuidString.prefix(6)).jpg")
                                        try? data.write(to: tempURL)
                                        DispatchQueue.main.async {
                                            appState.sendFile(url: tempURL, targetDevice: selectedDevice)
                                        }
                                    }
                                }
                            }
                            selectedPhotos.removeAll()
                        }

                        // Files & Documents Picker Button
                        Button(action: { showDocumentPicker = true }) {
                            HStack(spacing: 10) {
                                Image(systemName: "folder.fill")
                                    .font(.system(size: 18, weight: .bold))
                                    .foregroundColor(.blue)
                                Text("Send Files & Documents")
                                    .font(.headline)
                                    .foregroundColor(.primary)
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            .padding()
                            .frame(maxWidth: .infinity)
                            .background(Color(.secondarySystemBackground))
                            .cornerRadius(14)
                        }
                    }

                    // Active Progress Bar Card (if transferring)
                    if let transfer = appState.activeTransfer {
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Image(systemName: "arrow.up.circle.fill")
                                    .foregroundColor(.blue)
                                Text("Sending \(transfer.fileName)")
                                    .font(.subheadline)
                                    .fontWeight(.semibold)
                                    .lineLimit(1)
                                Spacer()
                                Text("\(Int(transfer.progressFraction * 100))%")
                                    .font(.caption)
                                    .fontWeight(.bold)
                                    .foregroundColor(.blue)
                            }

                            ProgressView(value: transfer.progressFraction)
                                .tint(.blue)

                            HStack {
                                Text("\(transfer.bytesSent / 1024) KB / \(transfer.totalBytes / 1024) KB")
                                    .font(.caption2)
                                    .foregroundColor(.secondary)
                                Spacer()
                                Text(transfer.isComplete ? "Verified ✅" : "Streaming 64KB chunks...")
                                    .font(.caption2)
                                    .foregroundColor(transfer.isComplete ? .green : .secondary)
                            }
                        }
                        .padding()
                        .background(Color(.secondarySystemBackground))
                        .cornerRadius(14)
                    }

                    // Recent Transfers List
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Recent Transfers")
                            .font(.headline)
                            .foregroundColor(.primary)

                        if appState.transferHistory.isEmpty {
                            Text("No transfers yet. Select photos or files above to send directly to your Mac, PC, or Android over local WiFi.")
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding()
                                .background(Color(.secondarySystemBackground))
                                .cornerRadius(14)
                        } else {
                            ForEach(appState.transferHistory) { item in
                                HStack(spacing: 12) {
                                    Image(systemName: item.isIncoming ? "arrow.down.circle.fill" : "arrow.up.circle.fill")
                                        .foregroundColor(item.isIncoming ? .green : .blue)
                                        .font(.title3)

                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(item.fileName)
                                            .font(.subheadline)
                                            .fontWeight(.semibold)
                                            .lineLimit(1)
                                        Text("\(item.isIncoming ? "Received from" : "Sent to") \(item.peerName) • \(item.fileSize / 1024) KB")
                                            .font(.caption2)
                                            .foregroundColor(.secondary)
                                    }

                                    Spacer()

                                    Text(item.timestamp, style: .time)
                                        .font(.caption2)
                                        .foregroundColor(.secondary)
                                }
                                .padding()
                                .background(Color(.secondarySystemBackground))
                                .cornerRadius(12)
                            }
                        }
                    }
                }
                .padding()
            }
            .navigationTitle("DexoLink")
            .fileImporter(isPresented: $showDocumentPicker, allowedContentTypes: [.item]) { result in
                if case .success(let url) = result {
                    guard url.startAccessingSecurityScopedResource() else { return }
                    defer { url.stopAccessingSecurityScopedResource() }
                    appState.sendFile(url: url, targetDevice: selectedDevice)
                }
            }
        }
    }
}

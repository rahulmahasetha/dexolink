import SwiftUI
import AppKit
import Core
import Networking
import Crypto

// MARK: - Share Window Controller

class ShareWindowController: NSWindowController {
    static var currentController: ShareWindowController?

    static func present(appState: AppState, urls: [URL], text: String?) {
        DispatchQueue.main.async {
            if let existing = currentController {
                existing.window?.close()
                currentController = nil
            }

            let window = NSWindow(
                contentRect: NSRect(x: 0, y: 0, width: 440, height: 420),
                styleMask: [.titled, .closable, .miniaturizable],
                backing: .buffered,
                defer: false
            )
            window.title = "Share with DexoLink"
            window.center()
            window.isReleasedWhenClosed = false
            window.level = .floating

            let rootView = ShareWindowView(appState: appState, urls: urls, text: text) {
                window.close()
                currentController = nil
            }
            window.contentView = NSHostingView(rootView: rootView)

            let controller = ShareWindowController(window: window)
            currentController = controller
            controller.showWindow(nil)
            NSApp.activate(ignoringOtherApps: true)
            window.makeKeyAndOrderFront(nil)
        }
    }
}

// MARK: - Share Window View

struct ShareWindowView: View {
    @ObservedObject var appState: AppState
    let urls: [URL]
    let text: String?
    let onDismiss: () -> Void

    @State private var isSending = false
    @State private var isComplete = false
    @State private var errorMessage: String? = nil

    private var pairedDevice: TrustedDevice? {
        TrustStore.shared.getPairedDevice()
    }

    private var isConnected: Bool {
        appState.connectionState.isConnected
    }

    private var totalBytes: Int64 {
        urls.reduce(0) { total, url in
            if let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
               let size = attrs[.size] as? Int64 {
                return total + size
            }
            return total
        }
    }

    var body: some View {
        VStack(spacing: 16) {
            // 1. Header
            HStack(spacing: 10) {
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundColor(.blue)

                VStack(alignment: .leading, spacing: 2) {
                    Text("DexoLink Share")
                        .font(.headline)
                        .fontWeight(.bold)
                    Text("Direct high-speed local transfer • Encrypted")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }

                Spacer()

                Button(action: onDismiss) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundColor(.secondary)
                        .font(.system(size: 16))
                }
                .buttonStyle(.plain)
            }

            Divider()

            // 2. Destination Device Card
            if let paired = pairedDevice {
                HStack(spacing: 12) {
                    Image(systemName: "iphone.gen3")
                        .font(.system(size: 28))
                        .foregroundColor(.blue)

                    VStack(alignment: .leading, spacing: 3) {
                        HStack(spacing: 6) {
                            Text(paired.deviceName)
                                .font(.system(size: 13, weight: .bold))
                            Text("Android")
                                .font(.system(size: 9, weight: .bold))
                                .foregroundColor(.secondary)
                                .padding(.horizontal, 5)
                                .padding(.vertical, 1)
                                .background(Color.secondary.opacity(0.12))
                                .cornerRadius(4)
                        }

                        HStack(spacing: 5) {
                            Circle()
                                .fill(isConnected ? Color.green : Color.red)
                                .frame(width: 7, height: 7)

                            Text(isConnected ? "Connected • Ready to receive" : "Disconnected • Phone is offline")
                                .font(.caption2)
                                .foregroundColor(isConnected ? .green : .red)
                                .fontWeight(.medium)
                        }
                    }

                    Spacer()

                    if !isConnected {
                        Button("Reconnect") {
                            reconnect()
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.mini)
                        .tint(.blue)
                    }
                }
                .padding(10)
                .background(Color(nsColor: .controlBackgroundColor))
                .cornerRadius(10)
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .stroke(isConnected ? Color.blue.opacity(0.2) : Color.red.opacity(0.2), lineWidth: 1)
                )
            } else {
                VStack(spacing: 6) {
                    Image(systemName: "lock.slash.fill")
                        .font(.system(size: 24))
                        .foregroundColor(.red)
                    Text("No Paired Android Device")
                        .font(.subheadline)
                        .fontWeight(.bold)
                        .foregroundColor(.red)
                    Text("DexoLink requires an authorized paired device before sharing.")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding(12)
                .frame(maxWidth: .infinity)
                .background(Color.red.opacity(0.08))
                .cornerRadius(10)
            }

            // 3. Content to Share
            if !urls.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text("\(urls.count) item\(urls.count > 1 ? "s" : "") to send")
                            .font(.caption)
                            .fontWeight(.bold)
                            .foregroundColor(.secondary)
                        Spacer()
                        if totalBytes > 0 {
                            Text(formatBytes(totalBytes))
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }
                    }

                    ScrollView {
                        VStack(spacing: 4) {
                            ForEach(urls, id: \.self) { url in
                                HStack(spacing: 8) {
                                    Image(systemName: fileIconName(for: url.pathExtension))
                                        .foregroundColor(.blue)
                                        .frame(width: 16)
                                    Text(url.lastPathComponent)
                                        .font(.caption)
                                        .lineLimit(1)
                                        .truncationMode(.middle)
                                    Spacer()
                                    if let size = fileSize(for: url) {
                                        Text(formatBytes(size))
                                            .font(.caption2)
                                            .foregroundColor(.secondary)
                                    }
                                }
                                .padding(.horizontal, 8)
                                .padding(.vertical, 4)
                                .background(Color(nsColor: .controlBackgroundColor).opacity(0.6))
                                .cornerRadius(6)
                            }
                        }
                    }
                    .frame(maxHeight: 120)
                }
                .padding(10)
                .background(Color(nsColor: .windowBackgroundColor).opacity(0.6))
                .cornerRadius(10)
            } else if let text = text, !text.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Text / URL to Send:")
                        .font(.caption2)
                        .fontWeight(.bold)
                        .foregroundColor(.blue)

                    Text(text)
                        .font(.caption)
                        .lineLimit(4)
                        .foregroundColor(.primary)
                }
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.blue.opacity(0.08))
                .cornerRadius(8)
            }

            // 4. Progress or Status Banner
            if let active = appState.activeTransfer, isSending {
                VStack(spacing: 5) {
                    HStack {
                        Text("Sending: \(active.name)")
                            .font(.caption)
                            .fontWeight(.bold)
                            .lineLimit(1)
                        Spacer()
                        Text("\(Int(active.progress * 100))%")
                            .font(.caption)
                            .fontWeight(.bold)
                            .foregroundColor(.blue)
                    }

                    ProgressView(value: Double(active.progress))
                        .progressViewStyle(.linear)

                    HStack {
                        if active.speedBytesPerSec > 1000 {
                            Text("\(formatBytes(Int64(active.speedBytesPerSec)))/s")
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }
                        if active.etaSeconds > 0 {
                            Text("• ETA: \(formatETA(active.etaSeconds))")
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }
                        Spacer()
                        Button("Cancel") {
                            cancelTransfer()
                        }
                        .buttonStyle(.plain)
                        .font(.caption2)
                        .foregroundColor(.red)
                    }
                }
                .padding(8)
                .background(Color.blue.opacity(0.06))
                .cornerRadius(8)
            } else if isComplete {
                HStack(spacing: 6) {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(.green)
                    Text("Sent Successfully to \(pairedDevice?.deviceName ?? "Device")! 🎉")
                        .font(.caption)
                        .fontWeight(.bold)
                        .foregroundColor(.green)
                }
                .padding(8)
                .frame(maxWidth: .infinity)
                .background(Color.green.opacity(0.12))
                .cornerRadius(8)
            } else if let err = errorMessage {
                HStack(spacing: 6) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundColor(.red)
                    Text(err)
                        .font(.caption2)
                        .foregroundColor(.red)
                        .lineLimit(2)
                }
                .padding(8)
                .frame(maxWidth: .infinity)
                .background(Color.red.opacity(0.1))
                .cornerRadius(8)
            }

            Spacer()

            // 5. Actions Footer
            HStack(spacing: 12) {
                Button("Cancel") {
                    onDismiss()
                }
                .buttonStyle(.bordered)
                .controlSize(.regular)

                Spacer()

                if isConnected {
                    Button(action: sendContent) {
                        HStack(spacing: 5) {
                            Text("Send to \(pairedDevice?.deviceName ?? "Phone")")
                            Image(systemName: "paperplane.fill")
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.regular)
                    .tint(.blue)
                    .disabled(isSending || isComplete || (urls.isEmpty && (text == nil || text!.isEmpty)))
                } else if pairedDevice != nil {
                    Button("Reconnect to Send") {
                        reconnect()
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.regular)
                    .tint(.blue)
                }
            }
        }
        .padding(18)
        .frame(width: 440, height: 400)
    }

    private func sendContent() {
        guard isConnected else {
            errorMessage = "Device is not connected. Reconnect first."
            return
        }

        isSending = true
        errorMessage = nil
        isComplete = false

        // 1. If text/URL
        if let text = text, !text.isEmpty, urls.isEmpty {
            let seq = Int(Date().timeIntervalSince1970) % 100000
            appState.connectionManager.send(message: .clipboardPush(sequenceNumber: seq, content: text))
            isSending = false
            isComplete = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) {
                onDismiss()
            }
            return
        }

        // 2. If files
        for url in urls {
            appState.sendFile(url: url)
        }

        // Check completion polling
        Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { timer in
            if appState.activeTransfer == nil && isSending {
                timer.invalidate()
                isSending = false
                isComplete = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) {
                    onDismiss()
                }
            }
        }
    }

    private func cancelTransfer() {
        appState.transferManager.cancelAll()
        isSending = false
        errorMessage = "Transfer cancelled"
    }

    private func reconnect() {
        if let paired = pairedDevice,
           let matched = appState.discoveredDevices.first(where: { $0.id == paired.deviceId || $0.name == paired.deviceName }) {
            appState.connect(to: matched)
        } else {
            appState.startDiscovery(showActiveIndicator: true)
        }
    }

    private func fileSize(for url: URL) -> Int64? {
        if let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
           let size = attrs[.size] as? Int64 {
            return size
        }
        return nil
    }

    private func formatBytes(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useAll]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }

    private func formatETA(_ seconds: Int) -> String {
        if seconds < 60 {
            return "\(seconds)s"
        }
        return "\(seconds / 60)m \(seconds % 60)s"
    }

    private func fileIconName(for ext: String) -> String {
        switch ext.lowercased() {
        case "jpg", "jpeg", "png", "webp", "gif", "heic":
            return "photo"
        case "mp4", "mov", "mkv", "avi":
            return "film"
        case "pdf":
            return "doc.text.fill"
        case "doc", "docx", "txt", "rtf":
            return "doc.plaintext"
        case "zip", "rar", "tar", "gz":
            return "archivebox"
        case "mp3", "m4a", "wav", "flac":
            return "music.note"
        default:
            return "doc"
        }
    }
}

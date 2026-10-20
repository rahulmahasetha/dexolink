import SwiftUI

struct DevicesView: View {
    @ObservedObject var appState: iOSAppState
    @State private var showingCameraPreview: Bool = false
    @State private var previewRotation: Double = 0.0

    var body: some View {
        NavigationStack {
            List {
                // Section 1: Security & Pairing Modes
                Section(header: Text("Pairing Security")) {
                    Toggle(isOn: $appState.isMultiplePairedDevicesEnabled) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Multiple Paired Devices")
                                .font(.subheadline)
                                .fontWeight(.semibold)
                            Text(appState.isMultiplePairedDevicesEnabled ? "ON: Multiple trusted devices can connect simultaneously" : "OFF: Strict single-device pairing mode (Default)")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                }

                // Section 2: Paired Trusted Devices
                Section(header: Text("Paired Trusted Devices (\(appState.pairedDevices.count))")) {
                    if appState.pairedDevices.isEmpty {
                        Text("No devices paired yet. Pair with a device below to start transferring.")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    } else {
                        ForEach(appState.pairedDevices) { dev in
                            HStack {
                                Image(systemName: "laptopcomputer.and.iphone")
                                    .foregroundColor(.blue)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(dev.name)
                                        .font(.subheadline)
                                        .fontWeight(.semibold)
                                    Text("Paired: \(dev.pairedDate, style: .date)")
                                        .font(.caption2)
                                        .foregroundColor(.secondary)
                                }
                                Spacer()
                                Button(action: {
                                    appState.unpairDevice(deviceId: dev.id)
                                }) {
                                    Text("Unpair")
                                        .font(.caption)
                                        .fontWeight(.bold)
                                        .foregroundColor(.red)
                                        .padding(.horizontal, 10)
                                        .padding(.vertical, 4)
                                        .background(Color.red.opacity(0.12))
                                        .cornerRadius(8)
                                }
                                .buttonStyle(.borderless)
                            }
                        }
                    }
                }

                // Section 3: Discovered Nearby Devices (mDNS / Bonjour)
                Section(header: Text("Discovered Nearby Devices")) {
                    if appState.discoveredDevices.isEmpty {
                        HStack {
                            ProgressView()
                                .padding(.trailing, 6)
                            Text("Scanning local WiFi network...")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    } else {
                        ForEach(appState.discoveredDevices) { dev in
                            HStack {
                                Image(systemName: "antenna.radiowaves.left.and.right")
                                    .foregroundColor(.cyan)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(dev.name)
                                        .font(.subheadline)
                                    Text("Local Port: \(dev.port)")
                                        .font(.caption2)
                                        .foregroundColor(.secondary)
                                }
                                Spacer()
                                if dev.isPaired {
                                    Text("Paired")
                                        .font(.caption2)
                                        .fontWeight(.bold)
                                        .foregroundColor(.green)
                                } else {
                                    Button(action: {
                                        appState.pairDevice(dev)
                                    }) {
                                        Text("Pair")
                                            .font(.caption)
                                            .fontWeight(.bold)
                                            .foregroundColor(.white)
                                            .padding(.horizontal, 12)
                                            .padding(.vertical, 4)
                                            .background(Color.blue)
                                            .cornerRadius(8)
                                    }
                                    .buttonStyle(.borderless)
                                }
                            }
                        }
                    }
                }

                // Section 4: Media Streaming & Remote Control (v4.0.1)
                Section(header: Text("Media Streaming & Remote Control")) {
                    Toggle(isOn: Binding(
                        get: { appState.isCameraSharingEnabled },
                        set: { appState.setCameraSharingEnabled($0) }
                    )) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Camera Sharing")
                                .font(.subheadline)
                                .fontWeight(.semibold)
                            Text(appState.isCameraSharingEnabled ? "ON: Streaming camera to paired devices" : "OFF: Stream iPhone camera to Mac/Windows (Default)")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }

                    if appState.isCameraSharingEnabled {
                        HStack {
                            Text("Active Camera Sensor")
                                .font(.subheadline)
                            Spacer()
                            Button(action: {
                                appState.toggleCameraLens()
                            }) {
                                HStack(spacing: 4) {
                                    Image(systemName: "camera.rotate")
                                    Text(appState.isFrontCamera ? "Front Camera" : "Rear Camera")
                                }
                                .font(.caption)
                                .fontWeight(.semibold)
                                .foregroundColor(.blue)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 4)
                                .background(Color.blue.opacity(0.12))
                                .cornerRadius(8)
                            }
                            .buttonStyle(.borderless)
                        }

                        Button(action: {
                            showingCameraPreview = true
                        }) {
                            HStack {
                                Image(systemName: "viewfinder")
                                    .foregroundColor(.purple)
                                Text("Preview & Route Feed")
                                    .fontWeight(.medium)
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .font(.caption2)
                                    .foregroundColor(.secondary)
                            }
                        }
                    }

                    Toggle(isOn: $appState.isScreenSharingEnabled) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Screen Sharing")
                                .font(.subheadline)
                                .fontWeight(.semibold)
                            Text(appState.isScreenSharingEnabled ? "ON: Broadcast screen mirroring active" : "OFF: Broadcast screen to Mac/Windows (Default)")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }

                    Toggle(isOn: $appState.isDesktopModeEnabled) {
                        VStack(alignment: .leading, spacing: 2) {
                            HStack {
                                Text("Desktop Mode")
                                    .font(.subheadline)
                                    .fontWeight(.semibold)
                                Text("BETA")
                                    .font(.system(size: 9, weight: .bold))
                                    .padding(.horizontal, 4)
                                    .padding(.vertical, 1)
                                    .background(Color.green.opacity(0.15))
                                    .foregroundColor(.green)
                                    .cornerRadius(4)
                            }
                            Text(appState.isDesktopModeEnabled ? "ON: Remote desktop viewing active" : "OFF: Remote desktop viewing and control (Default)")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                }

                // Section 5: App Information
                Section(header: Text("About DexoLink")) {
                    HStack {
                        Text("Version")
                        Spacer()
                        Text("4.0.1 (Universal)")
                            .foregroundColor(.secondary)
                    }
                    HStack {
                        Text("Protocol")
                        Spacer()
                        Text("DexoLink TLS Framing (Port 52840)")
                            .foregroundColor(.secondary)
                    }
                }
            }
            .navigationTitle("Devices & Settings")
            .fullScreenCover(isPresented: $showingCameraPreview) {
                ZStack {
                    Color.black.edgesIgnoringSafeArea(.all)

                    // Simulated live camera preview viewfinder
                    VStack {
                        Spacer()
                        ZStack {
                            RoundedRectangle(cornerRadius: 16)
                                .stroke(Color.blue.opacity(0.6), lineWidth: 2)
                                .frame(maxWidth: .infinity, maxHeight: .infinity)
                                .padding(24)

                            VStack(spacing: 8) {
                                Image(systemName: "camera.fill")
                                    .font(.system(size: 48))
                                    .foregroundColor(.white.opacity(0.8))
                                Text("DexoLink Camera Stream")
                                    .font(.headline)
                                    .foregroundColor(.white)
                                Text("Active Sensor: \(appState.isFrontCamera ? "Front Camera" : "Rear Camera")")
                                    .font(.subheadline)
                                    .foregroundColor(.secondary)
                            }
                        }
                        .rotationEffect(.degrees(previewRotation))
                        Spacer()
                    }

                    // Top Bar Controls
                    VStack {
                        HStack(spacing: 8) {
                            HStack(spacing: 6) {
                                Circle()
                                    .fill(Color.red)
                                    .frame(width: 8, height: 8)
                                Text("LIVE")
                                    .font(.system(size: 10, weight: .bold))
                                    .foregroundColor(.white)
                            }
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(Color.black.opacity(0.7))
                            .cornerRadius(6)

                            Spacer()

                            Button(action: {
                                appState.toggleCameraLens()
                            }) {
                                HStack(spacing: 4) {
                                    Image(systemName: "camera.rotate")
                                    Text("Flip Camera")
                                }
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(.white)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 4)
                                .background(Color.blue.opacity(0.85))
                                .cornerRadius(6)
                            }

                            Button(action: {
                                showingCameraPreview = false
                            }) {
                                Image(systemName: "xmark.circle.fill")
                                    .font(.system(size: 22))
                                    .foregroundColor(.white.opacity(0.85))
                            }
                        }
                        .padding(16)

                        Spacer()

                        // Bottom Directional Route Dock (Up, Right, Down, Left)
                        HStack(spacing: 6) {
                            ForEach([(0.0, "Up"), (90.0, "Right"), (180.0, "Down"), (270.0, "Left")], id: \.0) { deg, label in
                                let isSelected = Int(previewRotation) == Int(deg)
                                Button(action: {
                                    previewRotation = deg
                                }) {
                                    Text(label)
                                        .font(.system(size: 12, weight: isSelected ? .bold : .medium))
                                        .foregroundColor(isSelected ? .white : .white.opacity(0.7))
                                        .padding(.horizontal, 12)
                                        .padding(.vertical, 6)
                                        .background(isSelected ? Color.blue : Color.black.opacity(0.6))
                                        .cornerRadius(12)
                                }
                            }
                        }
                        .padding(6)
                        .background(Color.black.opacity(0.5))
                        .cornerRadius(16)
                        .padding(.bottom, 24)
                    }
                }
            }
        }
    }
}

import SwiftUI

struct DevicesView: View {
    @ObservedObject var appState: iOSAppState

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

                // Section 4: App Information
                Section(header: Text("About DexoLink")) {
                    HStack {
                        Text("Version")
                        Spacer()
                        Text("4.0.0 (Universal)")
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
        }
    }
}

import SwiftUI

struct ContinuityView: View {
    @ObservedObject var appState: iOSAppState
    @State private var urlInput: String = "https://"
    @State private var showSentAlert = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    // Incoming Activity Card (if received from Mac / Windows / Android)
                    if let handoff = appState.currentHandoff {
                        VStack(alignment: .leading, spacing: 10) {
                            HStack {
                                Image(systemName: "safari.fill")
                                    .foregroundColor(.blue)
                                Text("Incoming Web Page Handoff")
                                    .font(.headline)
                                Spacer()
                                Text(handoff.timestamp, style: .time)
                                    .font(.caption2)
                                    .foregroundColor(.secondary)
                            }

                            Text(handoff.title)
                                .font(.subheadline)
                                .fontWeight(.semibold)
                            Text(handoff.urlString)
                                .font(.caption)
                                .foregroundColor(.blue)
                                .lineLimit(1)

                            Button(action: {
                                appState.openHandoffInSafari(handoff)
                            }) {
                                HStack {
                                    Image(systemName: "arrow.up.right.square")
                                    Text("Open in Safari")
                                }
                                .font(.subheadline)
                                .fontWeight(.bold)
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity)
                                .padding()
                                .background(Color.blue)
                                .cornerRadius(12)
                            }
                        }
                        .padding()
                        .background(Color(.secondarySystemBackground))
                        .cornerRadius(14)
                    }

                    // Outgoing Handoff Card
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Send Web Page to Connected Devices")
                            .font(.headline)
                        Text("Instantly open a web link on your Mac, Windows PC, or Android tablet without syncing accounts.")
                            .font(.caption)
                            .foregroundColor(.secondary)

                        TextField("https://example.com", text: $urlInput)
                            .keyboardType(.URL)
                            .autocapitalization(.none)
                            .padding(12)
                            .background(Color(.tertiarySystemBackground))
                            .cornerRadius(10)

                        Button(action: {
                            appState.sendHandoffURL(urlInput)
                            showSentAlert = true
                        }) {
                            HStack {
                                Image(systemName: "paperplane.fill")
                                Text("Open on Device")
                            }
                            .font(.subheadline)
                            .fontWeight(.bold)
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(urlInput == "https://" || urlInput.isEmpty ? Color.gray : Color.blue)
                            .cornerRadius(12)
                        }
                        .disabled(urlInput == "https://" || urlInput.isEmpty)
                    }
                    .padding()
                    .background(Color(.secondarySystemBackground))
                    .cornerRadius(14)

                    // How It Works Card
                    VStack(alignment: .leading, spacing: 8) {
                        Text("How DexoLink Continuity Works")
                            .font(.headline)
                        Text("• True local-network handoff: Transfers URLs directly over local WiFi on port 52840.\n• Works with Safari on macOS, Chrome/Edge on Windows, and default browsers on Android.\n• No cloud servers, no account logins, and no internet required.")
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .lineSpacing(4)
                    }
                    .padding()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(.secondarySystemBackground))
                    .cornerRadius(14)
                }
                .padding()
            }
            .navigationTitle("Continuity & Handoff")
            .alert("Handoff Sent", isPresented: $showSentAlert) {
                Button("OK", role: .cancel) { }
            } message: {
                Text("Sent web page link to connected devices.")
            }
        }
    }
}

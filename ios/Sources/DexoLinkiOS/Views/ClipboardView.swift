import SwiftUI

struct ClipboardView: View {
    @ObservedObject var appState: iOSAppState
    @State private var textInput: String = ""
    @State private var showCopiedAlert = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    // Push Text Card
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Send Text to Connected Devices")
                            .font(.headline)

                        TextField("Type or paste text to sync...", text: $textInput, axis: .vertical)
                            .lineLimit(2...4)
                            .padding(12)
                            .background(Color(.tertiarySystemBackground))
                            .cornerRadius(10)

                        HStack {
                            Spacer()
                            Button(action: {
                                appState.pushClipboardText(textInput)
                                textInput = ""
                            }) {
                                HStack(spacing: 6) {
                                    Image(systemName: "paperplane.fill")
                                    Text("Send to Devices")
                                }
                                .font(.subheadline)
                                .fontWeight(.bold)
                                .foregroundColor(.white)
                                .padding(.horizontal, 16)
                                .padding(.vertical, 8)
                                .background(textInput.isEmpty ? Color.gray : Color.blue)
                                .cornerRadius(10)
                            }
                            .disabled(textInput.isEmpty)
                        }
                    }
                    .padding()
                    .background(Color(.secondarySystemBackground))
                    .cornerRadius(14)

                    // Options Card
                    VStack(spacing: 12) {
                        Toggle(isOn: $appState.autoClipboardSync) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Auto Clipboard Sync")
                                    .font(.subheadline)
                                    .fontWeight(.semibold)
                                Text("Syncs clipboard automatically when app opens")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        }

                        Divider()

                        Toggle(isOn: $appState.enhancedClipboard) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Enhanced Clipboard")
                                    .font(.subheadline)
                                    .fontWeight(.semibold)
                                Text("Sanitizes line endings and supports rich text")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        }
                    }
                    .padding()
                    .background(Color(.secondarySystemBackground))
                    .cornerRadius(14)

                    // Clipboard History
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Text("Clipboard History")
                                .font(.headline)
                            Spacer()
                            if !appState.clipboardHistory.isEmpty {
                                Button("Clear All") {
                                    appState.clipboardHistory.removeAll()
                                }
                                .font(.caption)
                                .foregroundColor(.red)
                            }
                        }

                        if appState.clipboardHistory.isEmpty {
                            Text("No synced clipboard items yet.")
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding()
                                .background(Color(.secondarySystemBackground))
                                .cornerRadius(14)
                        } else {
                            ForEach(Array(appState.clipboardHistory.enumerated()), id: \.offset) { index, item in
                                HStack {
                                    Text(item)
                                        .font(.subheadline)
                                        .lineLimit(2)
                                        .frame(maxWidth: .infinity, alignment: .leading)

                                    Button(action: {
                                        UIPasteboard.general.string = item
                                        showCopiedAlert = true
                                    }) {
                                        Image(systemName: "doc.on.doc")
                                            .foregroundColor(.blue)
                                    }
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
            .navigationTitle("Universal Clipboard")
            .alert("Copied to Clipboard", isPresented: $showCopiedAlert) {
                Button("OK", role: .cancel) { }
            } message: {
                Text("Content copied to your iOS pasteboard.")
            }
        }
    }
}

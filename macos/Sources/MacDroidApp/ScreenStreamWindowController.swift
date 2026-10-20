import SwiftUI
import AppKit

public class ScreenStreamViewModel: ObservableObject {
    @Published public var currentImage: NSImage? = nil
    @Published public var deviceName: String = "Remote Device"
    @Published public var resolutionText: String = "960 × 540"
    @Published public var fps: Int = 0
    @Published public var isLive: Bool = true
    @Published public var streamId: String = ""

    private var frameCount: Int = 0
    private var lastFpsTime: Date = Date()

    public var onStopRequested: (() -> Void)?

    public init(streamId: String = "", deviceName: String = "Remote Device") {
        self.streamId = streamId
        self.deviceName = deviceName
    }

    public func updateFrame(_ image: NSImage, width: Int, height: Int) {
        self.currentImage = image
        self.resolutionText = "\(width) × \(height)"
        self.isLive = true

        frameCount += 1
        let now = Date()
        let elapsed = now.timeIntervalSince(lastFpsTime)
        if elapsed >= 1.0 {
            fps = Int(Double(frameCount) / elapsed)
            frameCount = 0
            lastFpsTime = now
        }
    }
}

public struct ScreenStreamView: View {
    @ObservedObject var viewModel: ScreenStreamViewModel

    public var body: some View {
        ZStack {
            Color(nsColor: .windowBackgroundColor)
                .edgesIgnoringSafeArea(.all)

            if let image = viewModel.currentImage {
                Image(nsImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.black)
            } else {
                VStack(spacing: 14) {
                    ProgressView()
                        .scaleEffect(1.3)
                    Text("Connecting to live screen stream...")
                        .font(.headline)
                        .foregroundColor(.primary)
                    Text("Receiving real-time display mirror from \(viewModel.deviceName).")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                    Text("Ensure Screen Sharing is enabled and permission is granted on the sending device.")
                        .font(.caption)
                        .foregroundColor(.secondary.opacity(0.8))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 30)
                }
                .padding(40)
            }

            // Top Status Bar Overlay
            VStack {
                HStack(spacing: 8) {
                    HStack(spacing: 6) {
                        Circle()
                            .fill(viewModel.isLive ? Color.green : Color.gray)
                            .frame(width: 8, height: 8)
                        Text(viewModel.isLive ? "LIVE MIRROR" : "PAUSED")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(.white)
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color.black.opacity(0.7))
                    .cornerRadius(6)

                    Text(viewModel.deviceName)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Color.black.opacity(0.7))
                        .cornerRadius(6)

                    Spacer()

                    HStack(spacing: 8) {
                        Text(viewModel.resolutionText)
                            .font(.system(size: 11, weight: .medium, design: .monospaced))
                            .foregroundColor(.white.opacity(0.9))

                        Text("\(viewModel.fps) FPS")
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .foregroundColor(Color.green)
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color.black.opacity(0.7))
                    .cornerRadius(6)

                    Button(action: {
                        viewModel.onStopRequested?()
                    }) {
                        HStack(spacing: 4) {
                            Image(systemName: "stop.circle.fill")
                            Text("Stop Mirroring")
                        }
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Color.red.opacity(0.85))
                        .cornerRadius(6)
                    }
                    .buttonStyle(.plain)
                }
                .padding(12)

                Spacer()
            }
        }
        .frame(minWidth: 540, minHeight: 380)
    }
}

public class ScreenStreamWindowController: NSWindowController, NSWindowDelegate {
    public static let shared = ScreenStreamWindowController()

    public let viewModel = ScreenStreamViewModel()
    public var onClose: (() -> Void)?

    private init() {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 960, height: 580),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.title = "DexoLink Screen Sharing"
        window.center()
        window.isReleasedWhenClosed = false
        window.titlebarAppearsTransparent = true
        window.backgroundColor = .black

        super.init(window: window)
        window.delegate = self
        window.contentView = NSHostingView(rootView: ScreenStreamView(viewModel: self.viewModel))

        viewModel.onStopRequested = { [weak self] in
            self?.close()
        }
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    public func showStream(streamId: String, deviceName: String) {
        viewModel.streamId = streamId
        viewModel.deviceName = deviceName
        viewModel.currentImage = nil
        viewModel.fps = 0
        window?.title = "Screen Mirror - \(deviceName)"
        window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    public func updateFrame(_ image: NSImage, width: Int, height: Int) {
        viewModel.updateFrame(image, width: width, height: height)
    }

    public func windowWillClose(_ notification: Notification) {
        onClose?()
    }
}

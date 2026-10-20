import SwiftUI
import AppKit

public class CameraStreamViewModel: ObservableObject {
    @Published public var currentImage: NSImage? = nil
    @Published public var deviceName: String = "Android Device"
    @Published public var resolutionText: String = "640 × 480"
    @Published public var fps: Int = 0
    @Published public var isLive: Bool = true
    @Published public var streamId: String = ""

    private var frameCount: Int = 0
    private var lastFpsTime: Date = Date()

    public var onStopRequested: (() -> Void)?
    public var onFlipCameraRequested: (() -> Void)?

    public var isMobilePeer: Bool {
        let lower = deviceName.lowercased()
        if lower.contains("mac") || lower.contains("windows") || lower.contains("pc") || lower.contains("laptop") || lower.contains("desktop") {
            return false
        }
        return true
    }

    public init(streamId: String = "", deviceName: String = "Android Device") {
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

public struct CameraStreamView: View {
    @ObservedObject var viewModel: CameraStreamViewModel

    public var body: some View {
        ZStack {
            Color(nsColor: .windowBackgroundColor)
                .edgesIgnoringSafeArea(.all)

            if let image = viewModel.currentImage {
                Image(nsImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                VStack(spacing: 12) {
                    ProgressView()
                        .scaleEffect(1.2)
                    Text("Connecting to camera feed...")
                        .font(.headline)
                        .foregroundColor(.secondary)
                    Text("Make sure Camera Sharing is allowed and turned ON on \(viewModel.deviceName).")
                        .font(.caption)
                        .foregroundColor(.secondary.opacity(0.8))
                }
                .padding(40)
            }

            // Top Status Bar Overlay
            VStack {
                HStack(spacing: 8) {
                    HStack(spacing: 6) {
                        Circle()
                            .fill(viewModel.isLive ? Color.red : Color.gray)
                            .frame(width: 8, height: 8)
                        Text(viewModel.isLive ? "LIVE" : "PAUSED")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(.white)
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color.black.opacity(0.65))
                    .cornerRadius(6)

                    Text(viewModel.deviceName)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Color.black.opacity(0.65))
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
                    .background(Color.black.opacity(0.65))
                    .cornerRadius(6)

                    // Flip camera button: only shown for mobile devices (phones/tablets with front and rear cameras)
                    if viewModel.isMobilePeer {
                        Button(action: {
                            viewModel.onFlipCameraRequested?()
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
                        .buttonStyle(.plain)
                    }

                    Button(action: {
                        viewModel.onStopRequested?()
                    }) {
                        HStack(spacing: 4) {
                            Image(systemName: "stop.circle.fill")
                            Text("Stop")
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
        .frame(minWidth: 480, minHeight: 360)
    }
}

public class CameraStreamWindowController: NSWindowController, NSWindowDelegate {
    public static let shared = CameraStreamWindowController()

    public let viewModel = CameraStreamViewModel()
    public var onClose: (() -> Void)?

    private init() {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 680, height: 510),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.title = "DexoLink Camera Sharing"
        window.center()
        window.isReleasedWhenClosed = false
        window.titlebarAppearsTransparent = true
        window.backgroundColor = .black

        super.init(window: window)
        window.delegate = self
        window.contentView = NSHostingView(rootView: CameraStreamView(viewModel: self.viewModel))

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
        window?.title = "Camera - \(deviceName)"
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

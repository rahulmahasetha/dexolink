import SwiftUI
import AppKit

public class ScreenStreamViewModel: ObservableObject {
    @Published public var currentImage: NSImage? = nil
    @Published public var deviceName: String = "Remote Device"
    @Published public var resolutionText: String = "960 × 540"
    @Published public var fps: Int = 0
    @Published public var isLive: Bool = true
    @Published public var streamId: String = ""
    @Published public var rotationAngle: Double = 0.0

    private var frameCount: Int = 0
    private var lastFpsTime: Date = Date()

    public var onStopRequested: (() -> Void)?
    public var onSendInput: ((String, Double, Double, Int, String?) -> Void)?

    public func routeNextOrientation() {
        rotationAngle = (rotationAngle + 90.0).truncatingRemainder(dividingBy: 360.0)
    }

    public func setRouteDirection(_ degrees: Double) {
        rotationAngle = degrees
    }

    public var orientationLabel: String {
        switch Int(rotationAngle) {
        case 90: return "Right (90 deg)"
        case 180: return "Down (180 deg)"
        case 270: return "Left (270 deg)"
        default: return "Up (0 deg)"
        }
    }

    public init(streamId: String = "", deviceName: String = "Remote Device") {
        self.streamId = streamId
        self.deviceName = deviceName
    }

    public func updateFrame(_ image: NSImage, width: Int, height: Int) {
        self.currentImage = image
        self.resolutionText = "\(width) x \(height)"
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
    @State private var showWritePopover: Bool = false
    @State private var writeText: String = ""

    public var body: some View {
        ZStack {
            Color(nsColor: .windowBackgroundColor)
                .edgesIgnoringSafeArea(.all)

            if let image = viewModel.currentImage {
                GeometryReader { geo in
                    ZStack {
                        Image(nsImage: image)
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .rotationEffect(.degrees(viewModel.rotationAngle))
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .background(Color.black)

                        // Interactive Click Gesture Layer
                        Color.clear
                            .contentShape(Rectangle())
                            .gesture(
                                DragGesture(minimumDistance: 0)
                                    .onEnded { value in
                                        let w = max(geo.size.width, 1.0)
                                        let h = max(geo.size.height, 1.0)
                                        let normX = min(max(Double(value.location.x / w), 0.0), 1.0)
                                        let normY = min(max(Double(value.location.y / h), 0.0), 1.0)
                                        viewModel.onSendInput?("click", normX, normY, 0, nil)
                                    }
                            )
                    }
                }
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

                    // Write / Type to Remote Screen
                    Button(action: {
                        showWritePopover.toggle()
                    }) {
                        HStack(spacing: 4) {
                            Image(systemName: "keyboard")
                            Text("Write")
                        }
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Color.indigo.opacity(0.85))
                        .cornerRadius(6)
                    }
                    .buttonStyle(.plain)
                    .popover(isPresented: $showWritePopover) {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Write to Remote Screen")
                                .font(.headline)
                            Text("Type text to send directly to \(viewModel.deviceName).")
                                .font(.caption)
                                .foregroundColor(.secondary)
                            TextField("Enter text or command...", text: $writeText)
                                .textFieldStyle(.roundedBorder)
                                .frame(width: 260)
                            HStack {
                                Button("Enter") {
                                    viewModel.onSendInput?("key_press", 0.0, 0.0, 0, "return")
                                }
                                Button("Backspace") {
                                    viewModel.onSendInput?("key_press", 0.0, 0.0, 0, "backspace")
                                }
                                Spacer()
                                Button("Send") {
                                    if !writeText.isEmpty {
                                        viewModel.onSendInput?("write", 0.0, 0.0, 0, writeText)
                                        writeText = ""
                                    }
                                }
                                .buttonStyle(.borderedProminent)
                            }
                        }
                        .padding(14)
                    }

                    // Route Orientation Button
                    Button(action: {
                        viewModel.routeNextOrientation()
                    }) {
                        HStack(spacing: 4) {
                            Image(systemName: "arrow.triangle.2.circlepath")
                            Text("Route: \(viewModel.orientationLabel)")
                        }
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Color.blue.opacity(0.85))
                        .cornerRadius(6)
                    }
                    .buttonStyle(.plain)

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

            // Bottom Directional Route Dock (Up, Right, Down, Left)
            VStack {
                Spacer()
                HStack(spacing: 6) {
                    ForEach([(0.0, "Up"), (90.0, "Right"), (180.0, "Down"), (270.0, "Left")], id: \.0) { deg, label in
                        let isSelected = Int(viewModel.rotationAngle) == Int(deg)
                        Button(action: {
                            viewModel.setRouteDirection(deg)
                        }) {
                            Text(label)
                                .font(.system(size: 11, weight: isSelected ? .bold : .medium))
                                .foregroundColor(isSelected ? .white : .white.opacity(0.7))
                                .padding(.horizontal, 10)
                                .padding(.vertical, 4)
                                .background(isSelected ? Color.blue : Color.black.opacity(0.6))
                                .cornerRadius(12)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(6)
                .background(Color.black.opacity(0.5))
                .cornerRadius(16)
                .padding(.bottom, 16)
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

import SwiftUI
import CoreImage.CIFilterBuiltins
#if canImport(UIKit)
import UIKit
#endif

public struct QRConnectView: View {
    @ObservedObject var appState: iOSAppState
    var onBack: () -> Void = {}

    @State private var qrPayload: String = ""
    @State private var qrImage: UIImage? = nil
    @State private var scanLineOffset: CGFloat = 0.1
    @State private var isScanningActive: Bool = false
    @State private var showingScannerAlert: Bool = false
    @State private var showingShareSheet: Bool = false

    private let context = CIContext()
    private let filter = CIFilter.qrCodeGenerator()

    public init(appState: iOSAppState, onBack: @escaping () -> Void = {}) {
        self.appState = appState
        self.onBack = onBack
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // 1. Top Header (Back button + "QR connect")
                HStack(spacing: 16) {
                    Button(action: onBack) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 14)
                                .fill(Color.white)
                                .frame(width: 44, height: 44)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 14)
                                        .stroke(Color(hex: "E2E8F0"), lineWidth: 1)
                                )

                            Image(systemName: "chevron.left")
                                .font(.system(size: 18, weight: .semibold))
                                .foregroundColor(Color(hex: "0F172A"))
                        }
                    }
                    .buttonStyle(.plain)

                    Text("QR connect")
                        .font(.system(size: 22, weight: .bold))
                        .foregroundColor(Color(hex: "0F172A"))

                    Spacer()
                }
                .padding(.top, 8)

                // 2. Card: My QR code
                VStack(spacing: 16) {
                    HStack(alignment: .top) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("My QR code")
                                .font(.system(size: 18, weight: .bold))
                                .foregroundColor(Color(hex: "0F172A"))

                            Text("Let the other device scan this")
                                .font(.system(size: 13))
                                .foregroundColor(Color(hex: "64748B"))
                        }

                        Spacer()

                        HStack(spacing: 8) {
                            // Share Button
                            Button(action: {
                                shareQRCode()
                            }) {
                                ZStack {
                                    RoundedRectangle(cornerRadius: 12)
                                        .fill(Color.white)
                                        .frame(width: 40, height: 40)
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 12)
                                                .stroke(Color(hex: "E2E8F0"), lineWidth: 1)
                                        )

                                    Image(systemName: "square.and.arrow.up")
                                        .font(.system(size: 16, weight: .semibold))
                                        .foregroundColor(Color(hex: "2563EB"))
                                }
                            }
                            .buttonStyle(.plain)

                            // Refresh Button
                            Button(action: {
                                generateNewQR()
                            }) {
                                ZStack {
                                    RoundedRectangle(cornerRadius: 12)
                                        .fill(Color.white)
                                        .frame(width: 40, height: 40)
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 12)
                                                .stroke(Color(hex: "E2E8F0"), lineWidth: 1)
                                        )

                                    Image(systemName: "arrow.clockwise")
                                        .font(.system(size: 16, weight: .semibold))
                                        .foregroundColor(Color(hex: "2563EB"))
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }

                    // QR Container
                    ZStack {
                        RoundedRectangle(cornerRadius: 18)
                            .fill(Color.white)
                            .frame(width: 230, height: 230)
                            .overlay(
                                RoundedRectangle(cornerRadius: 18)
                                    .stroke(Color(hex: "E2E8F0"), lineWidth: 1)
                            )

                        if let qr = qrImage {
                            Image(uiImage: qr)
                                .interpolation(.none)
                                .resizable()
                                .scaledToFit()
                                .frame(width: 200, height: 200)
                        } else {
                            ProgressView()
                        }
                    }
                    .padding(.vertical, 4)

                    // Device Name below QR
                    Text(appState.localDeviceName)
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(Color(hex: "0F172A"))
                }
                .padding(20)
                .background(Color.white)
                .cornerRadius(24)
                .overlay(
                    RoundedRectangle(cornerRadius: 24)
                        .stroke(Color(hex: "E2E8F0"), lineWidth: 1)
                )

                // 3. Section: Scan QR code
                VStack(alignment: .leading, spacing: 12) {
                    Text("Scan QR code")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(Color(hex: "334155"))

                    // Dark Viewfinder Container
                    ZStack {
                        RoundedRectangle(cornerRadius: 24)
                            .fill(Color(hex: "0C1322"))
                            .frame(maxWidth: .infinity)
                            .frame(height: 220)

                        // Reticle corners overlay
                        ReticleView()
                            .frame(width: 150, height: 150)

                        // Animated scanning beam
                        Rectangle()
                            .fill(Color(hex: "3B82F6"))
                            .frame(width: 130, height: 2.5)
                            .cornerRadius(1)
                            .offset(y: (scanLineOffset - 0.5) * 140)

                        // Subtitle text inside viewfinder
                        VStack {
                            Spacer()
                            Text("Point the camera at the other device's QR")
                                .font(.system(size: 12, weight: .medium))
                                .foregroundColor(Color(hex: "CBD5E1"))
                                .padding(.bottom, 16)
                        }
                    }

                    // Full-width blue Scan QR Button
                    Button(action: {
                        showingScannerAlert = true
                    }) {
                        HStack(spacing: 8) {
                            Image(systemName: "qrcode.viewfinder")
                                .font(.system(size: 20, weight: .bold))
                                .foregroundColor(.white)

                            Text("Scan QR")
                                .font(.system(size: 16, weight: .bold))
                                .foregroundColor(.white)
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 52)
                        .background(Color(hex: "2563EB"))
                        .cornerRadius(16)
                    }
                    .buttonStyle(.plain)
                }

                Spacer().frame(height: 40)
            }
            .padding(.horizontal, 20)
        }
        .background(Color(hex: "F3F6FC"))
        .onAppear {
            generateNewQR()
            startBeamAnimation()
        }
        .alert("Camera Scanner", isPresented: $showingScannerAlert) {
            Button("OK") {}
        } message: {
            Text("Camera scanner is searching for DexoLink QR code. Align with target screen to pair instantly.")
        }
    }

    private func generateNewQR() {
        let devName = appState.localDeviceName
        let payload = "dexolink://connect?host=192.168.1.50&port=52840&name=\(devName.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "iPhone")&t=\(Date().timeIntervalSince1970)"
        self.qrPayload = payload
        self.qrImage = createQRCodeImage(from: payload)
    }

    private func createQRCodeImage(from string: String) -> UIImage? {
        let data = Data(string.utf8)
        filter.setValue(data, forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")

        if let outputImage = filter.outputImage {
            let transform = CGAffineTransform(scaleX: 10, y: 10)
            let scaledImage = outputImage.transformed(by: transform)
            if let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) {
                return UIImage(cgImage: cgImage)
            }
        }
        return nil
    }

    private func startBeamAnimation() {
        withAnimation(.easeInOut(duration: 1.8).repeatForever(autoreverses: true)) {
            scanLineOffset = 0.9
        }
    }

    private func shareQRCode() {
        #if canImport(UIKit)
        let av = UIActivityViewController(activityItems: [qrPayload], applicationActivities: nil)
        if let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
           let rootVC = windowScene.windows.first?.rootViewController {
            rootVC.present(av, animated: true)
        }
        #endif
    }
}

// Corner Reticle Shape View
struct ReticleView: View {
    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let h = geo.size.height
            let length: CGFloat = 28
            let strokeWidth: CGFloat = 3

            Path { path in
                // Top Left
                path.move(to: CGPoint(x: 0, y: length))
                path.addLine(to: CGPoint(x: 0, y: 0))
                path.addLine(to: CGPoint(x: length, y: 0))

                // Top Right
                path.move(to: CGPoint(x: w - length, y: 0))
                path.addLine(to: CGPoint(x: w, y: 0))
                path.addLine(to: CGPoint(x: w, y: length))

                // Bottom Left
                path.move(to: CGPoint(x: 0, y: h - length))
                path.addLine(to: CGPoint(x: 0, y: h))
                path.addLine(to: CGPoint(x: length, y: h))

                // Bottom Right
                path.move(to: CGPoint(x: w - length, y: h))
                path.addLine(to: CGPoint(x: w, y: h))
                path.addLine(to: CGPoint(x: w, y: h - length))
            }
            .stroke(Color.white, style: StrokeStyle(lineWidth: strokeWidth, lineCap: .round, lineJoin: .round))
        }
    }
}

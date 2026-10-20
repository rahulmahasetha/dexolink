# DexoLink for iPhone (iOS App v4.0.1)

A modern, fast, and easy-to-use native SwiftUI app for iPhone running iOS 16.0+.

## Key Features
1. **Easy File & Photo Transfer:**
   - Tap **Send Photos & Videos** to pick photos and videos directly from your Camera Roll using Apple's native `PhotosPicker`.
   - Tap **Send Files & Documents** to send PDFs, ZIPs, or downloads from the iOS Files app or iCloud Drive.
   - 64KB chunked transmission with SHA-256 verification.
2. **Universal Clipboard:**
   - Automatic sync with connected Mac, Windows PC, or Android device.
   - Quick text push and rich clipboard history with 1-tap copy.
   - Enhanced Clipboard support for sanitized line endings and rich HTML formatting.
3. **Continuity & Handoff:**
   - Seamlessly hand off web pages between your iPhone, Mac, Windows, and Android devices.
   - 1-tap **Open in Safari** for incoming links.
4. **Media Streaming & Remote Control (v4.0.1):**
   - **Camera Sharing:** Stream your iPhone's camera to your Mac or PC (**OFF by default** in Settings; respects `NSCameraUsageDescription`).
   - **Screen Sharing:** Mirror iPhone display to another device (**OFF by default** in Settings; uses ReplayKit broadcast framework).
   - **Desktop Mode (beta):** View and interact with remote Mac and Windows desktops (**OFF by default** in Settings).
5. **Multiple Paired Devices:**
   - **OFF by default** for strict single-device security.
   - When enabled, allows pairing multiple computers and phones simultaneously with individual unpairing controls.
6. **Zero Internet Requirement:**
   - Works 100% offline over local Wi-Fi, Personal Hotspot, or local network.

## How to Run & Build on iPhone
1. Open Xcode on your Mac.
2. Choose **File > Open** and select the `ios` directory (or add the folder to your Xcode workspace).
3. Select your iPhone or an iOS Simulator as the run target.
4. Press **⌘ + R** (Run) to build and launch DexoLink on your iPhone.


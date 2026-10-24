# DexoLink for iPhone (iOS Guide & Architecture - v0.2)

This guide documents the iOS architecture, shared code modules, and implementation patterns for DexoLink on iPhone (iOS 16.0+).

## 1. Architecture Overview
DexoLink shares its core networking, cryptography, protocol serialization, and data layer directly between macOS and iOS via Swift Package Manager (`Package.swift`).

```
                    ┌────────────────────────────┐
                    │     DexoLink iOS App       │
                    │   (SwiftUI / UIKit Host)   │
                    └──────────────┬─────────────┘
                                   │
       ┌───────────────────────────┼───────────────────────────┐
       ▼                           ▼                           ▼
┌──────────────┐          ┌───────────────────┐       ┌─────────────────┐
│  Core Module │          │ Networking Module │       │  Crypto Module  │
│ - Clipboard  │          │ - NWListener      │       │ - Key exchange  │
│ - TrustStore │          │ - NWConnection    │       │ - SHA-256 hash  │
│ - FileStream │          │ - Framing Codec   │       │ - Trust store   │
└──────────────┘          └───────────────────┘       └─────────────────┘
```

## 2. Shared Modules (`macos/Package.swift`)
The Swift Package defines:
```swift
platforms: [
    .macOS(.v13),
    .iOS(.v16)
]
```
- **Networking:** Pure Swift using Apple's `Network.framework` (`NWListener`, `NWConnection`). Zero external dependencies. Works identically on iOS and macOS.
- **Crypto:** Uses `CryptoKit` for SHA-256 integrity and key generation.
- **Core:**
  - `ClipboardManager.swift`: Conditionally compiled using `#if os(iOS)` with `UIKit` (`UIPasteboard.general`) and `#if os(macOS)` with `AppKit` (`NSPasteboard.general`).
  - `TrustStore.swift`: Manages trusted device identities with `isMultiplePairedDevicesEnabled` persisted in `UserDefaults`.

## 3. Required iOS Entitlements & Info.plist Keys
To operate on a local Wi-Fi / hotspot network without requiring an internet connection:
1. **Local Network Privacy (`NSLocalNetworkUsageDescription`):**
   ```xml
   <key>NSLocalNetworkUsageDescription</key>
   <string>DexoLink needs access to your local network to discover and transfer files to your paired devices.</string>
   ```
2. **Bonjour Services (`NSBonjourServices`):**
   ```xml
   <key>NSBonjourServices</key>
   <array>
       <string>_dexolink._tcp</string>
       <string>_macdroid._tcp</string>
   </array>
   ```
3. **Background Modes (`UIBackgroundModes`):**
   ```xml
   <key>UIBackgroundModes</key>
   <array>
       <string>fetch</string>
       <string>processing</string>
   </array>
   ```

## 4. Clipboard Synchronization on iOS
- **iOS 16+ Pasteboard Access Notification:** iOS displays a system banner when reading the pasteboard across application boundaries. DexoLink minimizes pasteboard reads by checking `pasteboard.changeCount` before reading content, eliminating unnecessary reads.
- **Event-Driven Observation:** Listens to `UIApplication.didBecomeActiveNotification` to sync the clipboard immediately when the user switches into DexoLink.
- **Loop Prevention:** Uses SHA-256 hashes (`lastSentHash` and `lastReceivedHash`) to ensure copied text is not echoed back in an infinite loop.

## 5. Continuity / Handoff on iOS
- When receiving a `CONTINUITY_ACTIVITY` with a URL:
  ```swift
  if let url = URL(string: message.url) {
      UIApplication.shared.open(url, options: [:], completionHandler: nil)
  }
  ```
- When receiving a document, DexoLink saves it to the app's `Documents/DexoLink/` folder and presents `QLPreviewController` (Quick Look).

## 6. Multiple Paired Devices
- Defaults to **OFF** (`isMultiplePairedDevicesEnabled = false`).
- When OFF: Only 1 paired device is trusted. Attempting to pair a new device replaces the existing device.
- When ON: Multiple trusted devices are supported simultaneously, with device-specific unpairing via `TrustStore.shared.removeTrustedDevice(deviceId:)`.

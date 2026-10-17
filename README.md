# DexoLink 🚀

[![CI/CD Pipeline](https://github.com/rahulmahasetha/dexolink/actions/workflows/ci.yml/badge.svg)](https://github.com/rahulmahasetha/dexolink/actions/workflows/ci.yml)
[![Platform - macOS](https://img.shields.io/badge/Platform-macOS%2013%2B-black?logo=apple)](https://github.com/rahulmahasetha/dexolink)
[![Platform - Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green?logo=android)](https://github.com/rahulmahasetha/dexolink)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**DexoLink** enables seamless, zero-configuration peer-to-peer connectivity between macOS and Android devices over local Wi-Fi. Inspired by Apple AirDrop, DexoLink provides automatic device discovery, one-time cryptographic pairing, real-time bidirectional clipboard synchronization, and high-performance file streaming—all without requiring an active internet connection or third-party cloud servers.

---

## ✨ Features

- 📡 **Zero-Configuration Automatic Discovery (mDNS / Bonjour / NSD)**  
  Eliminates manual IP and port entry. macOS uses `NWBrowser`/`NWListener` and Android uses `NsdManager` over the `_macdroid._tcp.` local domain. Discovered devices appear instantly on both apps.

- 🔐 **One-Time Cryptographic Pairing & SAS 6-Digit PIN**  
  Establishes trust via cryptographic identities. Both devices deterministically compute a 6-digit Short Authentication String (SAS) confirmation code (`SHA-256(sorted(K1, K2)) % 1000000`). Confirming the code on Android links the devices permanently.

- 💾 **Persistent TrustStore & Zero-Touch Reconnection**  
  Trusted identities are securely persisted in `TrustStore` on both macOS (`trusted_devices.json`) and Android (`SharedPreferences`). Once paired, devices automatically authenticate upon network reconnects or app restarts without re-entering PINs.

- 📋 **Bidirectional Real-Time Clipboard Synchronization**  
  Copying text on either device instantly synchronizes with the other in real time. Features three user-selectable sync modes:
  1. **Two-Way Sync:** Copies on either device automatically update the other.
  2. **Android → Mac:** Android copies update Mac clipboard only.
  3. **Mac → Android:** Mac copies update Android clipboard only.  
  Includes cryptographic content-hash tracking and sequence ordering to prevent sync loops and echo duplicates.

- ⚡ **Background Persistence on Android**  
  Runs a sticky Foreground Service with `PARTIAL_WAKE_LOCK` and `WIFI_MODE_FULL_HIGH_PERF`, maintaining socket connections and background clipboard sync even when the app is minimized.

- 📁 **Chunked File Transfer Streaming**  
  Transfers large files with chunked protocol streaming, progress tracking, and SHA-256 verification.

- 🛡️ **Local-Only & Private**  
  All traffic stays strictly within your local Wi-Fi subnet. No cloud accounts, telemetries, or external internet connections required.

---

## 📥 Installation

### macOS
1. Download `DexoLink.dmg` from the latest [GitHub Release](https://github.com/rahulmahasetha/dexolink/releases) or the [Actions Artifacts](https://github.com/rahulmahasetha/dexolink/actions).
2. Open `DexoLink.dmg` and drag **DexoLink.app** into your `/Applications` folder.
3. Launch **DexoLink** from Applications or Spotlight. It lives conveniently in your macOS Menu Bar.
4. When prompted by macOS, allow Local Network permissions.

### Android
1. Download `DexoLink-Android.apk` from the latest [GitHub Release](https://github.com/rahulmahasetha/dexolink/releases) or the [Actions Artifacts](https://github.com/rahulmahasetha/dexolink/actions).
2. Open the APK on your Android device to install (allow "Install Unknown Apps" if prompted).
3. Open **DexoLink** and grant notification permissions (required for foreground background persistence).

---

## 🚀 Quick Start & Pairing Guide

1. **Connect to Same Wi-Fi:** Ensure your Mac and Android phone are connected to the same local Wi-Fi network.
2. **Launch Both Apps:**
   - Open **DexoLink** on your Mac (look for the antenna icon in the top menu bar).
   - Open **DexoLink** on your Android phone.
3. **Automatic Discovery:**
   - Your Android phone will appear in the Mac menu bar dropdown.
   - Your Mac will appear in the Android app under **"Nearby MacBooks"**.
4. **One-Time Pairing:**
   - Tap **"Pair & Connect"** on Android (or "Pair" on Mac).
   - A 6-digit confirmation PIN will appear on both screens.
   - Tap **"Pair & Trust Device"** to verify.
5. **Enjoy Seamless Sync:**
   - Both devices now show **"★ Trusted"** and are connected!
   - Copy any text on your Mac or Android—it updates the other device instantly.

---

## 🏗️ Project Architecture

```
dexolink/
├── .github/
│   └── workflows/
│       └── ci.yml               # GitHub Actions CI/CD pipeline
├── android/
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/macdroid/
│   │   │   │   ├── core/        # Clipboard manager & File streamer
│   │   │   │   ├── networking/  # NSD Discovery & Socket ConnectionManager
│   │   │   │   ├── security/    # SAS PairingManager & CryptoService
│   │   │   │   ├── service/     # Foreground persistent service
│   │   │   │   ├── storage/     # Persistent TrustStore
│   │   │   │   └── ui/          # Jetpack Compose UI
│   │   │   ├── res/             # Vector icons & branding resources
│   │   │   └── AndroidManifest.xml
│   │   └── build.gradle.kts
│   ├── gradlew
│   └── settings.gradle.kts
├── macos/
│   ├── Sources/
│   │   ├── Core/                # ClipboardManager & TransferManager
│   │   ├── Crypto/              # TrustStore, CryptoService & PairingManager
│   │   ├── Networking/          # Bonjour DiscoveryService & ConnectionManager
│   │   ├── UI/                  # MenuBarView & SwiftUI controls
│   │   └── MacDroidApp/         # App entrypoint & State coordination
│   ├── Tests/                   # Unit & protocol codec tests
│   ├── scripts/
│   │   └── package_dmg.sh       # Automated DMG packager
│   └── Package.swift
├── docs/
│   └── PROTOCOL.md              # Binary framing & message specs
├── shared/
│   └── protocol/                # JSON schema & cross-platform test vectors
├── .gitignore
├── dexolink_app_logo.jpg
└── README.md
```

---

## 🛠️ Building From Source

### Prerequisites
- **macOS:** macOS 13+ (Ventura or newer), Swift 5.9+ / Xcode 15+
- **Android:** Android Studio Jellyfish / Koala, JDK 21+

### Build macOS App & DMG
```bash
cd macos

# Run tests
swift test

# Build release and package DMG
chmod +x scripts/package_dmg.sh
./scripts/package_dmg.sh
```
The resulting `DexoLink.dmg` will be in `macos/dist/DexoLink.dmg`.

### Build Android APK
```bash
cd android

# Run unit tests
./gradlew test

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease
```
The resulting APKs will be in `android/app/build/outputs/apk/`.

---

## 🔄 Protocol & Network Specification

DexoLink uses a custom framing protocol over raw TCP:
- **Framing:** 4-byte Big-Endian Length prefix followed by UTF-8 encoded JSON payload:
  ```
  +-------------------------+---------------------------------+
  | 4-byte Payload Length   | JSON Message Payload (UTF-8)    |
  +-------------------------+---------------------------------+
  ```
- **Service Type:** `_macdroid._tcp.local.` on port `52840`.
- **Handshake Sequence:**
  1. Discovery: mDNS TXT record resolves `deviceId` and `deviceName`.
  2. Initiation: `HELLO` $\rightarrow$ `HELLO_ACK`.
  3. Trust Check: If device is in `TrustStore`, `HELLO_ACK(paired: true)` triggers instant authentication.
  4. Pairing (if untrusted): `PAIR_REQUEST` $\rightarrow$ SAS 6-digit PIN confirmation $\rightarrow$ `PAIR_RESPONSE` $\rightarrow$ Persist in `TrustStore`.
  5. Session: `CLIPBOARD_MODE`, `CLIPBOARD_PUSH` / `CLIPBOARD_ACK`, file chunk streaming, and heartbeats (`PING`/`PONG`).

For complete protocol schema details, see [docs/PROTOCOL.md](docs/PROTOCOL.md).

---

## 🤝 Contributing

Pull requests are welcome! For major changes, please open an issue first to discuss what you would like to change. All contributions are automatically tested and built by the [GitHub Actions CI/CD pipeline](.github/workflows/ci.yml).

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

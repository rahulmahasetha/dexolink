# DexoLink for Windows (v4.1.0)

Lightweight, high-performance, zero-external-dependency companion engine for Windows 10/11.

## Key Capabilities
- **Local Network Transfer:** Directly communicates over local Wi-Fi or Ethernet on port `52840` (TCP) and `52841` (UDP Beacon) with zero internet dependency.
- **Multiple Paired Devices:**
  - Strict **OFF by default** setting preserves single-paired device security.
  - When enabled, supports simultaneous multi-device connections, independent disconnections, and device-specific unpairing.
- **Universal Clipboard Sync:**
  - Real-time text sync between Windows and connected Android/macOS/iOS devices.
  - Automatic line ending normalization (`\r\n` <-> `\n`) and SHA-256 loop prevention.
  - Zero-width character stripping for Enhanced Clipboard compatibility.
- **Fast Chunked File Sharing:**
  - High-throughput 512KB chunked transmission with SHA-256 file verification.
  - Automatically saves incoming files to `Downloads/DexoLink/`.
- **Continuity / Handoff:**
  - Automatically opens URLs sent via Handoff in the user's default Windows browser.
  - Automatically launches transferred documents with associated native applications.

## Requirements
- Python 3.8+ (uses only Python standard library: `socket`, `struct`, `hashlib`, `json`, `ctypes`, `threading`, `uuid`, `webbrowser`).
- Windows 10 / Windows 11.

## Easy-to-Use Desktop App
- **1-Click Launch:** Double-click [`run_windows_gui.bat`](run_windows_gui.bat) or [`dexolink.pyw`](dexolink.pyw) to launch the modern graphical user interface.
- **Features in GUI:**
  - One-click file picker and drag-and-drop file sender with live progress bar.
  - Universal Clipboard quick sender and history viewer.
  - Continuity web page launcher.
  - Device list with 1-click pairing and **Multiple Paired Devices toggle** (OFF by default).

## Running via Command Line
```bash
# Launch modern desktop GUI:
python dexolink_gui.py

# Or launch background headless engine:
python dexolink_windows.py
```

## Running the Unit Tests
```bash
python tests/test_windows_protocol.py
```

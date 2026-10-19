# DexoLink Paste Assistant (Browser Extension)

The **DexoLink Paste Assistant** is an optional companion browser extension designed to legitimately paste cross-device synced clipboard content into restricted web inputs and websites where page-level JavaScript `onpaste` blockers or browser gesture restrictions interfere with standard pasting.

## Why This Extension Exists

Certain websites and web forms (such as anti-fraud forms, testing portals, banking login confirmations, or custom web forms):
1. Intercept keyboard shortcuts (`Ctrl+V` / `Cmd+V`) and execute `event.preventDefault()` or `onpaste="return false;"`.
2. Restrict access to `navigator.clipboard.readText()` unless an explicit user action occurs on an allowed origin.
3. Fail to parse cross-platform line breaks (`\r\n` from Windows vs `\n` from macOS/Android) or fail form validation due to invisible zero-width Unicode characters.

## How It Works Legally & Legitiately

- **Explicit User Activation**: The extension operates strictly upon explicit user interaction via the right-click context menu (**"Paste via DexoLink Assistant"**).
- **Standard DOM Insertion**: It uses standard web APIs (`document.execCommand('insertText')` or standard `InputEvent` dispatching) so that React, Vue, Angular, and form state listeners properly record the entered text.
- **No Unauthorized Script Injection or Security Bypass**: It does not defeat authentication protections, crack CAPTCHAs, or disable security headers (CSP).
- **Sanitization**: Automatically normalizes line endings and removes invisible characters that trigger form validation failures.

## Installation Instructions

1. Open your browser's extension manager:
   - **Chrome / Brave / Edge**: Navigate to `chrome://extensions/`
   - Enable **Developer mode** (toggle in the top-right corner).
2. Click **Load unpacked**.
3. Select this directory: `extensions/dexolink-paste-assistant`.
4. Right-click any text field or content-editable input and select **Paste via DexoLink Assistant**.

## Known Limitations

- **Canvas-Only Renderers**: Web applications rendered purely via HTML5 Canvas or WebAssembly (e.g., Figma Canvas, Google Docs in Canvas Rendering Mode) do not expose DOM input elements. Direct typing or file import remains necessary in canvas mode.
- **OS-Protected Secure Fields**: Android and macOS secure PIN / password fields protected at the operating-system level prevent clipboard injection by design.

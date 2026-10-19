#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MACOS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_DIR="$(cd "$MACOS_DIR/.." && pwd)"

echo "🚀 Building DexoLink macOS release binary..."
cd "$MACOS_DIR"
swift build -c release

SHOW_BIN="$(swift build -c release --show-bin-path 2>/dev/null || true)"
BIN_PATH="$SHOW_BIN/MacDroidApp"

if [ ! -f "$BIN_PATH" ]; then
    BIN_PATH="$MACOS_DIR/.build/out/Products/Release/MacDroidApp"
fi

if [ ! -f "$BIN_PATH" ]; then
    BIN_PATH="$MACOS_DIR/.build/release/MacDroidApp"
fi

if [ ! -f "$BIN_PATH" ]; then
    echo "❌ Error: Release binary not found at $BIN_PATH"
    exit 1
fi

DIST_DIR="$MACOS_DIR/dist"
APP_NAME="DexoLink.app"
APP_BUNDLE="$DIST_DIR/$APP_NAME"
DMG_NAME="DexoLink.dmg"
DMG_OUTPUT="$DIST_DIR/$DMG_NAME"
STAGE_DIR="$DIST_DIR/dmg_stage"

rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"
mkdir -p "$APP_BUNDLE/Contents/MacOS"
mkdir -p "$APP_BUNDLE/Contents/Resources"

echo "📦 Copying binary to $APP_NAME..."
cp "$BIN_PATH" "$APP_BUNDLE/Contents/MacOS/DexoLink"
chmod +x "$APP_BUNDLE/Contents/MacOS/DexoLink"

echo "🎨 Generating AppIcon.icns from dexolink_app_logo..."
LOGO_PATH="$REPO_DIR/dexolink_app_logo.jpg"
if [ -f "$LOGO_PATH" ]; then
    ICONSET="$DIST_DIR/DexoLink.iconset"
    mkdir -p "$ICONSET"
    sips -s format png -z 16 16     "$LOGO_PATH" --out "$ICONSET/icon_16x16.png" >/dev/null
    sips -s format png -z 32 32     "$LOGO_PATH" --out "$ICONSET/icon_16x16@2x.png" >/dev/null
    sips -s format png -z 32 32     "$LOGO_PATH" --out "$ICONSET/icon_32x32.png" >/dev/null
    sips -s format png -z 64 64     "$LOGO_PATH" --out "$ICONSET/icon_32x32@2x.png" >/dev/null
    sips -s format png -z 128 128   "$LOGO_PATH" --out "$ICONSET/icon_128x128.png" >/dev/null
    sips -s format png -z 256 256   "$LOGO_PATH" --out "$ICONSET/icon_128x128@2x.png" >/dev/null
    sips -s format png -z 256 256   "$LOGO_PATH" --out "$ICONSET/icon_256x256.png" >/dev/null
    sips -s format png -z 512 512   "$LOGO_PATH" --out "$ICONSET/icon_256x256@2x.png" >/dev/null
    sips -s format png -z 512 512   "$LOGO_PATH" --out "$ICONSET/icon_512x512.png" >/dev/null
    sips -s format png -z 1024 1024 "$LOGO_PATH" --out "$ICONSET/icon_512x512@2x.png" >/dev/null
    iconutil -c icns "$ICONSET" -o "$APP_BUNDLE/Contents/Resources/AppIcon.icns"
    rm -rf "$ICONSET"
fi

echo "📝 Writing Info.plist..."
cat << 'EOF' > "$APP_BUNDLE/Contents/Info.plist"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>DexoLink</string>
    <key>CFBundleIdentifier</key>
    <string>com.dexolink.macos</string>
    <key>CFBundleName</key>
    <string>DexoLink</string>
    <key>CFBundleDisplayName</key>
    <string>DexoLink</string>
    <key>CFBundleIconFile</key>
    <string>AppIcon</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>4.0.0</string>
    <key>CFBundleVersion</key>
    <string>4.0.0</string>
    <key>LSMinimumSystemVersion</key>
    <string>13.0</string>
    <key>LSUIElement</key>
    <true/>
    <key>NSHighResolutionCapable</key>
    <true/>
    <key>NSLocalNetworkUsageDescription</key>
    <string>DexoLink discovers and connects with your Android device on the local network for automatic clipboard and file synchronization.</string>
    <key>NSBonjourServices</key>
    <array>
        <string>_macdroid._tcp</string>
    </array>
    <key>NSServices</key>
    <array>
        <dict>
            <key>NSMenuItem</key>
            <dict>
                <key>default</key>
                <string>Send with DexoLink</string>
            </dict>
            <key>NSMessage</key>
            <string>shareToDexoLink</string>
            <key>NSPortName</key>
            <string>DexoLink</string>
            <key>NSRequiredContext</key>
            <dict/>
            <key>NSSendTypes</key>
            <array>
                <string>NSFilenamesPboardType</string>
                <string>public.plain-text</string>
                <string>public.utf8-plain-text</string>
                <string>NSStringPboardType</string>
                <string>NSURLPboardType</string>
                <string>public.url</string>
                <string>public.file-url</string>
            </array>
        </dict>
    </array>
    <key>CFBundleDocumentTypes</key>
    <array>
        <dict>
            <key>CFBundleTypeName</key>
            <string>All Files</string>
            <key>CFBundleTypeRole</key>
            <string>Viewer</string>
            <key>LSHandlerRank</key>
            <string>Alternate</string>
            <key>LSItemContentTypes</key>
            <array>
                <string>public.item</string>
                <string>public.data</string>
                <string>public.content</string>
            </array>
        </dict>
    </array>
    <key>CFBundleURLTypes</key>
    <array>
        <dict>
            <key>CFBundleURLName</key>
            <string>com.dexolink.share</string>
            <key>CFBundleURLSchemes</key>
            <array>
                <string>dexolink</string>
            </array>
        </dict>
    </array>
</dict>
</plist>
EOF

echo "💿 Staging DMG creation..."
mkdir -p "$STAGE_DIR"
cp -R "$APP_BUNDLE" "$STAGE_DIR/"
ln -s /Applications "$STAGE_DIR/Applications"

echo "💽 Building DexoLink.dmg with hdiutil..."
rm -f "$DMG_OUTPUT"
hdiutil create -volname "DexoLink" -srcfolder "$STAGE_DIR" -ov -format UDZO "$DMG_OUTPUT"
rm -rf "$STAGE_DIR"

echo "✅ Successfully created installable macOS app and DMG:"
echo "   App: $APP_BUNDLE"
echo "   DMG: $DMG_OUTPUT"
ls -lh "$DMG_OUTPUT"

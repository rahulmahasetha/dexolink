#!/bin/bash
set -e

# Package DexoLink for iPhone as an installable .ipa file.
# Targets iOS 16.0+ on arm64 devices (iPhone & iPad).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_DIR="$(cd "$IOS_DIR/.." && pwd)"
DEXO_OUTPUT_DIR="/Users/rahulmahaseth/Desktop/Dexo"

DIST_DIR="$IOS_DIR/dist"
APP_NAME="DexoLink.app"
IPA_NAME="DexoLink.ipa"
IPA_OUTPUT="$DIST_DIR/$IPA_NAME"
STAGE_DIR="$DIST_DIR/ipa_stage"
PAYLOAD_DIR="$STAGE_DIR/Payload"
APP_BUNDLE="$PAYLOAD_DIR/$APP_NAME"

echo "Resolving iOS SDK..."
IPHONEOS_SDK="$(xcrun --sdk iphoneos --show-sdk-path 2>/dev/null || true)"
if [ -z "$IPHONEOS_SDK" ] || [ ! -d "$IPHONEOS_SDK" ]; then
    echo "Error: iPhoneOS SDK not found. Ensure Xcode with iOS SDK is installed."
    exit 1
fi
echo "Using iPhoneOS SDK: $IPHONEOS_SDK"

rm -rf "$DIST_DIR"
mkdir -p "$PAYLOAD_DIR"

echo "Compiling DexoLink for iOS (arm64, iOS 16.0+)..."
SWIFT_SOURCES=(
    "$IOS_DIR/Sources/DexoLinkiOS/DexoLinkiOSApp.swift"
    "$IOS_DIR/Sources/DexoLinkiOS/iOSAppState.swift"
    "$IOS_DIR/Sources/DexoLinkiOS/Views/ClipboardView.swift"
    "$IOS_DIR/Sources/DexoLinkiOS/Views/ContentView.swift"
    "$IOS_DIR/Sources/DexoLinkiOS/Views/ContinuityView.swift"
    "$IOS_DIR/Sources/DexoLinkiOS/Views/HomeView.swift"
    "$IOS_DIR/Sources/DexoLinkiOS/Views/QRConnectView.swift"
    "$IOS_DIR/Sources/DexoLinkiOS/Views/DevicesView.swift"
    "$IOS_DIR/Sources/DexoLinkiOS/Views/TransferView.swift"
)

mkdir -p "$APP_BUNDLE"

xcrun --sdk iphoneos swiftc \
    -target arm64-apple-ios16.0 \
    -sdk "$IPHONEOS_SDK" \
    -O \
    -whole-module-optimization \
    -emit-executable \
    "${SWIFT_SOURCES[@]}" \
    -o "$APP_BUNDLE/DexoLink"

chmod +x "$APP_BUNDLE/DexoLink"

echo "Copying Info.plist..."
cp "$IOS_DIR/Info.plist" "$APP_BUNDLE/Info.plist"

echo "Generating iOS App Icons..."
LOGO_PATH="$REPO_DIR/dexolink_app_logo.jpg"
if [ -f "$LOGO_PATH" ]; then
    sips -s format png -z 120 120   "$LOGO_PATH" --out "$APP_BUNDLE/AppIcon60x60@2x.png" >/dev/null
    sips -s format png -z 180 180   "$LOGO_PATH" --out "$APP_BUNDLE/AppIcon60x60@3x.png" >/dev/null
    sips -s format png -z 152 152   "$LOGO_PATH" --out "$APP_BUNDLE/AppIcon76x76@2x~ipad.png" >/dev/null
    sips -s format png -z 167 167   "$LOGO_PATH" --out "$APP_BUNDLE/AppIcon83.5x83.5@2x~ipad.png" >/dev/null
    sips -s format png -z 1024 1024 "$LOGO_PATH" --out "$APP_BUNDLE/AppIcon1024x1024.png" >/dev/null

    # iTunesArtwork for IPA packaging
    sips -s format png -z 512 512   "$LOGO_PATH" --out "$STAGE_DIR/iTunesArtwork" >/dev/null
    sips -s format png -z 1024 1024 "$LOGO_PATH" --out "$STAGE_DIR/iTunesArtwork@2x" >/dev/null
fi

echo "Signing iOS App Bundle (ad-hoc)..."
codesign --force --sign - --timestamp=none --deep "$APP_BUNDLE"

echo "Creating DexoLink.ipa package..."
rm -f "$IPA_OUTPUT"
(
    cd "$STAGE_DIR"
    zip -qr "$IPA_OUTPUT" Payload iTunesArtwork iTunesArtwork@2x 2>/dev/null || zip -qr "$IPA_OUTPUT" Payload
)

if [ -d "$DEXO_OUTPUT_DIR" ]; then
    echo "Copying DexoLink.ipa to $DEXO_OUTPUT_DIR..."
    cp -f "$IPA_OUTPUT" "$DEXO_OUTPUT_DIR/$IPA_NAME"
fi

echo "Successfully built DexoLink.ipa for iPhone:"
echo "   IPA: $IPA_OUTPUT"
if [ -f "$DEXO_OUTPUT_DIR/$IPA_NAME" ]; then
    echo "   Desktop Distribution: $DEXO_OUTPUT_DIR/$IPA_NAME"
    ls -lh "$DEXO_OUTPUT_DIR/$IPA_NAME"
fi

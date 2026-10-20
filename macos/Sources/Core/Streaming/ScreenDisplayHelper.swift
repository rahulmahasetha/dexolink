import Foundation
import CoreGraphics
#if os(macOS)
import AppKit
#endif

/// Frame dimensions in physical pixels (adapted from p2p.kiwi screenPicker).
public struct FrameSize: Equatable, Codable {
    public var width: Int
    public var height: Int

    public init(width: Int, height: Int) {
        self.width = width
        self.height = height
    }
}

/// Information about an enumerated display source (adapted from p2p.kiwi getDesktopSources).
public struct DisplaySourceInfo: Identifiable, Equatable {
    public var id: String
    public var name: String
    public var bounds: CGRect
    public var scaleFactor: CGFloat
    public var pixelSize: FrameSize
    public var isPrimary: Bool

    public init(id: String, name: String, bounds: CGRect, scaleFactor: CGFloat, pixelSize: FrameSize, isPrimary: Bool) {
        self.id = id
        self.name = name
        self.bounds = bounds
        self.scaleFactor = scaleFactor
        self.pixelSize = pixelSize
        self.isPrimary = isPrimary
    }
}

public enum ScreenDisplayHelper {
    public static let displayMatchPx: Int = 8

    /// Computes physical pixel size from logical bounds and display scale factor.
    /// Adapted from p2p.kiwi: displayPixelSize(bounds, scaleFactor)
    public static func displayPixelSize(bounds: CGSize, scaleFactor: CGFloat) -> FrameSize {
        let scale = scaleFactor > 0 ? scaleFactor : 1.0
        return FrameSize(
            width: Int((bounds.width * scale).rounded()),
            height: Int((bounds.height * scale).rounded())
        )
    }

    /// Checks if a frame size matches any display pixel dimensions within tolerance.
    /// Adapted from p2p.kiwi: frameMatchesDisplay(frame, displays)
    public static func frameMatchesDisplay(frame: FrameSize, displays: [FrameSize], tolerancePx: Int = displayMatchPx) -> Bool {
        return displays.contains { display in
            abs(frame.width - display.width) <= tolerancePx &&
            abs(frame.height - display.height) <= tolerancePx
        }
    }

    /// Determines whether the capture surface is a full monitor or a cropped window/surface.
    /// Adapted from p2p.kiwi: shareSurfaceFromFrame(reported, frame, displays)
    public static func shareSurfaceFromFrame(reported: String?, frame: FrameSize, displays: [FrameSize]) -> String? {
        if reported == "window" || reported == "browser" {
            return reported
        }
        let sized = frame.width > 0 && frame.height > 0
        let matches = sized && frameMatchesDisplay(frame: frame, displays: displays)
        if sized && !matches {
            return "window"
        }
        if reported == "monitor" || matches {
            return "monitor"
        }
        return nil
    }

    /// Computes optimal proportional capture dimensions to maintain exact aspect ratio
    /// while staying within maximum bounds (default max edge 1280px for low-latency streaming).
    public static func calculateProportionalTargetSize(sourceWidth: Int, sourceHeight: Int, maxEdge: Int = 1280) -> FrameSize {
        guard sourceWidth > 0 && sourceHeight > 0 else {
            return FrameSize(width: 960, height: 540)
        }
        let maxDim = max(sourceWidth, sourceHeight)
        if maxDim <= maxEdge {
            return FrameSize(width: sourceWidth, height: sourceHeight)
        }
        let scale = Double(maxEdge) / Double(maxDim)
        var targetW = Int((Double(sourceWidth) * scale).rounded())
        var targetH = Int((Double(sourceHeight) * scale).rounded())
        if targetW % 2 != 0 { targetW -= 1 }
        if targetH % 2 != 0 { targetH -= 1 }
        return FrameSize(width: max(targetW, 2), height: max(targetH, 2))
    }

    #if os(macOS)
    /// Enumerates all active displays on macOS with bounds and pixel dimensions.
    public static func getActiveDisplaySources() -> [DisplaySourceInfo] {
        var sources: [DisplaySourceInfo] = []
        var displayCount: UInt32 = 0
        CGGetActiveDisplayList(0, nil, &displayCount)
        guard displayCount > 0 else { return sources }

        var displayIDs = [CGDirectDisplayID](repeating: 0, count: Int(displayCount))
        CGGetActiveDisplayList(displayCount, &displayIDs, &displayCount)

        let mainDisplayID = CGMainDisplayID()
        let screens = NSScreen.screens

        for (index, displayID) in displayIDs.prefix(Int(displayCount)).enumerated() {
            let bounds = CGDisplayBounds(displayID)
            let matchingScreen = screens.first { screen in
                let desc = screen.deviceDescription
                if let screenNumber = desc[NSDeviceDescriptionKey("NSScreenNumber")] as? CGDirectDisplayID {
                    return screenNumber == displayID
                }
                return false
            }
            let scaleFactor = matchingScreen?.backingScaleFactor ?? 1.0
            let pixelSize = displayPixelSize(bounds: bounds.size, scaleFactor: scaleFactor)
            let isPrimary = (displayID == mainDisplayID)
            let name = isPrimary ? "Primary Display" : "Display \(index + 1)"

            sources.append(DisplaySourceInfo(
                id: "\(displayID)",
                name: name,
                bounds: bounds,
                scaleFactor: scaleFactor,
                pixelSize: pixelSize,
                isPrimary: isPrimary
            ))
        }
        return sources
    }
    #endif
}

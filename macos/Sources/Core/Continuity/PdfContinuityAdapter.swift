import Foundation
import AppKit
import Networking

public final class PdfContinuityAdapter: ContinuityAdapter {
    public let activityType: String = "pdf"

    public init() {}

    public func canHandle(activity: ContinuityActivity) -> Bool {
        if activity.activityType == activityType { return true }
        if let urlStr = activity.url?.lowercased(), urlStr.hasSuffix(".pdf") { return true }
        return activity.title.lowercased().hasSuffix(".pdf")
    }

    public func continueActivity(_ activity: ContinuityActivity) -> Bool {
        // 1. If web URL with PDF, open with #page=X fragment to restore reading position
        if let urlStr = activity.url, let url = URL(string: urlStr), url.scheme?.hasPrefix("http") == true {
            let pageTarget = activity.position > 0 ? "\(urlStr)#page=\(activity.position)" : urlStr
            if let targetUrl = URL(string: pageTarget) {
                return NSWorkspace.shared.open(targetUrl)
            }
        }

        // 2. If local file exists, open with Preview / system viewer
        if let urlStr = activity.url {
            let fileUrl = urlStr.hasPrefix("file://") ? URL(string: urlStr) : URL(fileURLWithPath: urlStr)
            if let fileUrl = fileUrl, FileManager.default.fileExists(atPath: fileUrl.path) {
                let config = NSWorkspace.OpenConfiguration()
                config.activates = true
                NSWorkspace.shared.open([fileUrl], withApplicationAt: URL(fileURLWithPath: "/System/Applications/Preview.app"), configuration: config) { _, error in
                    if error != nil {
                        NSWorkspace.shared.open(fileUrl)
                    }
                }
                return true
            }
        }

        // 3. Fallback: payload URL
        if let payload = activity.contentPayload, let url = URL(string: payload) {
            return NSWorkspace.shared.open(url)
        }

        return false
    }
}

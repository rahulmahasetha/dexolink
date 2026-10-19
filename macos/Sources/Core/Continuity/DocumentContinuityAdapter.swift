import Foundation
import AppKit
import Networking

public final class DocumentContinuityAdapter: ContinuityAdapter {
    public let activityType: String = "document"

    public init() {}

    public func canHandle(activity: ContinuityActivity) -> Bool {
        if activity.activityType == activityType { return true }
        let lower = activity.title.lowercased()
        return lower.hasSuffix(".docx") || lower.hasSuffix(".doc") || lower.hasSuffix(".txt") || lower.hasSuffix(".md") || lower.hasSuffix(".pages")
    }

    public func continueActivity(_ activity: ContinuityActivity) -> Bool {
        if let urlStr = activity.url, let url = URL(string: urlStr) {
            return NSWorkspace.shared.open(url)
        }
        return false
    }
}

import Foundation
import AppKit
import Networking

public final class PresentationContinuityAdapter: ContinuityAdapter {
    public let activityType: String = "presentation"

    public init() {}

    public func canHandle(activity: ContinuityActivity) -> Bool {
        if activity.activityType == activityType { return true }
        let lower = activity.title.lowercased()
        return lower.hasSuffix(".key") || lower.hasSuffix(".pptx") || lower.hasSuffix(".ppt")
    }

    public func continueActivity(_ activity: ContinuityActivity) -> Bool {
        if let urlStr = activity.url, let url = URL(string: urlStr) {
            return NSWorkspace.shared.open(url)
        }
        return false
    }
}

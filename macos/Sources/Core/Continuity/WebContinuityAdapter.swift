import Foundation
import AppKit
import Networking

public final class WebContinuityAdapter: ContinuityAdapter {
    public let activityType: String = "webpage"

    public init() {}

    public func canHandle(activity: ContinuityActivity) -> Bool {
        if activity.activityType == activityType || activity.activityType == "web" || activity.activityType == "browser" { return true }
        if !activity.activityType.isEmpty { return false }
        guard let urlStr = activity.url, let url = URL(string: urlStr), let scheme = url.scheme?.lowercased() else {
            return false
        }
        return (scheme == "http" || scheme == "https") && !urlStr.lowercased().hasSuffix(".pdf")
    }

    public func continueActivity(_ activity: ContinuityActivity) -> Bool {
        guard let urlStr = activity.url, let url = URL(string: urlStr) else { return false }
        return NSWorkspace.shared.open(url)
    }
}

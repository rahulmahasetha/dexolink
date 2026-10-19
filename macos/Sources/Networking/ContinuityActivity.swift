import Foundation

public struct ContinuityActivity: Codable, Identifiable, Equatable {
    public var id: String { activityId }
    public let activityId: String
    public let activityType: String // "webpage", "pdf", "document", "presentation"
    public let title: String
    public let subtitle: String
    public let url: String?
    public let contentPayload: String?
    public let position: Int // page number, slide number, or cursor position
    public let progress: Double // 0.0 to 1.0 reading/scroll progress
    public let timestamp: Int64
    public let sourceDevice: String

    public init(
        activityId: String = UUID().uuidString,
        activityType: String,
        title: String,
        subtitle: String = "",
        url: String? = nil,
        contentPayload: String? = nil,
        position: Int = 0,
        progress: Double = 0.0,
        timestamp: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        sourceDevice: String = Host.current().localizedName ?? "Mac"
    ) {
        self.activityId = activityId
        self.activityType = activityType
        self.title = title
        self.subtitle = subtitle
        self.url = url
        self.contentPayload = contentPayload
        self.position = position
        self.progress = progress
        self.timestamp = timestamp
        self.sourceDevice = sourceDevice
    }

    public var displaySubtitle: String {
        switch activityType {
        case "pdf":
            return position > 0 ? "Page \(position) • PDF" : (subtitle.isEmpty ? "PDF Document" : subtitle)
        case "presentation":
            return position > 0 ? "Slide \(position) • Presentation" : (subtitle.isEmpty ? "Presentation" : subtitle)
        case "document":
            return position > 0 ? "Page \(position) • Document" : (subtitle.isEmpty ? "Document" : subtitle)
        case "webpage":
            return subtitle.isEmpty ? (url ?? "Webpage") : subtitle
        default:
            return subtitle.isEmpty ? activityType.capitalized : subtitle
        }
    }
}

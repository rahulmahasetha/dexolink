import Foundation
import AppKit
import Networking
import UserNotifications

public final class ContinuityManager: ObservableObject {
    public static let shared = ContinuityManager()

    private let userDefaultsKey = "dexolink_continuity_enabled"
    private var adapters: [ContinuityAdapter] = []
    private weak var connectionManager: ConnectionManager?

    @Published public var isEnabled: Bool {
        didSet {
            UserDefaults.standard.set(isEnabled, forKey: userDefaultsKey)
            if !isEnabled {
                incomingActivity = nil
            }
            connectionManager?.send(message: .continuityState(enabled: isEnabled))
        }
    }

    @Published public var incomingActivity: ContinuityActivity?
    @Published public var lastSentActivity: ContinuityActivity?

    public init() {
        self.isEnabled = UserDefaults.standard.object(forKey: userDefaultsKey) as? Bool ?? true
        registerDefaultAdapters()
    }

    public func setConnectionManager(_ manager: ConnectionManager) {
        self.connectionManager = manager
    }

    public func registerAdapter(_ adapter: ContinuityAdapter) {
        adapters.append(adapter)
    }

    private func registerDefaultAdapters() {
        registerAdapter(WebContinuityAdapter())
        registerAdapter(PdfContinuityAdapter())
        registerAdapter(PresentationContinuityAdapter())
        registerAdapter(DocumentContinuityAdapter())
    }

    public func handleMessage(_ message: ProtocolMessage) {
        guard isEnabled else { return }

        switch message {
        case let .continuityActivity(activityId, activityType, title, subtitle, url, contentPayload, position, progress, timestamp, sourceDevice):
            let activity = ContinuityActivity(
                activityId: activityId,
                activityType: activityType,
                title: title,
                subtitle: subtitle,
                url: url,
                contentPayload: contentPayload,
                position: position,
                progress: progress,
                timestamp: timestamp,
                sourceDevice: sourceDevice
            )
            DispatchQueue.main.async { [weak self] in
                guard let self = self, self.isEnabled else { return }
                self.incomingActivity = activity
                self.postContinuityNotification(for: activity)
            }
            connectionManager?.send(message: .continuityAck(activityId: activityId, accepted: true))

        case let .continuityDismiss(activityId):
            DispatchQueue.main.async { [weak self] in
                if self?.incomingActivity?.activityId == activityId {
                    self?.incomingActivity = nil
                }
            }

        case let .continuityState(peerEnabled):
            print("[Continuity] Peer reported continuity enabled: \(peerEnabled)")

        default:
            break
        }
    }

    public func continueIncomingActivity() -> Bool {
        guard let activity = incomingActivity else { return false }
        for adapter in adapters {
            if adapter.canHandle(activity: activity) {
                let success = adapter.continueActivity(activity)
                if success {
                    incomingActivity = nil
                    return true
                }
            }
        }
        return false
    }

    public func dismissIncomingActivity() {
        if let id = incomingActivity?.activityId {
            connectionManager?.send(message: .continuityDismiss(activityId: id))
        }
        incomingActivity = nil
    }

    public func broadcastActivity(_ activity: ContinuityActivity) {
        guard isEnabled else { return }
        lastSentActivity = activity
        let msg = ProtocolMessage.continuityActivity(
            activityId: activity.activityId,
            activityType: activity.activityType,
            title: activity.title,
            subtitle: activity.subtitle,
            url: activity.url,
            contentPayload: activity.contentPayload,
            position: activity.position,
            progress: activity.progress,
            timestamp: activity.timestamp,
            sourceDevice: Host.current().localizedName ?? "MacBook"
        )
        connectionManager?.send(message: msg)
    }

    private func postContinuityNotification(for activity: ContinuityActivity) {
        let content = UNMutableNotificationContent()
        content.title = "Continue on Mac"
        content.subtitle = "\(activity.sourceDevice) • \(activity.displaySubtitle)"
        content.body = activity.title
        content.sound = .default
        content.categoryIdentifier = "CONTINUITY_CATEGORY"
        let request = UNNotificationRequest(identifier: "continuity_\(activity.activityId)", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request, withCompletionHandler: nil)
    }
}

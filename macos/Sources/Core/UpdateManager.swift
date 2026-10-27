import Foundation
import AppKit

// MARK: - Update Data Models

public struct MacUpdateInfo {
    public let currentVersion: String
    public let latestVersion: String
    public let isUpdateAvailable: Bool
    public let releaseTitle: String
    public let releaseNotes: String
    public let downloadUrl: String
    public let assetSize: Int64

    public init(currentVersion: String, latestVersion: String, isUpdateAvailable: Bool, releaseTitle: String, releaseNotes: String, downloadUrl: String, assetSize: Int64) {
        self.currentVersion = currentVersion
        self.latestVersion = latestVersion
        self.isUpdateAvailable = isUpdateAvailable
        self.releaseTitle = releaseTitle
        self.releaseNotes = releaseNotes
        self.downloadUrl = downloadUrl
        self.assetSize = assetSize
    }
}

public enum MacUpdateStatus: Equatable {
    case idle
    case checking
    case available(MacUpdateInfo)
    case upToDate(String)
    case downloading(fraction: Double, bytesDownloaded: Int64, totalBytes: Int64)
    case error(String)

    public static func == (lhs: MacUpdateStatus, rhs: MacUpdateStatus) -> Bool {
        switch (lhs, rhs) {
        case (.idle, .idle): return true
        case (.checking, .checking): return true
        case (.upToDate(let a), .upToDate(let b)): return a == b
        case (.error(let a), .error(let b)): return a == b
        default: return false
        }
    }
}

// MARK: - Update Manager

public final class MacUpdateManager: ObservableObject {

    public static let shared = MacUpdateManager()

    private static let githubRepo = "rahulmahasetha/dexolink"
    private static let apiURL = URL(string: "https://api.github.com/repos/\(githubRepo)/releases/latest")!

    @Published public var status: MacUpdateStatus = .idle

    public var currentVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "4.0.3"
    }

    public var isUpdateAvailable: Bool {
        if case .available = status { return true }
        return false
    }

    public var availableUpdateInfo: MacUpdateInfo? {
        if case .available(let info) = status { return info }
        return nil
    }

    private init() {}

    // MARK: - Public API

    @MainActor
    public func checkForUpdates() async {
        guard status != .checking else { return }
        status = .checking

        do {
            let info = try await fetchLatestRelease()
            if isNewer(info.latestVersion, than: currentVersion) {
                status = .available(info)
            } else {
                status = .upToDate(currentVersion)
            }
        } catch {
            status = .error(error.localizedDescription)
        }
    }

    public func openReleasePage() {
        let url = URL(string: "https://github.com/\(MacUpdateManager.githubRepo)/releases/latest")!
        NSWorkspace.shared.open(url)
    }

    // MARK: - Private Helpers

    private func fetchLatestRelease() async throws -> MacUpdateInfo {
        var request = URLRequest(url: MacUpdateManager.apiURL)
        request.setValue("application/vnd.github.v3+json", forHTTPHeaderField: "Accept")
        request.timeoutInterval = 15

        let (data, _) = try await URLSession.shared.data(for: request)
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]

        let tagName = (json["tag_name"] as? String ?? "").trimmingCharacters(in: .whitespaces)
        let latestVersion = tagName.hasPrefix("v") ? String(tagName.dropFirst()) : tagName
        let releaseTitle = json["name"] as? String ?? "DexoLink \(latestVersion)"
        let releaseNotes = json["body"] as? String ?? ""

        // Find the .dmg asset
        let assets = json["assets"] as? [[String: Any]] ?? []
        let dmg = assets.first { ($0["name"] as? String ?? "").hasSuffix(".dmg") }
        let downloadUrl = dmg?["browser_download_url"] as? String ?? ""
        let assetSize = (dmg?["size"] as? Int).map { Int64($0) } ?? 0

        return MacUpdateInfo(
            currentVersion: currentVersion,
            latestVersion: latestVersion,
            isUpdateAvailable: isNewer(latestVersion, than: currentVersion),
            releaseTitle: releaseTitle,
            releaseNotes: releaseNotes,
            downloadUrl: downloadUrl,
            assetSize: assetSize
        )
    }

    private func isNewer(_ latest: String, than current: String) -> Bool {
        let l = parseVersion(latest)
        let c = parseVersion(current)
        if l.0 != c.0 { return l.0 > c.0 }
        if l.1 != c.1 { return l.1 > c.1 }
        return l.2 > c.2
    }

    private func parseVersion(_ v: String) -> (Int, Int, Int) {
        let cleaned = v.trimmingCharacters(in: .whitespaces)
            .drop(while: { $0 == "v" || $0 == "V" })
        let parts = cleaned.split(separator: ".").prefix(3).map { Int($0.prefix(while: \.isNumber)) ?? 0 }
        return (parts.indices.contains(0) ? parts[0] : 0,
                parts.indices.contains(1) ? parts[1] : 0,
                parts.indices.contains(2) ? parts[2] : 0)
    }
}

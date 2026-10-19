import Foundation

/// Helper utility for DexoLink's Enhanced Clipboard on macOS.
///
/// Provides:
/// 1. Line-ending normalization (\r\n -> \n, \r -> \n)
/// 2. Invisible / zero-width character stripping (\u{200B}, \u{FEFF}, etc.)
/// 3. Multi-MIME HTML generation for rich web editors (Google Docs, Notion, Slack, Apple Notes)
public enum EnhancedClipboardHelper {

    private static let invisibleRegex = try? NSRegularExpression(
        pattern: "[\u{200B}-\u{200D}\u{FEFF}\u{200E}\u{200F}]",
        options: []
    )

    private static let htmlTagRegex = try? NSRegularExpression(
        pattern: "<([a-zA-Z][a-zA-Z0-9]*)\\b[^>]*>(.*?)</\\1>|<([a-zA-Z][a-zA-Z0-9]*)\\b[^/>]*/>",
        options: [.dotMatchesLineSeparators]
    )

    private static let urlRegex = try? NSRegularExpression(
        pattern: "^https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=]+$",
        options: []
    )

    /// Sanitizes plain text by removing invisible formatting artifacts and normalizing line breaks.
    public static func sanitizePlainText(_ input: String) -> String {
        if input.isEmpty { return "" }
        let normalized = input
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")

        guard let regex = invisibleRegex else { return normalized }
        let range = NSRange(location: 0, length: normalized.utf16.count)
        return regex.stringByReplacingMatches(in: normalized, options: [], range: range, withTemplate: "")
    }

    /// Detects if the string already contains valid HTML markup.
    public static func containsHtml(_ text: String) -> Bool {
        if text.isEmpty { return false }
        guard let regex = htmlTagRegex else { return false }
        let range = NSRange(location: 0, length: text.utf16.count)
        return regex.firstMatch(in: text, options: [], range: range) != nil
    }

    /// Generates structured HTML markup for rich text editors (Google Docs, Notion, Slack, Pages, Notes).
    public static func toHtmlRepresentation(_ text: String) -> String {
        let clean = sanitizePlainText(text)
        if clean.isEmpty { return "" }
        if containsHtml(clean) { return clean }

        if let regex = urlRegex {
            let range = NSRange(location: 0, length: clean.utf16.count)
            if regex.firstMatch(in: clean, options: [], range: range) != nil {
                return "<a href=\"\(clean)\">\(clean)</a>"
            }
        }

        let escaped = clean
            .replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "'", with: "&#39;")

        let lines = escaped.components(separatedBy: "\n")
        var html = ""
        for line in lines {
            if line.isEmpty {
                html += "<p><br></p>"
            } else {
                html += "<p>\(line)</p>"
            }
        }
        return html
    }
}

import Foundation
import CryptoKit

public enum FileStreamerError: Error {
    case fileNotFound
    case accessDenied
    case invalidPath
    case readFailed
    case writeFailed
}

public class FileStreamer {
    public static let chunkSize: Int = 64 * 1024 // 64 KB
    
    // MARK: - Sender Side
    
    public static func getFileMetadata(url: URL) throws -> (size: Int64, mimeType: String) {
        guard FileManager.default.isReadableFile(atPath: url.path) else {
            throw FileStreamerError.accessDenied
        }
        
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        guard let size = attributes[.size] as? Int64 else {
            throw FileStreamerError.readFailed
        }
        
        // Simple MIME type fallback, would use UTType in production
        let ext = url.pathExtension.lowercased()
        let mimeType: String
        switch ext {
        case "jpg", "jpeg": mimeType = "image/jpeg"
        case "png": mimeType = "image/png"
        case "pdf": mimeType = "application/pdf"
        case "txt": mimeType = "text/plain"
        default: mimeType = "application/octet-stream"
        }
        
        return (size, mimeType)
    }
    
    public static func readChunk(url: URL, offset: Int64, size: Int = chunkSize) throws -> Data {
        let fileHandle = try FileHandle(forReadingFrom: url)
        defer { try? fileHandle.close() }
        
        try fileHandle.seek(toOffset: UInt64(offset))
        if let data = try fileHandle.read(upToCount: size) {
            return data
        }
        return Data()
    }
    
    public static func computeSHA256(url: URL) throws -> String {
        let fileHandle = try FileHandle(forReadingFrom: url)
        defer { try? fileHandle.close() }
        
        var hasher = SHA256()
        while let data = try fileHandle.read(upToCount: chunkSize), !data.isEmpty {
            hasher.update(data: data)
        }
        
        let hash = hasher.finalize()
        return hash.map { String(format: "%02x", $0) }.joined()
    }
    
    // MARK: - Receiver Side
    
    public static func sanitizeFilename(_ name: String) -> String {
        var clean = name.replacingOccurrences(of: "..", with: "")
        clean = clean.replacingOccurrences(of: "/", with: "_")
        clean = clean.replacingOccurrences(of: "\\", with: "_")
        let allowedCharacterSet = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: " .-_"))
        clean = String(clean.unicodeScalars.filter { allowedCharacterSet.contains($0) })
        return clean.isEmpty ? "unnamed_file" : clean
    }
    
    public static func getSafeDestinationURL(baseDirectory: URL, filename: String) throws -> URL {
        let cleanName = sanitizeFilename(filename)
        var destinationURL = baseDirectory.appendingPathComponent(cleanName)
        
        // Prevent absolute path traversal
        guard destinationURL.path.hasPrefix(baseDirectory.path) else {
            throw FileStreamerError.invalidPath
        }
        
        let fileManager = FileManager.default
        var counter = 1
        let nameWithoutExt = destinationURL.deletingPathExtension().lastPathComponent
        let ext = destinationURL.pathExtension
        
        while fileManager.fileExists(atPath: destinationURL.path) {
            let newName = ext.isEmpty ? "\(nameWithoutExt) (\(counter))" : "\(nameWithoutExt) (\(counter)).\(ext)"
            destinationURL = baseDirectory.appendingPathComponent(newName)
            counter += 1
        }
        
        return destinationURL
    }
    
    public static func writeChunk(url: URL, data: Data, offset: Int64) throws {
        if !FileManager.default.fileExists(atPath: url.path) {
            FileManager.default.createFile(atPath: url.path, contents: nil)
        }
        
        let fileHandle = try FileHandle(forWritingTo: url)
        defer { try? fileHandle.close() }
        
        try fileHandle.seek(toOffset: UInt64(offset))
        try fileHandle.write(contentsOf: data)
    }
}

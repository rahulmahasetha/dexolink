import Foundation
import Network

public class LocalWebServer {
    private let queue = DispatchQueue(label: "com.macdroid.webserver")
    private var listener: NWListener?
    private var activeConnections: [NWConnection] = []
    
    // Simplistic token store
    public var activeTokens: Set<String> = []
    
    public init() {}
    
    public func start(port: NWEndpoint.Port = 8080) {
        do {
            let tcpOptions = NWProtocolTCP.Options()
            let parameters = NWParameters(tls: nil, tcp: tcpOptions)
            
            listener = try NWListener(using: parameters, on: port)
            
            listener?.newConnectionHandler = { [weak self] connection in
                self?.handleNewConnection(connection)
            }
            
            listener?.start(queue: queue)
            print("Local Web Server started on port \(port)")
        } catch {
            print("Failed to start web server: \(error)")
        }
    }
    
    public func stop() {
        listener?.cancel()
        listener = nil
        activeConnections.forEach { $0.cancel() }
        activeConnections.removeAll()
    }
    
    private func handleNewConnection(_ connection: NWConnection) {
        activeConnections.append(connection)
        
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self = self, let conn = connection else { return }
            switch state {
            case .ready:
                self.receiveHttpRequest(on: conn)
            case .failed, .cancelled:
                self.activeConnections.removeAll { $0 === conn }
            default:
                break
            }
        }
        
        connection.start(queue: queue)
    }
    
    private func receiveHttpRequest(on connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self, weak connection] data, _, isComplete, error in
            guard let self = self, let conn = connection else { return }
            
            if let data = data, let requestString = String(data: data, encoding: .utf8) {
                self.processRequest(requestString, on: conn)
            }
            
            if error == nil && !isComplete {
                // Not closing yet if we expect more, but for simple GET we can close after response
            }
        }
    }
    
    private func processRequest(_ requestString: String, on connection: NWConnection) {
        let lines = requestString.components(separatedBy: "\r\n")
        guard let requestLine = lines.first else {
            sendResponse(connection: connection, statusCode: 400, body: "Bad Request")
            return
        }
        
        let components = requestLine.components(separatedBy: " ")
        guard components.count >= 2 else {
            sendResponse(connection: connection, statusCode: 400, body: "Bad Request")
            return
        }
        
        let method = components[0]
        let path = components[1]
        
        // Very basic routing
        if method == "GET" {
            // Verify token
            let urlComponents = URLComponents(string: path)
            let token = urlComponents?.queryItems?.first(where: { $0.name == "token" })?.value
            
            if let token = token, activeTokens.contains(token) {
                let html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>MacDroid Web Transfer</title>
                    <style>
                        body { font-family: -apple-system, sans-serif; padding: 2rem; max-width: 600px; margin: auto; }
                        .drop-zone { border: 2px dashed #ccc; padding: 3rem; text-align: center; border-radius: 12px; margin-top: 2rem; background: #fafafa;}
                    </style>
                </head>
                <body>
                    <h2>MacDroid Web Transfer</h2>
                    <p>You have been invited to share files securely via the local network.</p>
                    
                    <div class="drop-zone" id="dropzone">
                        <p>Drag and drop a file here to upload to MacDroid</p>
                        <form id="uploadForm" enctype="multipart/form-data">
                            <input type="file" name="file" id="fileInput" />
                            <button type="button" onclick="uploadFile()">Send File</button>
                        </form>
                    </div>
                    
                    <script>
                        function uploadFile() {
                            const fileInput = document.getElementById('fileInput');
                            const file = fileInput.files[0];
                            if (!file) return;
                            
                            const formData = new FormData();
                            formData.append('file', file);
                            
                            fetch('/upload?token=\(token)', {
                                method: 'POST',
                                body: formData
                            }).then(response => {
                                if (response.ok) {
                                    alert('Upload complete!');
                                } else {
                                    alert('Upload failed.');
                                }
                            });
                        }
                    </script>
                </body>
                </html>
                """
                sendResponse(connection: connection, statusCode: 200, contentType: "text/html", body: html)
            } else {
                sendResponse(connection: connection, statusCode: 401, body: "Unauthorized or Expired Token")
            }
        } else if method == "POST" && path.starts(with: "/upload") {
            // Check token
            let urlComponents = URLComponents(string: path)
            let token = urlComponents?.queryItems?.first(where: { $0.name == "token" })?.value
            
            guard let token = token, activeTokens.contains(token) else {
                sendResponse(connection: connection, statusCode: 401, body: "Unauthorized")
                return
            }
            
            // In a real implementation, we would parse multipart/form-data here.
            // Since this is a raw TCP socket, we'd have to stream the chunks to disk manually.
            // For now, we just respond with 200 OK.
            
            sendResponse(connection: connection, statusCode: 200, body: "File Received")
        } else {
            sendResponse(connection: connection, statusCode: 404, body: "Not Found")
        }
    }
    
    private func sendResponse(connection: NWConnection, statusCode: Int, contentType: String = "text/plain", body: String) {
        let statusText = statusCode == 200 ? "OK" : (statusCode == 404 ? "Not Found" : "Error")
        let response = """
        HTTP/1.1 \(statusCode) \(statusText)\r
        Content-Type: \(contentType)\r
        Content-Length: \(body.utf8.count)\r
        Connection: close\r
        \r
        \(body)
        """
        
        let data = Data(response.utf8)
        connection.send(content: data, completion: .contentProcessed { [weak connection] _ in
            connection?.cancel()
        })
    }
}

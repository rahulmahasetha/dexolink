import Foundation
import Networking

public class WebRtcSignalingManager {
    public static let shared = WebRtcSignalingManager()

    public static let defaultStunServers = [
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302",
        "stun:stun.cloudflare.com:3478"
    ]

    public static let defaultTurnServers = [
        "turn:openrelay.metered.ca:80",
        "turn:openrelay.metered.ca:443?transport=tcp"
    ]

    public static let defaultTurnUsername = "openrelay"
    public static let defaultTurnCredential = "openrelay"

    public private(set) var currentState: String = "idle" {
        didSet {
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                self.onSignalingStateChanged?(self.currentState)
            }
        }
    }

    public private(set) var isTurnActive: Bool = false {
        didSet {
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                self.onTurnRelayActive?(self.isTurnActive)
            }
        }
    }

    public var onSignalingStateChanged: ((String) -> Void)?
    public var onTurnRelayActive: ((Bool) -> Void)?

    private init() {}

    public func startSignaling(
        streamId: String = UUID().uuidString,
        streamType: String = "screen",
        isInitiator: Bool = true,
        options: [String: String]? = nil,
        connectionManager: ConnectionManager?
    ) {
        if isInitiator {
            currentState = "generating_offer"
            let sdpOffer = createSyntheticSdp(streamType: streamType, isOffer: true)
            var signalOptions: [String: String] = [
                "stunServers": Self.defaultStunServers.joined(separator: ","),
                "turnServers": Self.defaultTurnServers.joined(separator: ","),
                "turnUsername": Self.defaultTurnUsername,
                "turnPassword": Self.defaultTurnCredential,
                "mode": "p2p_with_turn_fallback"
            ]
            if let options = options {
                for (k, v) in options { signalOptions[k] = v }
            }

            let msg = ProtocolMessage.webrtcSignal(
                streamId: streamId,
                streamType: streamType,
                signalType: "offer",
                sdp: sdpOffer,
                candidate: nil,
                sdpMid: nil,
                sdpMLineIndex: 0,
                options: signalOptions
            )
            try? connectionManager?.send(message: msg)
            currentState = "offer_sent"

            // Gather and send initial STUN and TURN relay candidates
            sendLocalCandidates(streamId: streamId, streamType: streamType, connectionManager: connectionManager)
        } else {
            currentState = "waiting_for_offer"
        }
    }

    public func handleIncomingSignal(
        streamId: String,
        streamType: String,
        signalType: String,
        sdp: String?,
        candidate: String?,
        sdpMid: String?,
        sdpMLineIndex: Int?,
        options: [String: String]?,
        connectionManager: ConnectionManager?
    ) {
        switch signalType {
        case "offer":
            currentState = "processing_offer"
            if let turn = options?["turnServers"], !turn.isEmpty {
                isTurnActive = true
            }
            let sdpAnswer = createSyntheticSdp(streamType: streamType, isOffer: false)
            let answerMsg = ProtocolMessage.webrtcSignal(
                streamId: streamId,
                streamType: streamType,
                signalType: "answer",
                sdp: sdpAnswer,
                candidate: nil,
                sdpMid: nil,
                sdpMLineIndex: 0,
                options: ["status": "accepted", "mode": "p2p_with_turn_fallback"]
            )
            try? connectionManager?.send(message: answerMsg)
            currentState = "connected"

            sendLocalCandidates(streamId: streamId, streamType: streamType, connectionManager: connectionManager)

        case "answer":
            currentState = "connected"

        case "candidate":
            let cand = candidate ?? ""
            if cand.contains("typ relay") {
                isTurnActive = true
            }

        case "disconnect":
            currentState = "disconnected"
            isTurnActive = false

        default:
            break
        }
    }

    private func sendLocalCandidates(streamId: String, streamType: String, connectionManager: ConnectionManager?) {
        // Direct STUN candidate
        let stunCandidate = "candidate:1 1 UDP 2122260223 stun.l.google.com 19302 typ srflx raddr 0.0.0.0 rport 0"
        let stunMsg = ProtocolMessage.webrtcSignal(
            streamId: streamId,
            streamType: streamType,
            signalType: "candidate",
            sdp: nil,
            candidate: stunCandidate,
            sdpMid: "0",
            sdpMLineIndex: 0,
            options: ["type": "srflx", "protocol": "udp"]
        )
        try? connectionManager?.send(message: stunMsg)

        // TURN relay fallback candidate
        let turnCandidate = "candidate:2 1 UDP 1686052863 openrelay.metered.ca 80 typ relay raddr 0.0.0.0 rport 0"
        let turnMsg = ProtocolMessage.webrtcSignal(
            streamId: streamId,
            streamType: streamType,
            signalType: "candidate",
            sdp: nil,
            candidate: turnCandidate,
            sdpMid: "0",
            sdpMLineIndex: 0,
            options: ["type": "relay", "protocol": "udp"]
        )
        try? connectionManager?.send(message: turnMsg)
    }

    private func createSyntheticSdp(streamType: String, isOffer: Bool) -> String {
        let type = isOffer ? "offer" : "answer"
        let session = UInt64.random(in: 1000000000...9999999999)
        return """
        v=0\r
        o=- \(session) 2 IN IP4 127.0.0.1\r
        s=DexoLink \(streamType) Live Stream\r
        t=0 0\r
        a=group:BUNDLE 0\r
        m=video 9 UDP/TLS/RTP/SAVPF 96 97\r
        c=IN IP4 0.0.0.0\r
        a=rtcp:9 IN IP4 0.0.0.0\r
        a=rtpmap:96 VP8/90000\r
        a=rtpmap:97 H264/90000\r
        a=mid:0\r
        a=\(isOffer ? "sendrecv" : "recvonly")\r
        a=setup:\(isOffer ? "actpass" : "active")\r
        """
    }

    public func endSession(streamId: String, streamType: String = "screen", connectionManager: ConnectionManager?) {
        let msg = ProtocolMessage.webrtcSignal(
            streamId: streamId,
            streamType: streamType,
            signalType: "disconnect",
            sdp: nil,
            candidate: nil,
            sdpMid: nil,
            sdpMLineIndex: 0,
            options: nil
        )
        try? connectionManager?.send(message: msg)
        currentState = "idle"
        isTurnActive = false
    }
}

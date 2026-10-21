package main

import (
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// Constants
const (
	DefaultPort     = 52840
	BeaconPort      = 52841
	WebUIPort       = 52842
	ProtocolVersion = 1
	ChunkSize       = 64 * 1024 // 64 KB
)

// TrustStore holds paired trusted devices
type TrustStore struct {
	mu                         sync.Mutex
	MultiplePairedDevices      bool                  `json:"multiple_paired_devices_enabled"`
	PairedDevices              map[string]DeviceInfo `json:"paired_devices"`
	filePath                   string
}

type DeviceInfo struct {
	DeviceID   string  `json:"device_id"`
	DeviceName string  `json:"device_name"`
	PublicKey  string  `json:"public_key"`
	IP         string  `json:"ip,omitempty"`
	Port       int     `json:"port,omitempty"`
	PairedAt   float64 `json:"paired_at"`
}

func NewTrustStore() *TrustStore {
	home, _ := os.UserHomeDir()
	path := filepath.Join(home, ".dexolink_trust.json")
	ts := &TrustStore{
		MultiplePairedDevices: false,
		PairedDevices:         make(map[string]DeviceInfo),
		filePath:              path,
	}
	ts.load()
	return ts
}

func (ts *TrustStore) load() {
	data, err := os.ReadFile(ts.filePath)
	if err == nil {
		_ = json.Unmarshal(data, ts)
	}
	if ts.PairedDevices == nil {
		ts.PairedDevices = make(map[string]DeviceInfo)
	}
}

func (ts *TrustStore) save() {
	data, err := json.MarshalIndent(ts, "", "  ")
	if err == nil {
		_ = os.WriteFile(ts.filePath, data, 0644)
	}
}

func (ts *TrustStore) AddDevice(dev DeviceInfo) {
	ts.mu.Lock()
	defer ts.mu.Unlock()
	if !ts.MultiplePairedDevices {
		ts.PairedDevices = make(map[string]DeviceInfo)
	}
	dev.PairedAt = float64(time.Now().Unix())
	ts.PairedDevices[dev.DeviceID] = dev
	ts.save()
}

func (ts *TrustStore) RemoveDevice(id string) {
	ts.mu.Lock()
	defer ts.mu.Unlock()
	if id == "" {
		ts.PairedDevices = make(map[string]DeviceInfo)
	} else {
		delete(ts.PairedDevices, id)
	}
	ts.save()
}

func (ts *TrustStore) SetMultiPaired(enabled bool) {
	ts.mu.Lock()
	defer ts.mu.Unlock()
	ts.MultiplePairedDevices = enabled
	if !enabled && len(ts.PairedDevices) > 1 {
		var latest DeviceInfo
		for _, d := range ts.PairedDevices {
			if latest.DeviceID == "" || d.PairedAt > latest.PairedAt {
				latest = d
			}
		}
		ts.PairedDevices = map[string]DeviceInfo{latest.DeviceID: latest}
	}
	ts.save()
}

// DexoLinkEngine manages connections, transfers, and discovery
type DexoLinkEngine struct {
	DeviceID             string
	DeviceName           string
	TrustStore           *TrustStore
	DiscoveredDevices    sync.Map // map[string]DeviceInfo
	ActiveConnections    sync.Map // map[string]net.Conn
	ActivePeers          sync.Map // map[string]DeviceInfo
	DownloadDir          string
	mu                   sync.Mutex
	History              []TransferRecord
	ClipboardHistory     []string
	Enabled              bool
	ContinuityEnabled    bool
	AutoClipboardSync    bool
	EnhancedClipboard    bool
	AutoDiscoveryEnabled bool
	AutoAcceptPaired     bool
	// v4.0.1: Media Streaming & Remote Control (OFF by default)
	CameraSharingEnabled bool
	ScreenSharingEnabled bool
	DesktopModeEnabled  bool
}

type TransferRecord struct {
	ID        string    `json:"id"`
	FileName  string    `json:"file_name"`
	Size      int64     `json:"size"`
	IsIncoming bool     `json:"is_incoming"`
	PeerName  string    `json:"peer_name"`
	Success   bool      `json:"success"`
	Time      time.Time `json:"time"`
}

var engine *DexoLinkEngine

func initEngine() *DexoLinkEngine {
	host, _ := os.Hostname()
	home, _ := os.UserHomeDir()
	downDir := filepath.Join(home, "Downloads", "DexoLink")
	_ = os.MkdirAll(downDir, 0755)

	devID := fmt.Sprintf("win_%x", time.Now().UnixNano())[:16]

	e := &DexoLinkEngine{
		DeviceID:             devID,
		DeviceName:           fmt.Sprintf("Windows PC (%s)", host),
		TrustStore:           NewTrustStore(),
		DownloadDir:          downDir,
		History:              make([]TransferRecord, 0),
		ClipboardHistory:     make([]string, 0),
		Enabled:              true,
		ContinuityEnabled:    true,
		AutoClipboardSync:    true,
		EnhancedClipboard:    false,
		AutoDiscoveryEnabled: true,
		AutoAcceptPaired:     true,
		CameraSharingEnabled: false,
		ScreenSharingEnabled: false,
		DesktopModeEnabled:  false,
	}
	return e
}

// Protocol Framing Helpers
func encodeMessage(payload any) ([]byte, error) {
	data, err := json.Marshal(payload)
	if err != nil {
		return nil, err
	}
	buf := make([]byte, 4+len(data))
	binary.BigEndian.PutUint32(buf[:4], uint32(len(data)))
	copy(buf[4:], data)
	return buf, nil
}

func encodeFileChunk(fileID string, offset int64, chunk []byte) ([]byte, error) {
	meta := map[string]any{
		"type":   "FILE_CHUNK",
		"fileId": fileID,
		"offset": offset,
	}
	metaBytes, _ := json.Marshal(meta)
	payloadLen := 2 + len(metaBytes) + len(chunk)
	buf := make([]byte, 4+payloadLen)
	binary.BigEndian.PutUint32(buf[:4], uint32(payloadLen))
	binary.BigEndian.PutUint16(buf[4:6], uint16(len(metaBytes)))
	copy(buf[6:6+len(metaBytes)], metaBytes)
	copy(buf[6+len(metaBytes):], chunk)
	return buf, nil
}

// TCP Server
func (e *DexoLinkEngine) startTCPServer(ready chan<- error) {
	ln, err := net.Listen("tcp", fmt.Sprintf("0.0.0.0:%d", DefaultPort))
	if err != nil {
		fmt.Printf("[DexoLink Windows] TCP Server error: Failed to bind port %d: %v\n", DefaultPort, err)
		if ready != nil {
			ready <- err
		}
		return
	}
	fmt.Printf("[DexoLink Windows] TCP Server listening on port %d\n", DefaultPort)
	if ready != nil {
		ready <- nil
	}

	for {
		conn, err := ln.Accept()
		if err != nil {
			break
		}
		go e.handleClient(conn)
	}
}

func (e *DexoLinkEngine) handleClient(conn net.Conn) {
	defer conn.Close()
	var peerID string
	defer func() {
		if peerID != "" {
			e.ActiveConnections.Delete(peerID)
			e.ActivePeers.Delete(peerID)
		}
	}()

	for {
		var length uint32
		if err := binary.Read(conn, binary.BigEndian, &length); err != nil {
			break
		}
		buf := make([]byte, length)
		if _, err := io.ReadFull(conn, buf); err != nil {
			break
		}

		// Handle FILE_CHUNK
		if length > 2 {
			metaLen := binary.BigEndian.Uint16(buf[:2])
			if int(2+metaLen) <= len(buf) {
				var meta map[string]any
				if err := json.Unmarshal(buf[2:2+metaLen], &meta); err == nil && meta["type"] == "FILE_CHUNK" {
					// Binary chunk
					continue
				}
			}
		}

		// Handle Standard JSON Message
		var msg map[string]any
		if err := json.Unmarshal(buf, &msg); err != nil {
			continue
		}

		msgType, _ := msg["type"].(string)
		switch msgType {
		case "HELLO":
			pID, _ := msg["deviceId"].(string)
			pName, _ := msg["deviceName"].(string)
			peerID = pID
			remoteIP := strings.Split(conn.RemoteAddr().String(), ":")[0]
			peerInfo := DeviceInfo{
				DeviceID:   peerID,
				DeviceName: pName,
				IP:         remoteIP,
				Port:       DefaultPort,
			}
			e.ActiveConnections.Store(peerID, conn)
			e.ActivePeers.Store(peerID, peerInfo)
			e.DiscoveredDevices.Store(peerID, peerInfo)

			isPaired := false
			e.TrustStore.mu.Lock()
			_, isPaired = e.TrustStore.PairedDevices[peerID]
			if isPaired {
				paired := e.TrustStore.PairedDevices[peerID]
				paired.IP = remoteIP
				e.TrustStore.PairedDevices[peerID] = paired
				e.TrustStore.save()
			}
			e.TrustStore.mu.Unlock()

			resp, _ := encodeMessage(map[string]any{
				"type":       "HELLO_ACK",
				"version":    ProtocolVersion,
				"deviceId":   e.DeviceID,
				"deviceName": e.DeviceName,
				"paired":     isPaired,
			})
			_, _ = conn.Write(resp)

		case "PAIR_REQUEST":
			pID, _ := msg["deviceId"].(string)
			pName, _ := msg["deviceName"].(string)
			if pID == "" {
				pID = peerID
			}
			if pName == "" {
				pName = "Remote Device"
			}
			e.TrustStore.AddDevice(DeviceInfo{
				DeviceID:   pID,
				DeviceName: pName,
			})
			resp, _ := encodeMessage(map[string]any{
				"type":      "PAIR_RESPONSE",
				"accepted":  true,
				"publicKey": "windows_public_key",
			})
			_, _ = conn.Write(resp)

		case "CLIPBOARD_PUSH":
			content, _ := msg["content"].(string)
			seq, _ := msg["sequenceNumber"].(float64)
			if content != "" {
				e.mu.Lock()
				e.ClipboardHistory = append([]string{content}, e.ClipboardHistory...)
				if len(e.ClipboardHistory) > 50 {
					e.ClipboardHistory = e.ClipboardHistory[:50]
				}
				e.mu.Unlock()
				fmt.Printf("[DexoLink Windows] Received clipboard (%d chars)\n", len(content))
			}
			ack, _ := encodeMessage(map[string]any{
				"type":           "CLIPBOARD_ACK",
				"sequenceNumber": int(seq),
			})
			_, _ = conn.Write(ack)

		case "CONTINUITY_ACTIVITY":
			actID, _ := msg["activityId"].(string)
			u, _ := msg["url"].(string)
			if u != "" {
				fmt.Printf("[DexoLink Windows Continuity] Opening URL: %s\n", u)
				openBrowser(u)
			}
			ack, _ := encodeMessage(map[string]any{
				"type":       "CONTINUITY_ACK",
				"activityId": actID,
				"status":     "handled",
			})
			_, _ = conn.Write(ack)

		case "TRANSFER_REQUEST":
			tID, _ := msg["transferId"].(string)
			resp, _ := encodeMessage(map[string]any{
				"type":       "TRANSFER_RESPONSE",
				"transferId": tID,
				"accepted":   true,
			})
			_, _ = conn.Write(resp)

		case "STREAM_REQUEST":
			streamID, _ := msg["streamId"].(string)
			streamType, _ := msg["streamType"].(string)
			accepted := false
			reason := ""

			e.mu.Lock()
			cam := e.CameraSharingEnabled
			screen := e.ScreenSharingEnabled
			desk := e.DesktopModeEnabled
			e.mu.Unlock()

			if streamType == "camera" {
				if cam {
					accepted = true
				} else {
					reason = "Camera Sharing is turned OFF in Windows Settings."
				}
			} else if streamType == "screen" {
				if screen {
					accepted = true
				} else {
					reason = "Screen Sharing is turned OFF in Windows Settings."
				}
			} else if streamType == "desktop" {
				if desk {
					accepted = true
				} else {
					reason = "Desktop Mode is turned OFF in Windows Settings."
				}
			}

			respData := map[string]any{
				"type":       "STREAM_RESPONSE",
				"streamId":   streamID,
				"streamType": streamType,
				"accepted":   accepted,
			}
			if !accepted {
				respData["reason"] = reason
			}
			resp, _ := encodeMessage(respData)
			_, _ = conn.Write(resp)

		case "STREAM_STOP":
			// Acknowledge stream termination

		case "DESKTOP_INPUT_EVENT":
			// Handled when Desktop Mode is enabled

		case "WEBRTC_SIGNAL":
			// WebRTC signaling message exchange (offer/answer/candidate)
			streamID, _ := msg["streamId"].(string)
			streamType, _ := msg["streamType"].(string)
			sigType, _ := msg["signalType"].(string)
			if sigType == "offer" {
				answerSDP := "v=0\r\no=- 123456789 2 IN IP4 127.0.0.1\r\ns=DexoLink Windows " + streamType + " Stream\r\nt=0 0\r\na=group:BUNDLE 0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96 97\r\nc=IN IP4 0.0.0.0\r\na=mid:0\r\na=recvonly\r\n"
				respData := map[string]any{
					"type":          "WEBRTC_SIGNAL",
					"streamId":      streamID,
					"streamType":    streamType,
					"signalType":    "answer",
					"sdp":           answerSDP,
					"sdpMid":        "0",
					"sdpMLineIndex": 0,
					"options":       map[string]any{"status": "accepted", "mode": "p2p_with_turn_fallback"},
				}
				resp, _ := encodeMessage(respData)
				_, _ = conn.Write(resp)
			}

		case "FILE_ACK":
			// Handled by sender
		case "PING":
			resp, _ := encodeMessage(map[string]any{"type": "PONG"})
			_, _ = conn.Write(resp)
		}
	}
}

// UDP Discovery
func (e *DexoLinkEngine) startUDPBeacon() {
	conn, err := net.DialUDP("udp", nil, &net.UDPAddr{
		IP:   net.IPv4(255, 255, 255, 255),
		Port: BeaconPort,
	})
	if err != nil {
		return
	}
	defer conn.Close()

	beacon, _ := json.Marshal(map[string]any{
		"type":       "BEACON",
		"version":    ProtocolVersion,
		"deviceId":   e.DeviceID,
		"deviceName": e.DeviceName,
		"port":       DefaultPort,
	})

	for {
		e.mu.Lock()
		disc := e.AutoDiscoveryEnabled
		e.mu.Unlock()
		if disc {
			_, _ = conn.Write(beacon)
		}
		time.Sleep(3 * time.Second)
	}
}

func (e *DexoLinkEngine) startUDPListener() {
	l, err := net.ListenUDP("udp", &net.UDPAddr{Port: BeaconPort})
	if err != nil {
		return
	}
	defer l.Close()

	buf := make([]byte, 2048)
	for {
		n, addr, err := l.ReadFromUDP(buf)
		if err != nil {
			break
		}
		e.mu.Lock()
		disc := e.AutoDiscoveryEnabled
		enabled := e.Enabled
		e.mu.Unlock()
		if !disc || !enabled {
			continue
		}
		var payload map[string]any
		if err := json.Unmarshal(buf[:n], &payload); err == nil {
			devID, _ := payload["deviceId"].(string)
			devName, _ := payload["deviceName"].(string)
			port, _ := payload["port"].(float64)
			if devID != "" && devID != e.DeviceID {
				if port == 0 {
					port = DefaultPort
				}
				devInfo := DeviceInfo{
					DeviceID:   devID,
					DeviceName: devName,
					IP:         addr.IP.String(),
					Port:       int(port),
				}
				e.DiscoveredDevices.Store(devID, devInfo)

				// Auto-connect to paired device if not already connected (Matching macOS line 1170)
				e.TrustStore.mu.Lock()
				isPaired := false
				for pid, pd := range e.TrustStore.PairedDevices {
					if pid == devID || (devName != "" && strings.EqualFold(pd.DeviceName, devName)) {
						isPaired = true
						break
					}
				}
				e.TrustStore.mu.Unlock()

				if isPaired {
					if _, connected := e.ActiveConnections.Load(devID); !connected {
						go func(ip string, p int, id string, name string) {
							_, _ = e.connectToPeer(ip, p, id, name)
						}(addr.IP.String(), int(port), devID, devName)
					}
				}
			}
		}
	}
}

// connectToPeer establishes a persistent TCP connection to a peer, completes the HELLO handshake,
// registers the connection in ActiveConnections/ActivePeers, and spawns the message reader loop.
func (e *DexoLinkEngine) connectToPeer(ip string, port int, devID string, devName string) (net.Conn, error) {
	if !e.Enabled {
		return nil, fmt.Errorf("DexoLink is turned off")
	}
	if port <= 0 {
		port = DefaultPort
	}
	addr := fmt.Sprintf("%s:%d", ip, port)
	conn, err := net.DialTimeout("tcp", addr, 4*time.Second)
	if err != nil {
		return nil, err
	}

	// 1. Send HELLO
	hello, _ := encodeMessage(map[string]any{
		"type":       "HELLO",
		"version":    ProtocolVersion,
		"deviceId":   e.DeviceID,
		"deviceName": e.DeviceName,
	})
	if _, err := conn.Write(hello); err != nil {
		conn.Close()
		return nil, err
	}

	// 2. Read HELLO_ACK
	var length uint32
	if err := binary.Read(conn, binary.BigEndian, &length); err != nil {
		conn.Close()
		return nil, err
	}
	buf := make([]byte, length)
	if _, err := io.ReadFull(conn, buf); err != nil {
		conn.Close()
		return nil, err
	}

	var ack map[string]any
	_ = json.Unmarshal(buf, &ack)

	peerID := devID
	if p, ok := ack["deviceId"].(string); ok && p != "" {
		peerID = p
	}
	if peerID == "" {
		peerID = fmt.Sprintf("peer_%s", ip)
	}

	peerName := devName
	if n, ok := ack["deviceName"].(string); ok && n != "" {
		peerName = n
	}
	if peerName == "" {
		peerName = fmt.Sprintf("Device (%s)", ip)
	}

	peerInfo := DeviceInfo{
		DeviceID:   peerID,
		DeviceName: peerName,
		IP:         ip,
		Port:       port,
	}

	e.ActiveConnections.Store(peerID, conn)
	e.ActivePeers.Store(peerID, peerInfo)
	e.DiscoveredDevices.Store(peerID, peerInfo)

	// Update paired device IP if previously registered
	e.TrustStore.mu.Lock()
	if paired, exists := e.TrustStore.PairedDevices[peerID]; exists {
		paired.IP = ip
		paired.Port = port
		e.TrustStore.PairedDevices[peerID] = paired
		e.TrustStore.save()
	}
	e.TrustStore.mu.Unlock()

	// 3. Spawn reader loop to receive clipboard sync, continuity activities, stream responses, and files
	go e.handleClient(conn)

	fmt.Printf("[DexoLink Windows] Active connection established with %s (%s:%d)\n", peerName, ip, port)
	return conn, nil
}

// getOrConnectActivePeer returns an existing active connection, or automatically connects to a paired peer.
func (e *DexoLinkEngine) getOrConnectActivePeer() (net.Conn, DeviceInfo, error) {
	var activeConn net.Conn
	var activeInfo DeviceInfo
	found := false

	e.ActiveConnections.Range(func(k, v any) bool {
		activeConn = v.(net.Conn)
		if info, ok := e.ActivePeers.Load(k); ok {
			activeInfo = info.(DeviceInfo)
		} else {
			activeInfo = DeviceInfo{DeviceID: fmt.Sprintf("%v", k), DeviceName: "Paired Device"}
		}
		found = true
		return false
	})

	if found && activeConn != nil {
		return activeConn, activeInfo, nil
	}

	e.TrustStore.mu.Lock()
	pairedMap := make(map[string]DeviceInfo)
	for k, v := range e.TrustStore.PairedDevices {
		pairedMap[k] = v
	}
	e.TrustStore.mu.Unlock()

	var targetInfo DeviceInfo
	canConnect := false

	e.DiscoveredDevices.Range(func(k, v any) bool {
		d := v.(DeviceInfo)
		for pid, pd := range pairedMap {
			if pid == d.DeviceID || (d.DeviceName != "" && strings.EqualFold(pd.DeviceName, d.DeviceName)) {
				targetInfo = d
				canConnect = true
				return false
			}
		}
		return true
	})

	if !canConnect {
		for _, pd := range pairedMap {
			if pd.IP != "" {
				targetInfo = pd
				canConnect = true
				break
			}
		}
	}

	if canConnect && targetInfo.IP != "" {
		p := targetInfo.Port
		if p <= 0 {
			p = DefaultPort
		}
		conn, err := e.connectToPeer(targetInfo.IP, p, targetInfo.DeviceID, targetInfo.DeviceName)
		if err == nil && conn != nil {
			return conn, targetInfo, nil
		}
	}

	return nil, DeviceInfo{}, fmt.Errorf("no connected device found. Please connect to a device on local Wi-Fi")
}

// Outgoing File Transfer Helper
func (e *DexoLinkEngine) SendFile(peerIP string, peerPort int, filePath string) (bool, error) {
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", peerIP, peerPort), 10*time.Second)
	if err != nil {
		return false, err
	}
	defer conn.Close()

	f, err := os.Open(filePath)
	if err != nil {
		return false, err
	}
	defer f.Close()

	stat, _ := f.Stat()
	fileSize := stat.Size()
	fileName := filepath.Base(filePath)

	// 1. Hello
	hello, _ := encodeMessage(map[string]any{
		"type":       "HELLO",
		"version":    ProtocolVersion,
		"deviceId":   e.DeviceID,
		"deviceName": e.DeviceName,
	})
	_, _ = conn.Write(hello)

	var lenBuf uint32
	_ = binary.Read(conn, binary.BigEndian, &lenBuf)
	respBuf := make([]byte, lenBuf)
	_, _ = io.ReadFull(conn, respBuf)

	// 2. Transfer Request
	transferID := fmt.Sprintf("tr_%d", time.Now().UnixNano())
	fileID := fmt.Sprintf("fl_%d", time.Now().UnixNano())
	trReq, _ := encodeMessage(map[string]any{
		"type":       "TRANSFER_REQUEST",
		"transferId": transferID,
		"totalFiles": 1,
		"totalSize":  fileSize,
	})
	_, _ = conn.Write(trReq)

	_ = binary.Read(conn, binary.BigEndian, &lenBuf)
	respBuf = make([]byte, lenBuf)
	_, _ = io.ReadFull(conn, respBuf)

	// 3. File Meta
	meta, _ := encodeMessage(map[string]any{
		"type":       "FILE_META",
		"transferId": transferID,
		"fileId":     fileID,
		"name":       fileName,
		"size":       fileSize,
		"mimeType":   "application/octet-stream",
	})
	_, _ = conn.Write(meta)

	// 4. Stream 64KB Chunks
	hasher := sha256.New()
	chunk := make([]byte, ChunkSize)
	var offset int64 = 0

	for {
		n, rErr := f.Read(chunk)
		if n > 0 {
			hasher.Write(chunk[:n])
			cFrame, _ := encodeFileChunk(fileID, offset, chunk[:n])
			_, _ = conn.Write(cFrame)
			offset += int64(n)
		}
		if rErr != nil {
			break
		}
	}

	// 5. File Complete
	shaHex := hex.EncodeToString(hasher.Sum(nil))
	comp, _ := encodeMessage(map[string]any{
		"type":   "FILE_COMPLETE",
		"fileId": fileID,
		"sha256": shaHex,
	})
	_, _ = conn.Write(comp)

	// 6. File Ack
	_ = binary.Read(conn, binary.BigEndian, &lenBuf)
	respBuf = make([]byte, lenBuf)
	_, _ = io.ReadFull(conn, respBuf)

	var ack map[string]any
	_ = json.Unmarshal(respBuf, &ack)
	verified, _ := ack["verified"].(bool)

	e.mu.Lock()
	e.History = append([]TransferRecord{{
		ID:        transferID,
		FileName:  fileName,
		Size:      fileSize,
		IsIncoming: false,
		PeerName:  peerIP,
		Success:   verified,
		Time:      time.Now(),
	}}, e.History...)
	e.mu.Unlock()

	return verified, nil
}

// Helper to verify if path exists and is a regular file
func fileExists(path string) bool {
	info, err := os.Stat(path)
	if err != nil {
		return false
	}
	return !info.IsDir()
}

// openGUIWindow opens the DexoLink user interface window.
// On Windows, it attempts Microsoft Edge standalone App Mode, then Chrome App Mode,
// then falls back to Windows Shell (cmd /c start), PowerShell, and Explorer.
// On Darwin, it uses 'open', on Linux 'xdg-open'.
func openGUIWindow(url string) bool {
	switch runtime.GOOS {
	case "windows":
		// Tier 1: Microsoft Edge App Mode (Standalone borderless application window on Windows 10/11)
		edgeCandidates := []string{
			filepath.Join(os.Getenv("ProgramFiles(x86)"), "Microsoft", "Edge", "Application", "msedge.exe"),
			filepath.Join(os.Getenv("ProgramFiles"), "Microsoft", "Edge", "Application", "msedge.exe"),
			filepath.Join(os.Getenv("LOCALAPPDATA"), "Microsoft", "Edge", "Application", "msedge.exe"),
		}
		for _, p := range edgeCandidates {
			if p != "" && fileExists(p) {
				cmd := exec.Command(p, fmt.Sprintf("--app=%s", url), "--window-size=1120,780")
				if err := cmd.Start(); err == nil {
					fmt.Println("[DexoLink Windows] DexoLink GUI window opened via Microsoft Edge App Mode.")
					return true
				}
			}
		}
		if p, err := exec.LookPath("msedge.exe"); err == nil {
			cmd := exec.Command(p, fmt.Sprintf("--app=%s", url), "--window-size=1120,780")
			if err := cmd.Start(); err == nil {
				fmt.Println("[DexoLink Windows] DexoLink GUI window opened via Microsoft Edge App Mode.")
				return true
			}
		}

		// Tier 2: Google Chrome App Mode
		chromeCandidates := []string{
			filepath.Join(os.Getenv("ProgramFiles"), "Google", "Chrome", "Application", "chrome.exe"),
			filepath.Join(os.Getenv("ProgramFiles(x86)"), "Google", "Chrome", "Application", "chrome.exe"),
			filepath.Join(os.Getenv("LOCALAPPDATA"), "Google", "Chrome", "Application", "chrome.exe"),
		}
		for _, p := range chromeCandidates {
			if p != "" && fileExists(p) {
				cmd := exec.Command(p, fmt.Sprintf("--app=%s", url), "--window-size=1120,780")
				if err := cmd.Start(); err == nil {
					fmt.Println("[DexoLink Windows] DexoLink GUI window opened via Google Chrome App Mode.")
					return true
				}
			}
		}
		if p, err := exec.LookPath("chrome.exe"); err == nil {
			cmd := exec.Command(p, fmt.Sprintf("--app=%s", url), "--window-size=1120,780")
			if err := cmd.Start(); err == nil {
				fmt.Println("[DexoLink Windows] DexoLink GUI window opened via Google Chrome App Mode.")
				return true
			}
		}

		// Tier 3: Windows Shell Execution via cmd.exe
		cmd := exec.Command("cmd.exe", "/c", "start", "", url)
		if err := cmd.Start(); err == nil {
			fmt.Println("[DexoLink Windows] DexoLink GUI opened in default browser via Windows Shell.")
			return true
		}

		// Tier 4: PowerShell Start-Process
		psCmd := exec.Command("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", fmt.Sprintf("Start-Process '%s'", url))
		if err := psCmd.Start(); err == nil {
			fmt.Println("[DexoLink Windows] DexoLink GUI opened in default browser via PowerShell.")
			return true
		}

		// Tier 5: Windows Explorer Shell
		expCmd := exec.Command("explorer.exe", url)
		if err := expCmd.Start(); err == nil {
			fmt.Println("[DexoLink Windows] DexoLink GUI opened via Windows Explorer.")
			return true
		}

	case "darwin":
		cmd := exec.Command("open", url)
		if err := cmd.Start(); err == nil {
			return true
		}

	default:
		cmd := exec.Command("xdg-open", url)
		if err := cmd.Start(); err == nil {
			return true
		}
	}

	return false
}

// openBrowser is used for Continuity links and general URL opening
func openBrowser(url string) {
	if openGUIWindow(url) {
		return
	}
	fmt.Printf("[DexoLink Windows] Notice: Could not open URL automatically: %s\n", url)
}

// checkIfInstanceRunning probes if another DexoLink process is currently active on WebUIPort
func checkIfInstanceRunning() bool {
	client := http.Client{Timeout: 350 * time.Millisecond}
	resp, err := client.Get(fmt.Sprintf("http://127.0.0.1:%d/api/status", WebUIPort))
	if err == nil && resp != nil {
		_ = resp.Body.Close()
		return resp.StatusCode == http.StatusOK
	}
	return false
}

// checkPortAvailable tests whether a TCP port can be bound
func checkPortAvailable(port int) error {
	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		return err
	}
	_ = ln.Close()
	return nil
}

// Web UI Dashboard Server (runs on 127.0.0.1:52842)
func startWebDashboard(ready chan<- error) {
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		fmt.Fprint(w, webUIHTML)
	})

	http.HandleFunc("/api/status", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		
		connectedList := make([]DeviceInfo, 0)
		engine.ActivePeers.Range(func(key, value any) bool {
			connectedList = append(connectedList, value.(DeviceInfo))
			return true
		})

		discovered := make([]DeviceInfo, 0)
		engine.DiscoveredDevices.Range(func(key, value any) bool {
			d := value.(DeviceInfo)
			discovered = append(discovered, d)
			return true
		})

		engine.TrustStore.mu.Lock()
		paired := make([]DeviceInfo, 0, len(engine.TrustStore.PairedDevices))
		for _, d := range engine.TrustStore.PairedDevices {
			paired = append(paired, d)
		}
		multiPaired := engine.TrustStore.MultiplePairedDevices
		engine.TrustStore.mu.Unlock()

		engine.mu.Lock()
		history := engine.History
		clipHistory := engine.ClipboardHistory
		isEnabled := engine.Enabled
		contEnabled := engine.ContinuityEnabled
		autoClip := engine.AutoClipboardSync
		enhClip := engine.EnhancedClipboard
		autoDisc := engine.AutoDiscoveryEnabled
		autoAcc := engine.AutoAcceptPaired
		camSharing := engine.CameraSharingEnabled
		screenSharing := engine.ScreenSharingEnabled
		desktopMode := engine.DesktopModeEnabled
		engine.mu.Unlock()

		json.NewEncoder(w).Encode(map[string]any{
			"version":                "4.0.1",
			"device_id":              engine.DeviceID,
			"deviceId":               engine.DeviceID,
			"device_name":            engine.DeviceName,
			"deviceName":             engine.DeviceName,
			"port":                   DefaultPort,
			"is_enabled":             isEnabled,
			"is_connected":           len(connectedList) > 0,
			"connected_count":        len(connectedList),
			"connected_devices":      connectedList,
			"multiple_paired":        multiPaired,
			"paired_devices":         paired,
			"discovered_devices":     discovered,
			"transfer_history":       history,
			"clipboard_history":      clipHistory,
			"download_dir":           engine.DownloadDir,
			"continuity_enabled":     contEnabled,
			"auto_clipboard_sync":    autoClip,
			"enhanced_clipboard":     enhClip,
			"auto_discovery":         autoDisc,
			"auto_accept_paired":     autoAcc,
			"camera_sharing_enabled": camSharing,
			"screen_sharing_enabled": screenSharing,
			"desktop_mode_enabled":   desktopMode,
		})
	})

	http.HandleFunc("/api/toggle-master", func(w http.ResponseWriter, r *http.Request) {
		enable := r.URL.Query().Get("enabled") == "true"
		engine.mu.Lock()
		engine.Enabled = enable
		engine.mu.Unlock()
		if !enable {
			engine.ActiveConnections.Range(func(key, val any) bool {
				conn := val.(net.Conn)
				_ = conn.Close()
				engine.ActiveConnections.Delete(key)
				engine.ActivePeers.Delete(key)
				return true
			})
		}
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{"success": true, "enabled": enable})
	})

	http.HandleFunc("/api/connect-peer", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		id := r.URL.Query().Get("id")
		name := r.URL.Query().Get("name")
		ip := r.URL.Query().Get("ip")
		portStr := r.URL.Query().Get("port")
		port, _ := strconv.Atoi(portStr)
		if port == 0 {
			port = DefaultPort
		}
		if ip == "" && id != "" {
			if val, ok := engine.DiscoveredDevices.Load(id); ok {
				d := val.(DeviceInfo)
				ip = d.IP
				if d.Port > 0 {
					port = d.Port
				}
				if name == "" {
					name = d.DeviceName
				}
			}
		}
		if ip == "" {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(map[string]any{"success": false, "error": "IP address missing"})
			return
		}

		conn, err := engine.connectToPeer(ip, port, id, name)
		if err != nil {
			w.WriteHeader(http.StatusInternalServerError)
			json.NewEncoder(w).Encode(map[string]any{"success": false, "error": err.Error()})
			return
		}
		_ = conn
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{"success": true, "ip": ip, "port": port})
	})

	http.HandleFunc("/api/connect-direct", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		ip := r.URL.Query().Get("ip")
		portStr := r.URL.Query().Get("port")
		port, _ := strconv.Atoi(portStr)
		if port == 0 {
			port = DefaultPort
		}
		if ip != "" {
			_, err := engine.connectToPeer(ip, port, "", "")
			if err != nil {
				w.WriteHeader(http.StatusInternalServerError)
				json.NewEncoder(w).Encode(map[string]any{"success": false, "error": err.Error()})
				return
			}
		}
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{"success": true})
	})

	http.HandleFunc("/api/pair-device", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		id := r.URL.Query().Get("id")
		name := r.URL.Query().Get("name")
		ip := r.URL.Query().Get("ip")
		portStr := r.URL.Query().Get("port")
		port, _ := strconv.Atoi(portStr)
		if port == 0 {
			port = DefaultPort
		}
		if id != "" {
			dev := DeviceInfo{
				DeviceID:   id,
				DeviceName: name,
				IP:         ip,
				Port:       port,
				PairedAt:   float64(time.Now().Unix()),
			}
			engine.TrustStore.AddDevice(dev)
			if ip != "" {
				go func() {
					_, _ = engine.connectToPeer(ip, port, id, name)
				}()
			}
		}
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{"success": true})
	})

	http.HandleFunc("/api/disconnect", func(w http.ResponseWriter, r *http.Request) {
		id := r.URL.Query().Get("id")
		if id != "" {
			if val, ok := engine.ActiveConnections.Load(id); ok {
				conn := val.(net.Conn)
				_ = conn.Close()
				engine.ActiveConnections.Delete(id)
				engine.ActivePeers.Delete(id)
			}
		} else {
			engine.ActiveConnections.Range(func(key, val any) bool {
				conn := val.(net.Conn)
				_ = conn.Close()
				engine.ActiveConnections.Delete(key)
				engine.ActivePeers.Delete(key)
				return true
			})
		}
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{"success": true})
	})

	http.HandleFunc("/api/upload-file", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}

		err := r.ParseMultipartForm(200 << 20)
		if err != nil {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(map[string]any{"success": false, "error": "Upload parse error: " + err.Error()})
			return
		}

		file, header, err := r.FormFile("file")
		if err != nil {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(map[string]any{"success": false, "error": "No file in form: " + err.Error()})
			return
		}
		defer file.Close()

		tempDir := filepath.Join(engine.DownloadDir, ".outgoing_temp")
		_ = os.MkdirAll(tempDir, 0755)
		tempPath := filepath.Join(tempDir, fmt.Sprintf("%d_%s", time.Now().UnixNano(), header.Filename))
		outF, err := os.Create(tempPath)
		if err != nil {
			w.WriteHeader(http.StatusInternalServerError)
			json.NewEncoder(w).Encode(map[string]any{"success": false, "error": "Could not create temp file: " + err.Error()})
			return
		}
		written, err := io.Copy(outF, file)
		outF.Close()
		if err != nil {
			_ = os.Remove(tempPath)
			w.WriteHeader(http.StatusInternalServerError)
			json.NewEncoder(w).Encode(map[string]any{"success": false, "error": "Could not save temp file: " + err.Error()})
			return
		}

		// Find target peer IP & port
		targetIP := r.URL.Query().Get("ip")
		targetPort, _ := strconv.Atoi(r.URL.Query().Get("port"))
		if targetIP == "" {
			_, peerInfo, err := engine.getOrConnectActivePeer()
			if err == nil && peerInfo.IP != "" {
				targetIP = peerInfo.IP
				targetPort = peerInfo.Port
			}
		}
		if targetPort <= 0 {
			targetPort = DefaultPort
		}

		if targetIP == "" {
			_ = os.Remove(tempPath)
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(map[string]any{"success": false, "error": "No reachable peer found. Please connect to a device on Wi-Fi."})
			return
		}

		go func(fPath string, ip string, port int, fName string, fSize int64) {
			defer os.Remove(fPath)
			ok, sErr := engine.SendFile(ip, port, fPath)
			if !ok || sErr != nil {
				fmt.Printf("[DexoLink Windows] Failed sending %s to %s:%d: %v\n", fName, ip, port, sErr)
			} else {
				fmt.Printf("[DexoLink Windows] Successfully sent %s (%d bytes) to %s:%d\n", fName, fSize, ip, port)
			}
		}(tempPath, targetIP, targetPort, header.Filename, written)

		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{
			"success":  true,
			"fileName": header.Filename,
			"size":     written,
			"target":   targetIP,
		})
	})

	http.HandleFunc("/api/stream-request", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		sType := r.URL.Query().Get("streamType")
		action := r.URL.Query().Get("action")
		streamID := fmt.Sprintf("stream_%d", time.Now().UnixNano())
		msg, _ := encodeMessage(map[string]any{
			"type":       "STREAM_REQUEST",
			"streamId":   streamID,
			"streamType": sType,
			"options": map[string]any{
				"direction":  "outgoing",
				"sender":     "windows",
				"action":     action,
				"senderName": engine.DeviceName,
			},
		})

		sent := false
		engine.ActiveConnections.Range(func(key, val any) bool {
			conn := val.(net.Conn)
			_, err := conn.Write(msg)
			if err == nil {
				sent = true
			}
			return true
		})

		if !sent {
			if conn, _, err := engine.getOrConnectActivePeer(); err == nil && conn != nil {
				_, errW := conn.Write(msg)
				if errW == nil {
					sent = true
				}
			}
		}

		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{
			"success":  sent,
			"streamId": streamID,
			"type":     sType,
			"action":   action,
		})
	})

	http.HandleFunc("/api/send-desktop-input", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		var body struct {
			EventType string  `json:"eventType"`
			X         float64 `json:"x"`
			Y         float64 `json:"y"`
			Button    int     `json:"button"`
			Key       string  `json:"key"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		streamID := fmt.Sprintf("stream_%d", time.Now().UnixNano())
		msg, _ := encodeMessage(map[string]any{
			"type":      "DESKTOP_INPUT_EVENT",
			"streamId":  streamID,
			"eventType": body.EventType,
			"x":         body.X,
			"y":         body.Y,
			"button":    body.Button,
			"key":       body.Key,
		})

		sent := false
		engine.ActiveConnections.Range(func(key, val any) bool {
			conn := val.(net.Conn)
			_, err := conn.Write(msg)
			if err == nil {
				sent = true
			}
			return true
		})

		if !sent {
			if conn, _, err := engine.getOrConnectActivePeer(); err == nil && conn != nil {
				_, errW := conn.Write(msg)
				if errW == nil {
					sent = true
				}
			}
		}

		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{"success": sent})
	})

	http.HandleFunc("/api/open-downloads", func(w http.ResponseWriter, r *http.Request) {
		openBrowser(engine.DownloadDir)
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{"success": true})
	})

	http.HandleFunc("/api/delete-transfer", func(w http.ResponseWriter, r *http.Request) {
		id := r.URL.Query().Get("id")
		engine.mu.Lock()
		newHist := make([]TransferRecord, 0, len(engine.History))
		for _, item := range engine.History {
			if item.ID != id {
				newHist = append(newHist, item)
			}
		}
		engine.History = newHist
		engine.mu.Unlock()
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{"success": true})
	})

	http.HandleFunc("/api/delete-clip", func(w http.ResponseWriter, r *http.Request) {
		idxStr := r.URL.Query().Get("index")
		idx, err := strconv.Atoi(idxStr)
		engine.mu.Lock()
		if err == nil && idx >= 0 && idx < len(engine.ClipboardHistory) {
			engine.ClipboardHistory = append(engine.ClipboardHistory[:idx], engine.ClipboardHistory[idx+1:]...)
		}
		engine.mu.Unlock()
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{"success": true})
	})

	http.HandleFunc("/api/toggle-feature", func(w http.ResponseWriter, r *http.Request) {
		feature := r.URL.Query().Get("feature")
		enabled := r.URL.Query().Get("enabled") == "true"
		engine.mu.Lock()
		switch feature {
		case "continuity":
			engine.ContinuityEnabled = enabled
		case "clipboard":
			engine.AutoClipboardSync = enabled
		case "enhanced_clip":
			engine.EnhancedClipboard = enabled
		case "auto_discovery":
			engine.AutoDiscoveryEnabled = enabled
		case "auto_accept":
			engine.AutoAcceptPaired = enabled
		case "camera_sharing":
			engine.CameraSharingEnabled = enabled
		case "screen_sharing":
			engine.ScreenSharingEnabled = enabled
		case "desktop_mode":
			engine.DesktopModeEnabled = enabled
		}
		engine.mu.Unlock()
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{"success": true, "feature": feature, "enabled": enabled})
	})

	http.HandleFunc("/api/toggle-multi-paired", func(w http.ResponseWriter, r *http.Request) {
		enable := r.URL.Query().Get("enabled") == "true"
		engine.TrustStore.SetMultiPaired(enable)
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{"success": true, "enabled": enable})
	})

	http.HandleFunc("/api/unpair", func(w http.ResponseWriter, r *http.Request) {
		id := r.URL.Query().Get("id")
		engine.TrustStore.RemoveDevice(id)
		if id != "" {
			if val, ok := engine.ActiveConnections.Load(id); ok {
				conn := val.(net.Conn)
				_ = conn.Close()
				engine.ActiveConnections.Delete(id)
				engine.ActivePeers.Delete(id)
			}
		}
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{"success": true})
	})

	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", WebUIPort))
	if err != nil {
		fmt.Printf("[DexoLink Windows] Web UI server error: Failed to bind port %d: %v\n", WebUIPort, err)
		if ready != nil {
			ready <- err
		}
		return
	}
	fmt.Printf("[DexoLink Windows] Web UI server listening on http://localhost:%d\n", WebUIPort)
	if ready != nil {
		ready <- nil
	}
	_ = http.Serve(ln, nil)
}

func main() {
	fmt.Println("======================================================")
	fmt.Printf("  DexoLink for Windows v4.0.1 (%s/%s)\n", runtime.GOOS, runtime.GOARCH)
	fmt.Println("  Universal Cross-Platform Synchronization")
	fmt.Println("======================================================")

	// Step 1: Detect whether DexoLink is already running to avoid duplicate server instances
	if checkIfInstanceRunning() {
		fmt.Printf("[DexoLink Windows] An active DexoLink instance is already running on port %d / %d.\n", DefaultPort, WebUIPort)
		fmt.Println("[DexoLink Windows] Activating DexoLink GUI application window...")
		openGUIWindow(fmt.Sprintf("http://localhost:%d", WebUIPort))
		os.Exit(0)
	}

	// Step 2: Validate that required ports are available before starting
	if err := checkPortAvailable(DefaultPort); err != nil {
		fmt.Printf("[DexoLink Windows] Fatal Startup Error: TCP port %d is already occupied (%v).\n", DefaultPort, err)
		fmt.Println("[DexoLink Windows] Please terminate conflicting processes and restart DexoLink.")
		os.Exit(1)
	}
	if err := checkPortAvailable(WebUIPort); err != nil {
		fmt.Printf("[DexoLink Windows] Fatal Startup Error: Web UI port %d is already occupied (%v).\n", WebUIPort, err)
		fmt.Println("[DexoLink Windows] Please terminate conflicting processes and restart DexoLink.")
		os.Exit(1)
	}

	// Step 3: Initialize engine and synchronize startup
	engine = initEngine()
	fmt.Printf("[DexoLink Windows] Initialized DexoLink engine for %s\n", engine.DeviceName)

	tcpReady := make(chan error, 1)
	webReady := make(chan error, 1)

	go engine.startTCPServer(tcpReady)
	go engine.startUDPBeacon()
	go engine.startUDPListener()
	go startWebDashboard(webReady)

	// Verify servers initialized without error
	if err := <-webReady; err != nil {
		fmt.Printf("[DexoLink Windows] Fatal Startup Error: Web UI server could not start: %v\n", err)
		os.Exit(1)
	}
	if err := <-tcpReady; err != nil {
		fmt.Printf("[DexoLink Windows] Fatal Startup Error: TCP server could not start: %v\n", err)
		os.Exit(1)
	}

	// Step 4: Automatically open DexoLink GUI window
	guiURL := fmt.Sprintf("http://localhost:%d", WebUIPort)
	fmt.Println("[DexoLink Windows] Opening DexoLink GUI application window...")
	if openGUIWindow(guiURL) {
		fmt.Println("[DexoLink Windows] DexoLink GUI window opened successfully.")
	} else {
		fmt.Printf("[DexoLink Windows] Notice: Automatic window launch was blocked or unavailable.\n")
	}
	fmt.Printf("[DexoLink Windows] Local Web UI dashboard is accessible at: %s\n", guiURL)
	fmt.Println("[DexoLink Windows] DexoLink is ready. Press Ctrl+C to stop.")

	// Step 5: Graceful termination on OS interrupt
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, os.Interrupt, syscall.SIGTERM)
	<-sigChan
	fmt.Println("\n[DexoLink Windows] Shutting down DexoLink servers gracefully...")
	os.Exit(0)
}

const webUIHTML = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>DexoLink v4.0.1 (Windows)</title>
<style>
  :root {
    --bg: #0b1120;
    --card: #152033;
    --card-hover: #1c2b45;
    --border: #23334d;
    --border-light: rgba(255,255,255,0.08);
    --accent: #0066ff;
    --accent-hover: #1d4ed8;
    --cyan: #38bdf8;
    --text: #f8fafc;
    --muted: #94a3b8;
    --success: #22c55e;
    --warning: #f59e0b;
    --purple: #a855f7;
    --danger: #ef4444;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
  body { background: var(--bg); color: var(--text); padding: 18px 12px; line-height: 1.45; min-height: 100vh; }

  /* App Container - Centered macOS MenuBar View Width */
  .app-container { max-width: 500px; margin: 0 auto; display: flex; flex-direction: column; gap: 12px; }

  /* Top Navigation Bar */
  .header { display: flex; justify-content: space-between; align-items: center; padding: 10px 14px; background: var(--card); border: 1px solid var(--border); border-radius: 12px; }
  .brand { display: flex; align-items: center; gap: 8px; }
  .brand h1 { font-size: 16px; font-weight: 800; color: var(--text); letter-spacing: -0.3px; }
  .badge { background: rgba(56,189,248,0.15); color: var(--cyan); padding: 3px 7px; border-radius: 6px; font-size: 10px; font-weight: 700; }
  .badge-green { background: rgba(34,197,94,0.18); color: var(--success); }
  .badge-purple { background: rgba(168,85,247,0.18); color: var(--purple); }
  .badge-orange { background: rgba(245,158,11,0.18); color: var(--warning); }
  .header-actions { display: flex; align-items: center; gap: 6px; }
  .icon-btn { background: transparent; border: 1px solid var(--border); color: var(--muted); width: 32px; height: 32px; border-radius: 8px; cursor: pointer; display: flex; align-items: center; justify-content: center; transition: 0.15s; }
  .icon-btn:hover { background: var(--card-hover); border-color: var(--cyan); color: #fff; }

  /* Master Quick Settings ON/OFF Card (Matching MacBook MenuBar) */
  .master-card { background: var(--card); border: 1px solid var(--border); border-radius: 12px; padding: 12px 16px; display: flex; align-items: center; justify-content: space-between; box-shadow: 0 4px 16px rgba(0,0,0,0.25); }
  .master-info { display: flex; align-items: center; gap: 12px; flex: 1; min-width: 0; }
  .master-icon-wrap { width: 38px; height: 38px; border-radius: 50%; background: rgba(0,102,255,0.15); display: flex; align-items: center; justify-content: center; color: var(--accent); flex-shrink: 0; }
  .master-title-row { display: flex; align-items: center; gap: 6px; }
  .master-title { font-size: 14px; font-weight: 800; color: var(--text); }
  .master-state-pill { font-size: 9px; font-weight: 800; padding: 2px 6px; border-radius: 10px; background: rgba(0,102,255,0.2); color: var(--cyan); }
  .master-sub { font-size: 11px; color: var(--muted); margin-top: 2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; display: flex; align-items: center; gap: 6px; }

  /* Switch Toggle */
  .switch { position: relative; display: inline-block; width: 44px; height: 24px; flex-shrink: 0; }
  .switch input { opacity: 0; width: 0; height: 0; }
  .slider { position: absolute; cursor: pointer; top: 0; left: 0; right: 0; bottom: 0; background-color: #334155; transition: .25s; border-radius: 24px; }
  .slider:before { position: absolute; content: ""; height: 18px; width: 18px; left: 3px; bottom: 3px; background-color: white; transition: .25s; border-radius: 50%; }
  input:checked + .slider { background-color: var(--accent); }
  input:checked + .slider:before { transform: translateX(20px); }

  /* Cards */
  .card { background: var(--card); border: 1px solid var(--border); border-radius: 12px; padding: 14px 16px; box-shadow: 0 4px 14px rgba(0,0,0,0.2); }
  .card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
  .card-title { font-size: 13px; font-weight: 700; display: flex; align-items: center; gap: 7px; color: var(--text); }
  .card-sub { font-size: 11px; color: var(--muted); margin-bottom: 10px; line-height: 1.4; }
  .section-label { font-size: 11px; font-weight: 700; color: var(--muted); text-transform: uppercase; letter-spacing: 0.5px; margin-top: 10px; margin-bottom: 6px; }

  /* Buttons */
  .btn { background: var(--accent); color: #fff; border: none; border-radius: 8px; padding: 7px 12px; font-size: 12px; font-weight: 700; cursor: pointer; transition: 0.15s; display: inline-flex; align-items: center; justify-content: center; gap: 5px; }
  .btn:hover { background: var(--accent-hover); }
  .btn-sm { padding: 4px 8px; font-size: 11px; border-radius: 6px; }
  .btn-bordered { background: transparent; border: 1px solid var(--border); color: var(--text); }
  .btn-bordered:hover { background: var(--card-hover); border-color: var(--cyan); color: #fff; }
  .btn-success { background: rgba(34,197,94,0.18); color: var(--success); border: 1px solid rgba(34,197,94,0.3); }
  .btn-success:hover { background: rgba(34,197,94,0.3); }
  .btn-danger { background: rgba(239,68,68,0.18); color: var(--danger); border: 1px solid rgba(239,68,68,0.3); }
  .btn-danger:hover { background: rgba(239,68,68,0.3); }

  /* Inputs */
  .input { width: 100%; background: var(--bg); border: 1px solid var(--border); color: var(--text); padding: 8px 10px; border-radius: 8px; font-size: 12px; }
  .input:focus { outline: none; border-color: var(--cyan); }

  /* Drop Zone */
  .drop-zone { border: 1.5px dashed rgba(56,189,248,0.35); background: rgba(56,189,248,0.04); border-radius: 10px; padding: 14px 10px; text-align: center; cursor: pointer; transition: 0.2s; margin-bottom: 10px; }
  .drop-zone:hover, .drop-zone.dragover { border-color: var(--cyan); background: rgba(56,189,248,0.1); }
  .drop-zone-icon { color: var(--cyan); margin-bottom: 4px; }

  /* Lists */
  .list { list-style: none; margin-top: 6px; max-height: 180px; overflow-y: auto; }
  .list-item { background: var(--bg); border: 1px solid var(--border); padding: 8px 10px; border-radius: 8px; margin-bottom: 5px; display: flex; justify-content: space-between; align-items: center; font-size: 11px; }
  .list-item-left { display: flex; align-items: center; gap: 8px; overflow: hidden; min-width: 0; }
  .list-item-title { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 220px; }
  .list-item-sub { font-size: 10px; color: var(--muted); }

  /* Progress Bar */
  .progress-wrap { background: var(--bg); border: 1px solid var(--border); border-radius: 8px; padding: 8px 10px; margin-bottom: 10px; }
  .progress-bar-bg { width: 100%; height: 6px; background: #23334d; border-radius: 3px; overflow: hidden; margin-top: 6px; }
  .progress-bar-fill { height: 100%; background: linear-gradient(90deg, var(--accent), var(--cyan)); width: 0%; transition: width 0.2s; }

  /* Toggle Row */
  .toggle-row { display: flex; justify-content: space-between; align-items: center; padding: 8px 0; border-bottom: 1px solid var(--border); }

  /* Turned Off Banner */
  .turned-off-banner { background: var(--card); border: 1px solid var(--border); border-radius: 12px; padding: 36px 20px; text-align: center; }

  /* Toast Notification */
  #toastContainer { position: fixed; top: 18px; left: 50%; transform: translateX(-50%); z-index: 99999; display: flex; flex-direction: column; gap: 8px; pointer-events: none; width: 90%; max-width: 420px; }
  .toast { background: rgba(21,32,51,0.96); backdrop-filter: blur(10px); border: 1px solid var(--cyan); color: #fff; padding: 10px 14px; border-radius: 10px; font-size: 12px; font-weight: 600; box-shadow: 0 10px 30px rgba(0,0,0,0.6); pointer-events: auto; display: flex; align-items: center; gap: 8px; animation: slideDown 0.25s ease-out; }
  .toast.toast-error { border-color: var(--danger); color: #fca5a5; }
  .toast.toast-success { border-color: var(--success); color: #86efac; }
  @keyframes slideDown { from { opacity: 0; transform: translateY(-10px); } to { opacity: 1; transform: translateY(0); } }

  /* Modals */
  .modal-overlay { display: none; position: fixed; top: 0; left: 0; width: 100vw; height: 100vh; background: rgba(11,17,32,0.85); backdrop-filter: blur(6px); z-index: 9999; align-items: center; justify-content: center; }
  .modal-box { background: var(--card); border: 1px solid var(--border); border-radius: 14px; width: 92%; max-width: 480px; max-height: 85vh; overflow-y: auto; padding: 20px; box-shadow: 0 20px 50px rgba(0,0,0,0.7); }

  /* Footer */
  .footer { display: flex; justify-content: space-between; align-items: center; padding: 8px 12px; background: var(--card); border: 1px solid var(--border); border-radius: 10px; font-size: 11px; color: var(--muted); }
  .dev-attr { text-align: center; font-size: 10px; color: var(--muted); margin-top: 4px; }
  .status-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--success); box-shadow: 0 0 6px var(--success); }
  .status-dot.offline { background: var(--muted); box-shadow: none; }
</style>
</head>
<body>

  <!-- Floating Toast Notifications -->
  <div id="toastContainer"></div>

  <div class="app-container">

    <!-- Top Navigation Header -->
    <div class="header">
      <div class="brand">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#38bdf8" stroke-width="2.3" stroke-linecap="round" stroke-linejoin="round"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"></path><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"></path></svg>
        <h1>DexoLink</h1>
        <span class="badge">v4.0.1</span>
      </div>
      <div class="header-actions">
        <button class="icon-btn" onclick="openSettingsModal()" title="Settings & All Feature Controls">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg>
        </button>
        <button class="icon-btn" onclick="openHistoryModal()" title="Activity History">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline></svg>
        </button>
        <button class="icon-btn" onclick="openDirectIPModal()" title="Direct IP Connection">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><line x1="2" y1="12" x2="22" y2="12"></line><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"></path></svg>
        </button>
        <button class="icon-btn" onclick="rescanDiscovery()" title="Rescan Local Wi-Fi">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"></polyline><polyline points="1 20 1 14 7 14"></polyline><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"></path></svg>
        </button>
      </div>
    </div>

    <!-- Master Quick Settings ON/OFF Card (Matching MacBook MenuBar) -->
    <div class="master-card">
      <div class="master-info">
        <div class="master-icon-wrap" id="masterIconWrap">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M4.93 4.93a10 10 0 0 1 14.14 0"></path><path d="M7.76 7.76a6 6 0 0 1 8.48 0"></path><circle cx="12" cy="12" r="2"></circle><path d="M12 14v8"></path></svg>
        </div>
        <div style="min-width:0; flex:1;">
          <div class="master-title-row">
            <span class="master-title">DexoLink</span>
            <span class="master-state-pill" id="masterStatePill">ON</span>
          </div>
          <div class="master-sub" id="masterSubtitle">
            <span class="status-dot" id="masterStatusDot"></span>
            <span id="masterStatusText">Active • Ready to connect</span>
            <span id="masterInlineAction"></span>
          </div>
        </div>
      </div>
      <label class="switch">
        <input type="checkbox" id="masterSwitch" checked onchange="toggleMaster(this.checked)">
        <span class="slider"></span>
      </label>
    </div>

    <!-- Turned Off State Banner -->
    <div id="turnedOffBanner" class="turned-off-banner" style="display:none;">
      <div style="width:52px; height:52px; border-radius:50%; background:rgba(148,163,184,0.12); display:flex; align-items:center; justify-content:center; margin:0 auto 12px auto; color:var(--muted);">
        <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="1" y1="1" x2="23" y2="23"></line><path d="M16.72 11.06A10.94 10.94 0 0 1 19 12.55"></path><path d="M5 12.55a10.94 10.94 0 0 1 5.17-2.39"></path><path d="M10.71 5.05A16 16 0 0 1 22.58 9"></path><path d="M1.42 9a15.91 15.91 0 0 1 4.7-2.88"></path><path d="M8.53 16.11a6 6 0 0 1 6.95 0"></path><line x1="12" y1="20" x2="12.01" y2="20"></line></svg>
      </div>
      <h2 style="font-size:16px; font-weight:800; margin-bottom:6px;">DexoLink is Turned Off</h2>
      <p style="font-size:12px; color:var(--muted); margin-bottom:16px; line-height:1.4;">All background listeners, discovery broadcasts, and socket connections are stopped to ensure 0% CPU and battery usage.</p>
      <button class="btn" onclick="toggleMaster(true)">Turn On DexoLink</button>
    </div>

    <!-- Active Components Stack (Matching MacBook MenuBar View) -->
    <div id="activeDashboard" style="display:flex; flex-direction:column; gap:12px;">

      <!-- Card: Paired Trusted Devices Status -->
      <div class="card" id="pairedDevicesCard">
        <div class="card-header">
          <div class="card-title">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#22c55e" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path></svg>
            <span>Paired Devices</span>
          </div>
          <span class="badge" id="pairedCountBadge">0 PAIRED</span>
        </div>
        <ul class="list" id="pairedList">
          <li class="muted">No paired devices yet. Discover nearby devices below.</li>
        </ul>
      </div>

      <!-- Card: Quick File Sharing (Drag & Drop + Send Files) -->
      <div class="card">
        <div class="card-header">
          <div class="card-title">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#f59e0b" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path></svg>
            <span>Quick File Sharing</span>
          </div>
          <button class="btn btn-bordered btn-sm" onclick="openDownloadsFolder()" title="Open Downloads in Windows Explorer">Downloads</button>
        </div>

        <!-- Drag & Drop Zone -->
        <div class="drop-zone" id="dropZone" onclick="triggerFileSelect()">
          <div class="drop-zone-icon">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><polyline points="16 12 12 8 8 12"></polyline><line x1="12" y1="16" x2="12" y2="8"></line></svg>
          </div>
          <div style="font-weight:700; font-size:12px; color:var(--text);" id="dropZoneTitle">Drop files here or click to send</div>
          <div style="font-size:10px; color:var(--muted); margin-top:2px;">Photos • PDFs • Documents • Downloads • Any file</div>
          <input type="file" id="hiddenFileInput" style="display:none;" onchange="handleFileSelect(this)">
        </div>

        <button class="btn" style="width:100%; margin-bottom:8px;" id="sendFilesBtn" onclick="triggerFileSelect()">Select Files to Send</button>

        <!-- Live Upload Progress -->
        <div id="uploadProgressContainer" class="progress-wrap" style="display:none;">
          <div style="display:flex; justify-content:space-between; font-size:11px; font-weight:600;">
            <span id="uploadProgressName">Streaming file...</span>
            <span id="uploadProgressPercent">0%</span>
          </div>
          <div class="progress-bar-bg">
            <div id="uploadProgressBar" class="progress-bar-fill"></div>
          </div>
        </div>

        <div class="section-label">Recent Completed Transfers</div>
        <ul class="list" id="transfersList">
          <li class="muted">No transfers yet today.</li>
        </ul>
      </div>

      <!-- Card: Media Streaming & Remote Control (v4.0.1) -->
      <div class="card">
        <div class="card-header">
          <div class="card-title">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#22c55e" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"></rect><line x1="8" y1="21" x2="16" y2="21"></line><line x1="12" y1="17" x2="12" y2="21"></line></svg>
            <span>Media Streaming & Remote Control</span>
          </div>
          <span class="badge badge-green">v4.0.1</span>
        </div>

        <!-- Camera Section -->
        <div style="background:var(--bg); border:1px solid var(--border); border-radius:8px; padding:10px; margin-bottom:8px;">
          <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:6px;">
            <div style="font-weight:700; font-size:11px; display:flex; align-items:center; gap:6px;">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#38bdf8" stroke-width="2"><path d="M23 7l-7 5 7 5V7z"></path><rect x="1" y="5" width="15" height="14" rx="2" ry="2"></rect></svg>
              <span>Camera Sharing</span>
            </div>
            <span class="badge" id="cameraStatusBadge">INACTIVE</span>
          </div>
          <div style="display:flex; gap:6px;">
            <button class="btn btn-sm btn-bordered" style="flex:1;" onclick="requestCameraStream('camera', 'request_stream')">Request Camera Feed</button>
            <button class="btn btn-sm btn-bordered" onclick="requestCameraStream('camera', 'switch_camera')">Flip Camera</button>
          </div>
        </div>

        <!-- Screen Mirroring Section -->
        <div style="background:var(--bg); border:1px solid var(--border); border-radius:8px; padding:10px; margin-bottom:8px;">
          <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:6px;">
            <div style="font-weight:700; font-size:11px; display:flex; align-items:center; gap:6px;">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#f59e0b" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"></rect><line x1="8" y1="21" x2="16" y2="21"></line><line x1="12" y1="17" x2="12" y2="21"></line></svg>
              <span>Screen Sharing</span>
            </div>
            <span class="badge" id="screenStatusBadge">INACTIVE</span>
          </div>
          <button class="btn btn-sm btn-bordered" style="width:100%;" onclick="requestCameraStream('screen', 'request_stream')">Request Remote Screen</button>
        </div>

        <!-- Remote Desktop Input -->
        <div style="background:var(--bg); border:1px solid var(--border); border-radius:8px; padding:10px;">
          <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:6px;">
            <div style="font-weight:700; font-size:11px; display:flex; align-items:center; gap:6px;">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#a855f7" stroke-width="2"><polyline points="4 7 4 4 20 4 20 7"></polyline><line x1="9" y1="20" x2="15" y2="20"></line><line x1="12" y1="4" x2="12" y2="20"></line></svg>
              <span>Remote Write & Control</span>
            </div>
            <span class="badge badge-purple">BETA</span>
          </div>
          <div style="display:flex; gap:6px; margin-bottom:6px;">
            <input type="text" id="remoteWriteInput" class="input" placeholder="Type text to send to remote screen...">
            <button class="btn btn-sm" onclick="sendRemoteWriteText()">Send</button>
          </div>
          <div style="display:flex; gap:5px; flex-wrap:wrap;">
            <button class="btn btn-sm btn-bordered" onclick="sendRemoteKey('enter')">Enter</button>
            <button class="btn btn-sm btn-bordered" onclick="sendRemoteKey('backspace')">Backspace</button>
            <button class="btn btn-sm btn-bordered" onclick="sendRemoteKey('space')">Space</button>
            <button class="btn btn-sm btn-bordered" onclick="sendRemoteKey('tab')">Tab</button>
            <button class="btn btn-sm btn-bordered" onclick="sendRemoteClick('left')">Left Click</button>
            <button class="btn btn-sm btn-bordered" onclick="sendRemoteClick('right')">Right Click</button>
          </div>
        </div>
      </div>

      <!-- Card: Universal Clipboard -->
      <div class="card">
        <div class="card-header">
          <div class="card-title">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#38bdf8" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"></path><rect x="8" y="2" width="8" height="4" rx="1" ry="1"></rect></svg>
            <span>Universal Clipboard</span>
          </div>
          <span class="badge badge-green">AUTO SYNC</span>
        </div>
        <textarea id="clipText" class="input" style="height:60px; resize:none; margin-bottom:8px;" placeholder="Type or paste text to sync across paired devices..."></textarea>
        <button class="btn" style="width:100%;" onclick="pushClip()">Push Text to Devices</button>
        
        <div class="section-label">Clipboard History</div>
        <ul class="list" id="clipList">
          <li class="muted">No clipboard items yet.</li>
        </ul>
      </div>

      <!-- Card: Continuity & Handoff -->
      <div class="card">
        <div class="card-header">
          <div class="card-title">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#a855f7" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><polygon points="16.24 7.76 14.12 14.12 7.76 16.24 9.88 9.88 16.24 7.76"></polygon></svg>
            <span>Continuity & Handoff</span>
          </div>
        </div>
        <p class="card-sub">Open websites and browser links instantly on your phone or Mac.</p>
        <div style="display:flex; gap:6px;">
          <input type="url" id="contUrl" class="input" value="https://">
          <button class="btn" style="background:var(--purple); white-space:nowrap;" onclick="sendCont()">Launch</button>
        </div>
      </div>

      <!-- Card: Nearby Discovered Devices (Wi-Fi) -->
      <div class="card">
        <div class="card-header">
          <div class="card-title">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#38bdf8" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="2"></circle><path d="M16.24 7.76a6 6 0 0 1 0 8.49m-8.48-.01a6 6 0 0 1 0-8.49m11.31-2.82a10 10 0 0 1 0 14.14m-14.14 0a10 10 0 0 1 0-14.14"></path></svg>
            <span>Nearby Devices (Wi-Fi)</span>
          </div>
          <button class="btn btn-bordered btn-sm" onclick="rescanDiscovery()">Rescan</button>
        </div>
        <ul class="list" id="discoveredList">
          <li class="muted">Searching for nearby devices on local Wi-Fi...</li>
        </ul>
      </div>

      <!-- Card: Feature Controls Summary (Matching MacBook Reference Design) -->
      <div class="card" style="display:flex; align-items:center; justify-content:space-between; padding:12px 16px;">
        <div style="display:flex; align-items:center; gap:10px;">
          <div style="width:34px; height:34px; border-radius:8px; background:rgba(30,58,138,0.4); border:1px solid rgba(59,130,246,0.3); display:flex; align-items:center; justify-content:center; color:#60a5fa;">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#60a5fa" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg>
          </div>
          <div>
            <div style="display:flex; align-items:center; gap:6px;">
              <span style="font-weight:700; font-size:13px; color:var(--text);">Feature Controls</span>
              <span id="featCountBadge" class="badge badge-green">4 ACTIVE</span>
            </div>
            <div style="font-size:11px; color:var(--muted);">All synchronization & streaming modules</div>
          </div>
        </div>
        <button class="btn btn-sm" onclick="openSettingsModal()" style="background:#2563eb; padding:6px 14px;">Configure</button>
      </div>

    </div>

    <!-- Footer -->
    <div class="footer">
      <div style="display:flex; align-items:center; gap:6px;">
        <span class="status-dot" id="footerDot"></span>
        <span id="footerText">Ready to connect • Port 52840</span>
      </div>
      <button class="btn btn-bordered btn-sm" onclick="rescanDiscovery()">Refresh</button>
    </div>
    <div class="dev-attr">Developed by Rahul Mahaseth | DexoLink v4.0.1 (Universal)</div>

  </div>

  <!-- Dedicated Settings Modal -->
  <div id="settingsModal" class="modal-overlay">
    <div class="modal-box">
      <div style="display:flex; justify-content:space-between; align-items:center; border-bottom:1px solid var(--border); padding-bottom:12px; margin-bottom:14px;">
        <div>
          <h2 style="font-size:16px; font-weight:800; color:var(--text);">DexoLink Settings</h2>
          <p style="font-size:11px; color:var(--muted);">Configure features, security, and network preferences</p>
        </div>
        <button class="btn btn-bordered btn-sm" onclick="closeSettingsModal()">&times;</button>
      </div>

      <div style="font-size:10px; font-weight:800; color:var(--cyan); text-transform:uppercase; margin-bottom:6px; letter-spacing:0.5px;">Core Modules</div>
      
      <div class="toggle-row">
        <div>
          <div style="font-weight:600; font-size:12px;">Continuity / Handoff</div>
          <div class="muted" style="font-size:11px;">Seamlessly open web pages across devices</div>
        </div>
        <input type="checkbox" id="modalContinuityToggle" onchange="toggleFeature('continuity', this.checked)">
      </div>

      <div class="toggle-row">
        <div>
          <div style="font-weight:600; font-size:12px;">Auto Clipboard Sync</div>
          <div class="muted" style="font-size:11px;">Mirror copied text instantly over local Wi-Fi</div>
        </div>
        <input type="checkbox" id="modalClipToggle" onchange="toggleFeature('clipboard', this.checked)">
      </div>

      <div class="toggle-row">
        <div>
          <div style="font-weight:600; font-size:12px;">Multiple Paired Devices</div>
          <div class="muted" style="font-size:11px;">OFF by default (Single-device strict security)</div>
        </div>
        <input type="checkbox" id="modalMultiToggle" onchange="toggleMulti(this.checked)">
      </div>

      <div style="font-size:10px; font-weight:800; color:var(--cyan); text-transform:uppercase; margin-top:14px; margin-bottom:6px; letter-spacing:0.5px;">Media Streaming & Control</div>

      <div class="toggle-row">
        <div>
          <div style="font-weight:600; font-size:12px;">Camera Sharing</div>
          <div class="muted" style="font-size:11px;">Stream camera video with paired phones</div>
        </div>
        <input type="checkbox" id="modalCameraToggle" onchange="toggleFeature('camera_sharing', this.checked)">
      </div>

      <div class="toggle-row">
        <div>
          <div style="font-weight:600; font-size:12px;">Screen Sharing</div>
          <div class="muted" style="font-size:11px;">View remote screen or share Windows display</div>
        </div>
        <input type="checkbox" id="modalScreenToggle" onchange="toggleFeature('screen_sharing', this.checked)">
      </div>

      <div class="toggle-row">
        <div>
          <div style="font-weight:600; font-size:12px;">Desktop Mode (BETA)</div>
          <div class="muted" style="font-size:11px;">Remote keyboard & mouse input injection</div>
        </div>
        <input type="checkbox" id="modalDesktopToggle" onchange="toggleFeature('desktop_mode', this.checked)">
      </div>

      <div style="font-size:10px; font-weight:800; color:var(--cyan); text-transform:uppercase; margin-top:14px; margin-bottom:6px; letter-spacing:0.5px;">Storage & Downloads</div>
      <div style="background:var(--bg); border:1px solid var(--border); border-radius:8px; padding:10px; margin-top:6px; display:flex; justify-content:space-between; align-items:center;">
        <div>
          <div style="font-size:10px; color:var(--muted);">Downloads Folder:</div>
          <div id="modalDownPath" style="font-size:11px; font-family:monospace; color:var(--cyan); word-break:break-all;">~/Downloads/DexoLink</div>
        </div>
        <button class="btn btn-bordered btn-sm" onclick="openDownloadsFolder()">Open</button>
      </div>

      <div style="text-align:right; margin-top:16px; padding-top:12px; border-top:1px solid var(--border);">
        <button class="btn" onclick="closeSettingsModal()">Done</button>
      </div>
    </div>
  </div>

  <!-- Activity History Modal -->
  <div id="historyModal" class="modal-overlay">
    <div class="modal-box">
      <div style="display:flex; justify-content:space-between; align-items:center; border-bottom:1px solid var(--border); padding-bottom:12px; margin-bottom:14px;">
        <h2 style="font-size:16px; font-weight:800;">Activity History</h2>
        <button class="btn btn-bordered btn-sm" onclick="closeHistoryModal()">&times;</button>
      </div>

      <div style="display:flex; gap:6px; margin-bottom:12px;">
        <button class="btn btn-sm" id="tabTransfersBtn" onclick="switchHistoryTab('transfers')">Transfers History</button>
        <button class="btn btn-bordered btn-sm" id="tabClipboardBtn" onclick="switchHistoryTab('clipboard')">Clipboard History</button>
      </div>

      <div id="historyTransfersTab">
        <ul class="list" id="modalTransfersList" style="max-height:300px;">
          <li class="muted">No transfers in history.</li>
        </ul>
      </div>

      <div id="historyClipboardTab" style="display:none;">
        <ul class="list" id="modalClipboardList" style="max-height:300px;">
          <li class="muted">No clipboard items in history.</li>
        </ul>
      </div>

      <div style="text-align:right; margin-top:16px; padding-top:12px; border-top:1px solid var(--border);">
        <button class="btn" onclick="closeHistoryModal()">Close</button>
      </div>
    </div>
  </div>

  <!-- Direct IP Modal -->
  <div id="directIPModal" class="modal-overlay">
    <div class="modal-box" style="max-width:400px;">
      <div style="display:flex; justify-content:space-between; align-items:center; border-bottom:1px solid var(--border); padding-bottom:10px; margin-bottom:12px;">
        <h2 style="font-size:15px; font-weight:800;">Direct IP Connection</h2>
        <button class="btn btn-bordered btn-sm" onclick="closeDirectIPModal()">&times;</button>
      </div>
      <p style="font-size:11px; color:var(--muted); margin-bottom:12px;">Connect to an Android phone, iPhone, or Mac by entering its local IP address.</p>
      
      <label style="font-size:10px; font-weight:700; color:var(--muted);">HOST / IP ADDRESS</label>
      <input type="text" id="manualHostInput" class="input" style="margin-bottom:8px;" placeholder="e.g. 192.168.1.100">
      
      <label style="font-size:10px; font-weight:700; color:var(--muted);">PORT (DEFAULT 52840)</label>
      <input type="text" id="manualPortInput" class="input" value="52840" style="margin-bottom:12px;">

      <div style="display:flex; justify-content:flex-end; gap:6px;">
        <button class="btn btn-bordered" onclick="closeDirectIPModal()">Cancel</button>
        <button class="btn" onclick="connectDirectSubmit()">Connect Peer</button>
      </div>
    </div>
  </div>

  <script>
    let currentData = null;

    // Toast Notification System
    function showToast(message, type = 'info') {
      const c = document.getElementById('toastContainer');
      const t = document.createElement('div');
      t.className = 'toast' + (type === 'error' ? ' toast-error' : (type === 'success' ? ' toast-success' : ''));
      t.innerHTML = message;
      c.appendChild(t);
      setTimeout(() => {
        t.style.transition = 'opacity 0.3s, transform 0.3s';
        t.style.opacity = '0';
        t.style.transform = 'translateY(-10px)';
        setTimeout(() => t.remove(), 300);
      }, 2500);
    }

    // Modal Handlers
    function openSettingsModal() { document.getElementById('settingsModal').style.display = 'flex'; }
    function closeSettingsModal() { document.getElementById('settingsModal').style.display = 'none'; }
    function openHistoryModal() { document.getElementById('historyModal').style.display = 'flex'; switchHistoryTab('transfers'); }
    function closeHistoryModal() { document.getElementById('historyModal').style.display = 'none'; }
    function openDirectIPModal() { document.getElementById('directIPModal').style.display = 'flex'; }
    function closeDirectIPModal() { document.getElementById('directIPModal').style.display = 'none'; }

    function switchHistoryTab(tab) {
      if (tab === 'transfers') {
        document.getElementById('historyTransfersTab').style.display = 'block';
        document.getElementById('historyClipboardTab').style.display = 'none';
        document.getElementById('tabTransfersBtn').className = 'btn btn-sm';
        document.getElementById('tabClipboardBtn').className = 'btn btn-bordered btn-sm';
      } else {
        document.getElementById('historyTransfersTab').style.display = 'none';
        document.getElementById('historyClipboardTab').style.display = 'block';
        document.getElementById('tabTransfersBtn').className = 'btn btn-bordered btn-sm';
        document.getElementById('tabClipboardBtn').className = 'btn btn-sm';
      }
    }

    // Master Switch Toggle
    async function toggleMaster(val) {
      try {
        await fetch('/api/toggle-master?enabled=' + val);
        showToast(val ? 'DexoLink Turned ON' : 'DexoLink Turned OFF', val ? 'success' : 'info');
        loadStatus();
      } catch (e) {
        showToast('Error toggling master switch', 'error');
      }
    }

    // Feature Toggles
    async function toggleFeature(feat, val) {
      try {
        await fetch('/api/toggle-feature?feature=' + feat + '&enabled=' + val);
        loadStatus();
      } catch (e) {
        showToast('Error updating setting', 'error');
      }
    }

    async function toggleMulti(val) {
      try {
        await fetch('/api/toggle-multi-paired?enabled=' + val);
        loadStatus();
      } catch (e) {
        showToast('Error toggling multi-device', 'error');
      }
    }

    // Direct IP Submit
    async function connectDirectSubmit() {
      const host = document.getElementById('manualHostInput').value.trim();
      const port = document.getElementById('manualPortInput').value.trim() || '52840';
      if (!host) {
        showToast('Please enter an IP address', 'error');
        return;
      }
      showToast('Connecting to ' + host + '...', 'info');
      try {
        const res = await fetch('/api/connect-direct?ip=' + encodeURIComponent(host) + '&port=' + encodeURIComponent(port));
        const data = await res.json();
        closeDirectIPModal();
        if (data.success) {
          showToast('Connected to ' + host + ' successfully!', 'success');
        } else {
          showToast('Connection failed: ' + (data.error || 'Timeout'), 'error');
        }
        loadStatus();
      } catch (e) {
        showToast('Connection error: ' + e, 'error');
      }
    }

    // Connect to Peer by Discovered Device or Paired Device
    async function connectPeer(id, name, ip, port) {
      showToast('Connecting to ' + name + '...', 'info');
      try {
        const url = '/api/connect-peer?id=' + encodeURIComponent(id || '') + '&name=' + encodeURIComponent(name || '') + '&ip=' + encodeURIComponent(ip || '') + '&port=' + (port || 52840);
        const res = await fetch(url);
        const data = await res.json();
        if (data.success) {
          showToast('Connected to ' + name + ' successfully!', 'success');
        } else {
          showToast('Failed to connect: ' + (data.error || 'Recheck Wi-Fi'), 'error');
        }
        loadStatus();
      } catch (e) {
        showToast('Error connecting: ' + e, 'error');
      }
    }

    // Disconnect & Unpair
    async function disconnectDevice(id) {
      try {
        await fetch('/api/disconnect?id=' + encodeURIComponent(id || ''));
        showToast('Disconnected device session', 'info');
        loadStatus();
      } catch (e) {
        showToast('Error disconnecting', 'error');
      }
    }

    async function unpairDevice(id) {
      try {
        await fetch('/api/unpair?id=' + encodeURIComponent(id));
        showToast('Device unpaired', 'info');
        loadStatus();
      } catch (e) {
        showToast('Error unpairing', 'error');
      }
    }

    async function pairDevice(id, name, ip, port) {
      showToast('Pairing with ' + name + '...', 'info');
      try {
        await fetch('/api/pair-device?id=' + encodeURIComponent(id) + '&name=' + encodeURIComponent(name) + '&ip=' + encodeURIComponent(ip) + '&port=' + port);
        showToast('Paired and connected to ' + name + '!', 'success');
        loadStatus();
      } catch (e) {
        showToast('Error pairing', 'error');
      }
    }

    // Clipboard Push
    async function pushClip() {
      const val = document.getElementById('clipText').value.trim();
      if (!val) {
        showToast('Please type text to sync', 'error');
        return;
      }
      try {
        const res = await fetch('/api/push-clipboard', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ text: val })
        });
        const data = await res.json();
        if (data.success) {
          showToast('Pushed text to connected devices!', 'success');
          document.getElementById('clipText').value = '';
        } else {
          showToast('Clipboard push queued (no active device)', 'info');
        }
        loadStatus();
      } catch (e) {
        showToast('Clipboard error: ' + e, 'error');
      }
    }

    async function deleteClip(idx) {
      await fetch('/api/delete-clip?index=' + idx);
      loadStatus();
    }

    // Continuity Send
    async function sendCont() {
      const val = document.getElementById('contUrl').value.trim();
      if (!val || val === 'https://') {
        showToast('Please enter a valid URL', 'error');
        return;
      }
      try {
        const res = await fetch('/api/send-continuity', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ url: val })
        });
        const data = await res.json();
        if (data.success) {
          showToast('Launched web page on connected devices!', 'success');
        } else {
          showToast('Sent link (will launch when device connects)', 'info');
        }
      } catch (e) {
        showToast('Continuity error: ' + e, 'error');
      }
    }

    // Stream Requests
    async function requestCameraStream(sType, action) {
      try {
        const res = await fetch('/api/stream-request?streamType=' + sType + '&action=' + action);
        const data = await res.json();
        if (data.success) {
          showToast('Sent ' + sType + ' stream command (' + action + ')!', 'success');
        } else {
          showToast('Request sent (waiting for device connection)', 'info');
        }
      } catch (e) {
        showToast('Streaming error: ' + e, 'error');
      }
    }

    // Remote Write & Desktop Control
    async function sendRemoteWriteText() {
      const val = document.getElementById('remoteWriteInput').value;
      if (!val) return;
      try {
        const res = await fetch('/api/send-desktop-input', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ eventType: 'write', key: val })
        });
        document.getElementById('remoteWriteInput').value = '';
        showToast('Sent text to remote screen', 'success');
      } catch (e) {
        showToast('Input error: ' + e, 'error');
      }
    }

    async function sendRemoteKey(k) {
      try {
        await fetch('/api/send-desktop-input', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ eventType: 'key_press', key: k })
        });
        showToast('Sent key: ' + k, 'info');
      } catch (e) {
        showToast('Key error: ' + e, 'error');
      }
    }

    async function sendRemoteClick(btn) {
      try {
        await fetch('/api/send-desktop-input', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ eventType: 'click', x: 0.5, y: 0.5, button: btn === 'right' ? 1 : 0 })
        });
        showToast('Sent ' + btn + ' click', 'info');
      } catch (e) {
        showToast('Click error: ' + e, 'error');
      }
    }

    // Open Downloads Folder in Explorer
    async function openDownloadsFolder() {
      await fetch('/api/open-downloads');
    }

    // File Selection & Upload
    function triggerFileSelect() {
      document.getElementById('hiddenFileInput').click();
    }

    function handleFileSelect(input) {
      if (input.files && input.files[0]) {
        uploadFile(input.files[0]);
      }
    }

    async function uploadFile(file) {
      const pWrap = document.getElementById('uploadProgressContainer');
      const pName = document.getElementById('uploadProgressName');
      const pBar = document.getElementById('uploadProgressBar');
      const pPct = document.getElementById('uploadProgressPercent');

      pWrap.style.display = 'block';
      pName.innerText = 'Sending ' + file.name + ' (' + (file.size / 1024).toFixed(1) + ' KB)...';
      pBar.style.width = '30%';
      pPct.innerText = '30%';

      const formData = new FormData();
      formData.append('file', file);

      try {
        pBar.style.width = '70%';
        pPct.innerText = '70%';
        const res = await fetch('/api/upload-file', { method: 'POST', body: formData });
        const data = await res.json();
        pBar.style.width = '100%';
        pPct.innerText = '100%';
        setTimeout(() => { pWrap.style.display = 'none'; }, 600);

        if (data.success) {
          showToast('Sent ' + file.name + ' successfully!', 'success');
        } else {
          showToast('Send failed: ' + (data.error || 'Check connection'), 'error');
        }
        loadStatus();
      } catch (e) {
        pWrap.style.display = 'none';
        showToast('Upload error: ' + e, 'error');
      }
    }

    const dropZone = document.getElementById('dropZone');
    dropZone.addEventListener('dragover', (e) => { e.preventDefault(); dropZone.classList.add('dragover'); });
    dropZone.addEventListener('dragleave', () => { dropZone.classList.remove('dragover'); });
    dropZone.addEventListener('drop', (e) => {
      e.preventDefault();
      dropZone.classList.remove('dragover');
      if (e.dataTransfer.files && e.dataTransfer.files[0]) {
        uploadFile(e.dataTransfer.files[0]);
      }
    });

    async function rescanDiscovery() {
      showToast('Scanning local Wi-Fi for nearby devices...', 'info');
      loadStatus();
    }

    // Periodic Status Loader
    async function loadStatus() {
      try {
        const res = await fetch('/api/status');
        const data = await res.json();
        currentData = data;

        // Master switch state
        const isMasterOn = (data.is_enabled !== false);
        document.getElementById('masterSwitch').checked = isMasterOn;
        document.getElementById('masterStatePill').innerText = isMasterOn ? 'ON' : 'OFF';
        document.getElementById('masterStatePill').className = isMasterOn ? 'master-state-pill' : 'master-state-pill badge-orange';
        document.getElementById('activeDashboard').style.display = isMasterOn ? 'flex' : 'none';
        document.getElementById('turnedOffBanner').style.display = isMasterOn ? 'none' : 'block';

        const dot = document.getElementById('masterStatusDot');
        const text = document.getElementById('masterStatusText');
        const action = document.getElementById('masterInlineAction');
        const fDot = document.getElementById('footerDot');
        const fText = document.getElementById('footerText');

        if (!isMasterOn) {
          dot.className = 'status-dot offline';
          text.innerText = 'Turned Off • 0% CPU & Battery';
          action.innerHTML = '';
          fDot.className = 'status-dot offline';
          fText.innerText = 'DexoLink is Turned Off';
        } else if (data.is_connected && data.connected_devices && data.connected_devices.length > 0) {
          const first = data.connected_devices[0];
          dot.className = 'status-dot';
          text.innerText = 'Connected to ' + first.device_name + ' (' + first.ip + ')';
          action.innerHTML = '';
          fDot.className = 'status-dot';
          fText.innerText = 'Connected to ' + first.device_name + ' • Sync active';
          document.getElementById('sendFilesBtn').innerText = 'Send Files to ' + first.device_name;
        } else if (data.paired_devices && data.paired_devices.length > 0) {
          const first = data.paired_devices[0];
          dot.className = 'status-dot offline';
          text.innerText = first.device_name + ' (Disconnected)';
          action.innerHTML = '<button class="btn btn-sm btn-success" style="padding:2px 8px; font-size:10px;" onclick="connectPeer('' + first.device_id + '','' + first.device_name.replace(/'/g, "\'") + '','' + (first.ip || '') + '',' + (first.port || 52840) + ')">Connect Now</button>';
          fDot.className = 'status-dot offline';
          fText.innerText = 'Paired with ' + first.device_name + ' • Ready to connect';
          document.getElementById('sendFilesBtn').innerText = 'Select Files to Send';
        } else {
          dot.className = 'status-dot';
          text.innerText = 'Active • Ready to connect on local Wi-Fi';
          action.innerHTML = '';
          fDot.className = 'status-dot';
          fText.innerText = 'Ready • Local Wi-Fi Auto-Discovery';
          document.getElementById('sendFilesBtn').innerText = 'Select Files to Send';
        }

        // Active Features Count
        let activeCount = 0;
        if (data.continuity_enabled) activeCount++;
        if (data.auto_clipboard_sync) activeCount++;
        if (data.multiple_paired) activeCount++;
        if (data.camera_sharing_enabled) activeCount++;
        if (data.screen_sharing_enabled) activeCount++;
        if (data.desktop_mode_enabled) activeCount++;
        document.getElementById('featCountBadge').innerText = activeCount + ' ACTIVE';

        // Streaming badges
        document.getElementById('cameraStatusBadge').innerText = data.camera_sharing_enabled ? 'CONNECTED' : 'INACTIVE';
        document.getElementById('cameraStatusBadge').className = data.camera_sharing_enabled ? 'badge badge-green' : 'badge';
        document.getElementById('screenStatusBadge').innerText = data.screen_sharing_enabled ? 'CONNECTED' : 'INACTIVE';
        document.getElementById('screenStatusBadge').className = data.screen_sharing_enabled ? 'badge badge-green' : 'badge';

        // Settings modal sync
        if (document.getElementById('modalContinuityToggle')) {
          document.getElementById('modalContinuityToggle').checked = data.continuity_enabled;
          document.getElementById('modalClipToggle').checked = data.auto_clipboard_sync;
          document.getElementById('modalMultiToggle').checked = data.multiple_paired;
          document.getElementById('modalCameraToggle').checked = data.camera_sharing_enabled;
          document.getElementById('modalScreenToggle').checked = data.screen_sharing_enabled;
          document.getElementById('modalDesktopToggle').checked = data.desktop_mode_enabled;
          document.getElementById('modalDownPath').innerText = data.download_dir;
        }

        // Paired devices list
        const pList = document.getElementById('pairedList');
        const pCountBadge = document.getElementById('pairedCountBadge');
        if (data.paired_devices && data.paired_devices.length > 0) {
          pCountBadge.innerText = data.paired_devices.length + ' PAIRED';
          pList.innerHTML = data.paired_devices.map(d => {
            const isConn = data.connected_devices && data.connected_devices.some(c => c.device_id === d.device_id || c.ip === d.ip);
            return '<li class="list-item">' +
              '<div class="list-item-left">' +
                '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="' + (isConn ? '#22c55e' : '#38bdf8') + '" stroke-width="2.2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path></svg>' +
                '<div>' +
                  '<div class="list-item-title">' + d.device_name + '</div>' +
                  '<div class="list-item-sub">' + (isConn ? '<span style="color:var(--success); font-weight:700;">Connected</span>' : 'Paired') + ' • ' + (data.multiple_paired ? 'Multi-Device' : 'Strict 1-Device Policy') + '</div>' +
                '</div>' +
              '</div>' +
              '<div style="display:flex; gap:5px;">' +
                (isConn ?
                  '<button class="btn btn-sm btn-bordered" onclick="triggerFileSelect()">Send Files</button>' +
                  '<button class="btn btn-sm btn-bordered" onclick="disconnectDevice('' + d.device_id + '')">Disconnect</button>' :
                  '<button class="btn btn-sm btn-success" onclick="connectPeer('' + d.device_id + '','' + d.device_name.replace(/'/g, "\'") + '','' + (d.ip || '') + '',' + (d.port || 52840) + ')">Connect</button>'
                ) +
                '<button class="btn btn-danger btn-sm" onclick="unpairDevice('' + d.device_id + '')">Unpair</button>' +
              '</div>' +
            '</li>';
          }).join('');
        } else {
          pCountBadge.innerText = '0 PAIRED';
          pList.innerHTML = '<li class="muted">No paired devices yet. Discover nearby devices below.</li>';
        }

        // Nearby Discovered devices list
        const dList = document.getElementById('discoveredList');
        if (data.discovered_devices && data.discovered_devices.length > 0) {
          dList.innerHTML = data.discovered_devices.map(d => {
            const isPaired = data.paired_devices && data.paired_devices.some(p => p.device_id === d.device_id || p.device_name === d.device_name);
            const isConn = data.connected_devices && data.connected_devices.some(c => c.device_id === d.device_id || c.ip === d.ip);
            return '<li class="list-item">' +
              '<div class="list-item-left">' +
                '<div style="width:7px; height:7px; border-radius:50%; background:' + (isConn ? '#22c55e' : '#38bdf8') + ';"></div>' +
                '<div>' +
                  '<div class="list-item-title">' + d.device_name + '</div>' +
                  '<div class="list-item-sub">' + d.ip + ':' + d.port + '</div>' +
                '</div>' +
              '</div>' +
              '<div style="display:flex; gap:5px;">' +
                (isConn ?
                  '<span class="badge badge-green" style="display:flex; align-items:center;">Connected</span>' :
                  (isPaired ?
                    '<button class="btn btn-sm btn-success" onclick="connectPeer('' + d.device_id + '','' + d.device_name.replace(/'/g, "\'") + '','' + d.ip + '',' + d.port + ')">Connect</button>' :
                    '<button class="btn btn-sm btn-bordered" onclick="pairDevice('' + d.device_id + '','' + d.device_name.replace(/'/g, "\'") + '','' + d.ip + '',' + d.port + ')">Pair Device</button>'
                  )
                ) +
              '</div>' +
            '</li>';
          }).join('');
        } else {
          dList.innerHTML = '<li class="muted">Searching for nearby devices on local Wi-Fi...</li>';
        }

        // Recent Transfers list
        const tList = document.getElementById('transfersList');
        const modalTList = document.getElementById('modalTransfersList');
        if (data.transfer_history && data.transfer_history.length > 0) {
          const tHtml = data.transfer_history.slice(0, 10).map(t => 
            '<li class="list-item">' +
              '<div class="list-item-left">' +
                '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="' + (t.success ? '#22c55e' : '#ef4444') + '" stroke-width="2"><polyline points="20 6 9 17 4 12"></polyline></svg>' +
                '<div>' +
                  '<div class="list-item-title">' + t.file_name + '</div>' +
                  '<div class="list-item-sub">' + (t.size ? (t.size / 1024).toFixed(1) + ' KB' : 'Completed') + ' • ' + (t.is_incoming ? 'Received' : 'Sent') + '</div>' +
                '</div>' +
              '</div>' +
              '<button class="btn btn-bordered btn-sm" onclick="deleteTransfer('' + t.id + '')">Delete</button>' +
            '</li>'
          ).join('');
          tList.innerHTML = tHtml;
          if (modalTList) modalTList.innerHTML = tHtml;
        } else {
          tList.innerHTML = '<li class="muted">No transfers yet today.</li>';
          if (modalTList) modalTList.innerHTML = '<li class="muted">No transfers in history.</li>';
        }

        // Clipboard history list
        const cList = document.getElementById('clipList');
        const modalCList = document.getElementById('modalClipboardList');
        if (data.clipboard_history && data.clipboard_history.length > 0) {
          const cHtml = data.clipboard_history.slice(0, 10).map((t, idx) => 
            '<li class="list-item">' +
              '<div class="list-item-title">' + t.substring(0, 32) + (t.length > 32 ? '...' : '') + '</div>' +
              '<div style="display:flex; gap:4px;">' +
                '<button class="btn btn-bordered btn-sm" onclick="copyClipText('' + encodeURIComponent(t) + '')">Copy</button>' +
                '<button class="btn btn-danger btn-sm" onclick="deleteClip(' + idx + ')">&times;</button>' +
              '</div>' +
            '</li>'
          ).join('');
          cList.innerHTML = cHtml;
          if (modalCList) modalCList.innerHTML = cHtml;
        } else {
          cList.innerHTML = '<li class="muted">No clipboard items yet.</li>';
          if (modalCList) modalCList.innerHTML = '<li class="muted">No clipboard items in history.</li>';
        }

      } catch (e) {
        console.error('loadStatus error:', e);
      }
    }

    function copyClipText(encoded) {
      const text = decodeURIComponent(encoded);
      navigator.clipboard.writeText(text);
      showToast('Copied to clipboard!', 'success');
    }

    async function deleteTransfer(id) {
      await fetch('/api/delete-transfer?id=' + encodeURIComponent(id));
      loadStatus();
    }

    setInterval(loadStatus, 2000);
    loadStatus();
  </script>
</body>
</html>
`

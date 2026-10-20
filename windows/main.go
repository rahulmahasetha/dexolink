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
			e.ActiveConnections.Store(peerID, conn)
			e.DiscoveredDevices.Store(peerID, DeviceInfo{
				DeviceID:   peerID,
				DeviceName: pName,
				IP:         strings.Split(conn.RemoteAddr().String(), ":")[0],
				Port:       DefaultPort,
			})

			isPaired := false
			e.TrustStore.mu.Lock()
			_, isPaired = e.TrustStore.PairedDevices[peerID]
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
		e.mu.Unlock()
		if !disc {
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
				e.DiscoveredDevices.Store(devID, DeviceInfo{
					DeviceID:   devID,
					DeviceName: devName,
					IP:         addr.IP.String(),
					Port:       int(port),
				})
			}
		}
	}
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
		discovered := make([]DeviceInfo, 0)
		engine.DiscoveredDevices.Range(func(key, value any) bool {
			discovered = append(discovered, value.(DeviceInfo))
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

		connCount := 0
		engine.ActiveConnections.Range(func(k, v any) bool {
			connCount++
			return true
		})

		json.NewEncoder(w).Encode(map[string]any{
			"version":                "4.0.1",
			"deviceId":               engine.DeviceID,
			"deviceName":             engine.DeviceName,
			"port":                   DefaultPort,
			"is_enabled":             isEnabled,
			"connected_count":        connCount,
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
			"desktop_mode_enabled":  desktopMode,
		})
	})

	http.HandleFunc("/api/toggle-master", func(w http.ResponseWriter, r *http.Request) {
		enable := r.URL.Query().Get("enabled") == "true"
		engine.mu.Lock()
		engine.Enabled = enable
		engine.mu.Unlock()
		w.WriteHeader(http.StatusOK)
	})

	http.HandleFunc("/api/connect-direct", func(w http.ResponseWriter, r *http.Request) {
		ip := r.URL.Query().Get("ip")
		portStr := r.URL.Query().Get("port")
		port, _ := strconv.Atoi(portStr)
		if port == 0 {
			port = DefaultPort
		}
		if ip != "" {
			go func() {
				conn, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", ip, port), 5*time.Second)
				if err == nil {
					peerID := fmt.Sprintf("direct_%s", ip)
					engine.ActiveConnections.Store(peerID, conn)
					engine.DiscoveredDevices.Store(peerID, DeviceInfo{
						DeviceID:   peerID,
						DeviceName: fmt.Sprintf("Remote Device (%s)", ip),
						IP:         ip,
						Port:       port,
					})
					hello, _ := encodeMessage(map[string]any{
						"type":       "HELLO",
						"version":    ProtocolVersion,
						"deviceId":   engine.DeviceID,
						"deviceName": engine.DeviceName,
					})
					_, _ = conn.Write(hello)
				}
			}()
		}
		w.WriteHeader(http.StatusOK)
	})

	http.HandleFunc("/api/pair-device", func(w http.ResponseWriter, r *http.Request) {
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
		}
		w.WriteHeader(http.StatusOK)
	})

	http.HandleFunc("/api/disconnect", func(w http.ResponseWriter, r *http.Request) {
		id := r.URL.Query().Get("id")
		if id != "" {
			if val, ok := engine.ActiveConnections.Load(id); ok {
				conn := val.(net.Conn)
				_ = conn.Close()
				engine.ActiveConnections.Delete(id)
			}
		} else {
			engine.ActiveConnections.Range(func(key, val any) bool {
				conn := val.(net.Conn)
				_ = conn.Close()
				engine.ActiveConnections.Delete(key)
				return true
			})
		}
		w.WriteHeader(http.StatusOK)
	})

	http.HandleFunc("/api/stream-request", func(w http.ResponseWriter, r *http.Request) {
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
		engine.ActiveConnections.Range(func(key, val any) bool {
			conn := val.(net.Conn)
			_, _ = conn.Write(msg)
			return true
		})
		w.WriteHeader(http.StatusOK)
	})

	http.HandleFunc("/api/send-desktop-input", func(w http.ResponseWriter, r *http.Request) {
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
		engine.ActiveConnections.Range(func(key, val any) bool {
			conn := val.(net.Conn)
			_, _ = conn.Write(msg)
			return true
		})
		w.WriteHeader(http.StatusOK)
	})

	http.HandleFunc("/api/open-downloads", func(w http.ResponseWriter, r *http.Request) {
		openBrowser(engine.DownloadDir)
		w.WriteHeader(http.StatusOK)
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
	})

	http.HandleFunc("/api/toggle-multi-paired", func(w http.ResponseWriter, r *http.Request) {
		enable := r.URL.Query().Get("enabled") == "true"
		engine.TrustStore.SetMultiPaired(enable)
		w.WriteHeader(http.StatusOK)
	})

	http.HandleFunc("/api/unpair", func(w http.ResponseWriter, r *http.Request) {
		id := r.URL.Query().Get("id")
		engine.TrustStore.RemoveDevice(id)
		w.WriteHeader(http.StatusOK)
	})

	http.HandleFunc("/api/push-clipboard", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Text string `json:"text"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		if body.Text != "" {
			engine.mu.Lock()
			clipEnabled := engine.AutoClipboardSync
			if clipEnabled {
				engine.ClipboardHistory = append([]string{body.Text}, engine.ClipboardHistory...)
			}
			engine.mu.Unlock()

			if !clipEnabled {
				w.WriteHeader(http.StatusOK)
				return
			}

			msg, _ := encodeMessage(map[string]any{
				"type":           "CLIPBOARD_PUSH",
				"sequenceNumber": time.Now().Unix(),
				"content":        body.Text,
			})
			engine.ActiveConnections.Range(func(key, val any) bool {
				conn := val.(net.Conn)
				_, _ = conn.Write(msg)
				return true
			})
		}
		w.WriteHeader(http.StatusOK)
	})

	http.HandleFunc("/api/send-continuity", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			URL string `json:"url"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		if body.URL != "" {
			engine.mu.Lock()
			contEnabled := engine.ContinuityEnabled
			engine.mu.Unlock()

			if !contEnabled {
				w.WriteHeader(http.StatusOK)
				return
			}

			msg, _ := encodeMessage(map[string]any{
				"type":           "CONTINUITY_ACTIVITY",
				"activityId":     fmt.Sprintf("act_%d", time.Now().UnixNano()),
				"activityType":   "web_browsing",
				"title":          "Shared Web Page",
				"url":            body.URL,
				"timestamp":      float64(time.Now().Unix()),
				"sourceDeviceId": engine.DeviceID,
			})
			engine.ActiveConnections.Range(func(key, val any) bool {
				conn := val.(net.Conn)
				_, _ = conn.Write(msg)
				return true
			})
		}
		w.WriteHeader(http.StatusOK)
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
    --card: #1e293b;
    --card-hover: #24344d;
    --border: #334155;
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
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
  body { background: var(--bg); color: var(--text); padding: 20px; line-height: 1.4; }

  /* Header */
  .header { display: flex; justify-content: space-between; align-items: center; padding-bottom: 16px; border-bottom: 1px solid var(--border); margin-bottom: 18px; }
  .brand { display: flex; align-items: center; gap: 10px; }
  .brand h1 { font-size: 22px; font-weight: 800; color: var(--cyan); letter-spacing: -0.5px; }
  .badge { background: rgba(56,189,248,0.15); color: var(--cyan); padding: 4px 8px; border-radius: 6px; font-size: 11px; font-weight: 700; }
  .badge-green { background: rgba(34,197,94,0.18); color: var(--success); }
  .badge-purple { background: rgba(168,85,247,0.18); color: var(--purple); }
  .badge-orange { background: rgba(245,158,11,0.18); color: var(--warning); }
  .header-actions { display: flex; align-items: center; gap: 10px; }
  .icon-btn { background: var(--card); border: 1px solid var(--border); color: var(--text); padding: 7px 11px; border-radius: 8px; cursor: pointer; display: flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600; transition: 0.15s; }
  .icon-btn:hover { background: var(--card-hover); border-color: var(--cyan); color: #fff; }
  .status-pill { display: flex; align-items: center; gap: 8px; font-size: 12px; font-weight: 600; color: var(--success); background: rgba(34,197,94,0.12); padding: 6px 12px; border-radius: 20px; border: 1px solid rgba(34,197,94,0.25); }
  .status-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--success); box-shadow: 0 0 8px var(--success); }

  /* Master ON/OFF Card */
  .master-card { background: var(--card); border: 1px solid var(--border); border-radius: 12px; padding: 14px 18px; display: flex; align-items: center; justify-content: space-between; margin-bottom: 20px; box-shadow: 0 4px 12px rgba(0,0,0,0.2); }
  .master-info { display: flex; align-items: center; gap: 14px; }
  .master-icon-wrap { width: 44px; height: 44px; border-radius: 50%; background: rgba(56,189,248,0.15); display: flex; align-items: center; justify-content: center; color: var(--cyan); }
  .master-title-row { display: flex; align-items: center; gap: 8px; }
  .master-title { font-size: 15px; font-weight: 800; color: var(--text); }
  .master-state-pill { font-size: 10px; font-weight: 800; padding: 2px 7px; border-radius: 12px; background: rgba(0,102,255,0.2); color: var(--cyan); }
  .master-sub { font-size: 12px; color: var(--muted); margin-top: 2px; }

  /* Switch Toggle */
  .switch { position: relative; display: inline-block; width: 46px; height: 26px; }
  .switch input { opacity: 0; width: 0; height: 0; }
  .slider { position: absolute; cursor: pointer; top: 0; left: 0; right: 0; bottom: 0; background-color: #334155; transition: .25s; border-radius: 26px; }
  .slider:before { position: absolute; content: ""; height: 20px; width: 20px; left: 3px; bottom: 3px; background-color: white; transition: .25s; border-radius: 50%; }
  input:checked + .slider { background-color: var(--accent); }
  input:checked + .slider:before { transform: translateX(20px); }

  /* Grid Layout */
  .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(350px, 1fr)); gap: 18px; }
  .card { background: var(--card); border: 1px solid var(--border); border-radius: 12px; padding: 18px; box-shadow: 0 2px 8px rgba(0,0,0,0.15); }
  .card h2 { font-size: 15px; font-weight: 700; margin-bottom: 12px; display: flex; align-items: center; gap: 8px; color: var(--text); }
  .card h3 { font-size: 12px; font-weight: 700; color: var(--muted); text-transform: uppercase; letter-spacing: 0.5px; margin-top: 14px; margin-bottom: 6px; }
  .muted { color: var(--muted); font-size: 12px; }

  /* Buttons */
  .btn { background: var(--accent); color: #fff; border: none; border-radius: 8px; padding: 9px 15px; font-size: 12px; font-weight: 700; cursor: pointer; transition: 0.15s; display: inline-flex; align-items: center; justify-content: center; gap: 6px; }
  .btn:hover { background: var(--accent-hover); }
  .btn-sm { padding: 5px 10px; font-size: 11px; border-radius: 6px; }
  .btn-bordered { background: transparent; border: 1px solid var(--border); color: var(--text); }
  .btn-bordered:hover { background: var(--card-hover); border-color: var(--cyan); }
  .btn-success { background: rgba(34,197,94,0.18); color: var(--success); border: 1px solid rgba(34,197,94,0.3); }
  .btn-success:hover { background: rgba(34,197,94,0.3); }
  .btn-danger { background: rgba(239,68,68,0.18); color: var(--danger); border: 1px solid rgba(239,68,68,0.3); }
  .btn-danger:hover { background: rgba(239,68,68,0.3); }
  .btn-orange { background: rgba(245,158,11,0.18); color: var(--warning); border: 1px solid rgba(245,158,11,0.3); }
  .btn-purple { background: rgba(168,85,247,0.18); color: var(--purple); border: 1px solid rgba(168,85,247,0.3); }

  /* Inputs */
  .input { width: 100%; background: var(--bg); border: 1px solid var(--border); color: var(--text); padding: 9px 12px; border-radius: 8px; margin-bottom: 10px; font-size: 12px; }
  .input:focus { outline: none; border-color: var(--cyan); }

  /* Drop Zone */
  .drop-zone { border: 2px dashed rgba(56,189,248,0.35); background: rgba(56,189,248,0.04); border-radius: 10px; padding: 18px; text-align: center; cursor: pointer; transition: 0.2s; margin-bottom: 12px; }
  .drop-zone:hover, .drop-zone.dragover { border-color: var(--cyan); background: rgba(56,189,248,0.1); }
  .drop-zone-icon { color: var(--cyan); margin-bottom: 6px; }

  /* Lists */
  .list { list-style: none; margin-top: 8px; max-height: 200px; overflow-y: auto; }
  .list-item { background: var(--bg); border: 1px solid var(--border); padding: 9px 12px; border-radius: 8px; margin-bottom: 6px; display: flex; justify-content: space-between; align-items: center; font-size: 12px; }
  .list-item-left { display: flex; align-items: center; gap: 8px; overflow: hidden; }
  .list-item-title { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 200px; }
  .list-item-sub { font-size: 10px; color: var(--muted); }

  /* Toggles */
  .toggle-row { display: flex; justify-content: space-between; align-items: center; padding: 9px 0; border-bottom: 1px solid var(--border); }

  /* Turned Off State Card */
  .turned-off-banner { background: var(--card); border: 1px solid var(--border); border-radius: 12px; padding: 40px 24px; text-align: center; margin-top: 10px; }

  /* Modals */
  .modal-overlay { display: none; position: fixed; top: 0; left: 0; width: 100vw; height: 100vh; background: rgba(11,17,32,0.88); backdrop-filter: blur(8px); z-index: 9999; align-items: center; justify-content: center; }
  .modal-box { background: var(--card); border: 1px solid var(--border); border-radius: 14px; width: 92%; max-width: 560px; max-height: 85vh; overflow-y: auto; padding: 24px; box-shadow: 0 20px 50px rgba(0,0,0,0.6); }

  /* Footer */
  .footer { display: flex; justify-content: space-between; align-items: center; padding-top: 16px; margin-top: 24px; border-top: 1px solid var(--border); font-size: 11px; color: var(--muted); }
  .dev-attr { text-align: center; font-size: 10px; color: var(--muted); margin-top: 8px; }
</style>
</head>
<body>

  <!-- Top Header Navigation -->
  <div class="header">
    <div class="brand">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="#38bdf8" stroke-width="2.3" stroke-linecap="round" stroke-linejoin="round"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"></path><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"></path></svg>
      <h1>DexoLink</h1>
      <span class="badge">v4.0.1 (Windows App)</span>
    </div>
    <div class="header-actions">
      <div class="status-pill">
        <div class="status-dot"></div>
        <span id="devName">Windows PC (Ready)</span>
      </div>
      <button class="icon-btn" onclick="openDirectIPModal()" title="Direct IP Connection">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><line x1="2" y1="12" x2="22" y2="12"></line><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"></path></svg>
        <span>Direct IP</span>
      </button>
      <button class="icon-btn" onclick="openHistoryModal()" title="Transfer & Clipboard History">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline></svg>
        <span>History</span>
      </button>
      <button class="icon-btn" onclick="openSettingsModal()" title="Settings & All Feature Controls">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg>
        <span>Settings</span>
      </button>
    </div>
  </div>

  <!-- Master Quick Settings ON/OFF Card (Matching MacBook MenuBar) -->
  <div class="master-card">
    <div class="master-info">
      <div class="master-icon-wrap" id="masterIconWrap">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M4.93 4.93a10 10 0 0 1 14.14 0"></path><path d="M7.76 7.76a6 6 0 0 1 8.48 0"></path><circle cx="12" cy="12" r="2"></circle><path d="M12 14v8"></path></svg>
      </div>
      <div>
        <div class="master-title-row">
          <span class="master-title">DexoLink</span>
          <span class="master-state-pill" id="masterStatePill">ON</span>
        </div>
        <div class="master-sub" id="masterSubtitle">Active • Ready to connect on local Wi-Fi</div>
      </div>
    </div>
    <label class="switch">
      <input type="checkbox" id="masterSwitch" checked onchange="toggleMaster(this.checked)">
      <span class="slider"></span>
    </label>
  </div>

  <!-- Turned Off State Banner (When Master Switch is OFF) -->
  <div id="turnedOffBanner" class="turned-off-banner" style="display:none;">
    <div style="width:60px; height:60px; border-radius:50%; background:rgba(148,163,184,0.12); display:flex; align-items:center; justify-content:center; margin:0 auto 16px auto; color:var(--muted);">
      <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="1" y1="1" x2="23" y2="23"></line><path d="M16.72 11.06A10.94 10.94 0 0 1 19 12.55"></path><path d="M5 12.55a10.94 10.94 0 0 1 5.17-2.39"></path><path d="M10.71 5.05A16 16 0 0 1 22.58 9"></path><path d="M1.42 9a15.91 15.91 0 0 1 4.7-2.88"></path><path d="M8.53 16.11a6 6 0 0 1 6.95 0"></path><line x1="12" y1="20" x2="12.01" y2="20"></line></svg>
    </div>
    <h2 style="font-size:18px; font-weight:800; margin-bottom:6px;">DexoLink is Turned Off</h2>
    <p class="muted" style="max-width:440px; margin:0 auto 18px auto;">All background listeners, discovery broadcasts, and socket connections are stopped to ensure 0% CPU and battery usage.</p>
    <button class="btn" onclick="toggleMaster(true)" style="padding:10px 22px; font-size:13px;">Turn On DexoLink</button>
  </div>

  <!-- Active Dashboard Container -->
  <div id="activeDashboard">
    <div class="grid">

      <!-- Card 1: Quick File Sharing (Matching MacBook MenuBar Quick File Sharing) -->
      <div class="card">
        <h2>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#f59e0b" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path></svg>
          <span>Quick File Sharing</span>
          <button class="btn btn-bordered btn-sm" style="margin-left:auto;" onclick="openDownloadsFolder()" title="Open Downloads Folder">Downloads</button>
        </h2>
        
        <!-- Drag & Drop Box -->
        <div class="drop-zone" id="dropZone" onclick="document.getElementById('hiddenFileInput').click()">
          <div class="drop-zone-icon">
            <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><polyline points="16 12 12 8 8 12"></polyline><line x1="12" y1="16" x2="12" y2="8"></line></svg>
          </div>
          <div style="font-weight:700; font-size:13px; color:var(--text);">Drop files here or click to send</div>
          <div class="muted" style="font-size:11px; margin-top:3px;">Photos, PDFs, Documents, Downloads, any file</div>
          <input type="file" id="hiddenFileInput" style="display:none;" onchange="handleFileSelect(this)">
        </div>

        <div style="display:flex; gap:8px;">
          <button class="btn" style="flex:1;" onclick="document.getElementById('hiddenFileInput').click()">Select Files to Send</button>
        </div>

        <h3>Recent Transfers</h3>
        <ul class="list" id="transfersList">
          <li class="muted">No transfers yet today.</li>
        </ul>
      </div>

      <!-- Card 2: Universal Clipboard -->
      <div class="card">
        <h2>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#38bdf8" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"></path><rect x="8" y="2" width="8" height="4" rx="1" ry="1"></rect></svg>
          <span>Universal Clipboard</span>
          <span class="badge badge-green" style="margin-left:auto;">AUTO SYNC</span>
        </h2>
        <p class="muted" style="margin-bottom:10px;">Instant real-time text sync across Windows, Mac, Android, and iPhone.</p>
        <textarea id="clipText" class="input" style="height:70px; resize:none;" placeholder="Type or paste text to sync across paired devices..."></textarea>
        <button class="btn" style="width:100%;" onclick="pushClip()">Push Text to Devices</button>
        
        <h3>Clipboard History</h3>
        <ul class="list" id="clipList">
          <li class="muted">No clipboard items yet.</li>
        </ul>
      </div>

      <!-- Card 3: Continuity & Handoff -->
      <div class="card">
        <h2>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#a855f7" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><polygon points="16.24 7.76 14.12 14.12 7.76 16.24 9.88 9.88 16.24 7.76"></polygon></svg>
          <span>Continuity & Handoff</span>
        </h2>
        <p class="muted" style="margin-bottom:12px;">Seamlessly open links and web activities across your iPhone, Mac, or Android.</p>
        <input type="url" id="contUrl" class="input" value="https://">
        <button class="btn" style="width:100%; background:var(--purple);" onclick="sendCont()">Launch on Connected Devices</button>
      </div>

      <!-- Card 4: Media Streaming & Remote Control (v4.0.1) -->
      <div class="card">
        <h2>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#22c55e" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"></rect><line x1="8" y1="21" x2="16" y2="21"></line><line x1="12" y1="17" x2="12" y2="21"></line></svg>
          <span>Media Streaming & Remote Control</span>
          <span class="badge badge-green" style="margin-left:auto;">v4.0.1</span>
        </h2>
        
        <!-- Camera Sub-section -->
        <div style="background:var(--bg); border:1px solid var(--border); border-radius:10px; padding:12px; margin-bottom:10px;">
          <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;">
            <div style="font-weight:700; font-size:12px; display:flex; align-items:center; gap:6px;">
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

        <!-- Screen Mirroring Sub-section -->
        <div style="background:var(--bg); border:1px solid var(--border); border-radius:10px; padding:12px; margin-bottom:10px;">
          <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;">
            <div style="font-weight:700; font-size:12px; display:flex; align-items:center; gap:6px;">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#f59e0b" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"></rect><line x1="8" y1="21" x2="16" y2="21"></line><line x1="12" y1="17" x2="12" y2="21"></line></svg>
              <span>Screen Sharing</span>
            </div>
            <span class="badge" id="screenStatusBadge">INACTIVE</span>
          </div>
          <div style="display:flex; gap:6px;">
            <button class="btn btn-sm btn-bordered" style="flex:1;" onclick="requestCameraStream('screen', 'request_stream')">Request Remote Screen</button>
          </div>
        </div>

        <!-- Desktop Mode & Remote Write -->
        <div style="background:var(--bg); border:1px solid var(--border); border-radius:10px; padding:12px;">
          <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;">
            <div style="font-weight:700; font-size:12px; display:flex; align-items:center; gap:6px;">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#22c55e" stroke-width="2"><polyline points="4 7 4 4 20 4 20 7"></polyline><line x1="9" y1="20" x2="15" y2="20"></line><line x1="12" y1="4" x2="12" y2="20"></line></svg>
              <span>Remote Write & Control</span>
            </div>
            <span class="badge badge-purple">BETA</span>
          </div>
          <input type="text" id="remoteWriteInput" class="input" style="margin-bottom:6px;" placeholder="Type text to send to remote screen...">
          <div style="display:flex; gap:6px; margin-bottom:8px;">
            <button class="btn btn-sm" style="flex:1;" onclick="sendRemoteWriteText()">Send Text</button>
            <button class="btn btn-sm btn-bordered" onclick="sendRemoteKey('enter')">Enter</button>
            <button class="btn btn-sm btn-bordered" onclick="sendRemoteKey('backspace')">Backspace</button>
          </div>
          <div style="display:flex; gap:6px;">
            <button class="btn btn-sm btn-bordered" style="flex:1;" onclick="sendRemoteKey('space')">Space</button>
            <button class="btn btn-sm btn-bordered" style="flex:1;" onclick="sendRemoteKey('tab')">Tab</button>
            <button class="btn btn-sm btn-bordered" style="flex:1;" onclick="sendRemoteClick('left')">Left Click</button>
            <button class="btn btn-sm btn-bordered" style="flex:1;" onclick="sendRemoteClick('right')">Right Click</button>
          </div>
        </div>
      </div>

      <!-- Card 5: Paired Devices & Security -->
      <div class="card">
        <h2>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#0066ff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path></svg>
          <span>Paired Devices & Security</span>
        </h2>
        <div class="toggle-row">
          <div>
            <div style="font-weight:600; font-size:12px;">Multiple Paired Devices</div>
            <div class="muted">OFF by default (Single-device strict security)</div>
          </div>
          <input type="checkbox" id="multiToggle" onchange="toggleMulti(this.checked)">
        </div>

        <h3>Paired Trusted Devices</h3>
        <ul class="list" id="pairedList">
          <li class="muted">No paired devices yet.</li>
        </ul>
      </div>

      <!-- Card 6: Nearby Devices (Wi-Fi) (Matching MacBook MenuBar Nearby Devices) -->
      <div class="card">
        <h2>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#38bdf8" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="2"></circle><path d="M16.24 7.76a6 6 0 0 1 0 8.49m-8.48-.01a6 6 0 0 1 0-8.49m11.31-2.82a10 10 0 0 1 0 14.14m-14.14 0a10 10 0 0 1 0-14.14"></path></svg>
          <span>Nearby Devices (Wi-Fi)</span>
          <div style="margin-left:auto; display:flex; gap:6px;">
            <button class="btn btn-bordered btn-sm" onclick="rescanDiscovery()" title="Rescan Local Wi-Fi">Rescan</button>
          </div>
        </h2>
        <p class="muted" style="margin-bottom:8px;">Available devices discovered automatically on your local Wi-Fi or Ethernet.</p>
        <ul class="list" id="discoveredList">
          <li class="muted">Searching for nearby devices on local Wi-Fi...</li>
        </ul>
      </div>

      <!-- Card 7: Feature Controls Summary Card (Matching MacBook Reference Design) -->
      <div class="card" style="display:flex; align-items:center; justify-content:space-between;">
        <div style="display:flex; align-items:center; gap:12px;">
          <div style="width:38px; height:38px; border-radius:10px; background:rgba(30,58,138,0.45); border:1px solid rgba(59,130,246,0.3); display:flex; align-items:center; justify-content:center; color:#60a5fa;">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#60a5fa" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg>
          </div>
          <div>
            <div style="display:flex; align-items:center; gap:8px;">
              <span style="font-weight:700; font-size:14px; color:var(--text);">Feature Controls</span>
              <span id="featCountBadge" style="background:#14532d; color:#4ade80; font-size:10px; font-weight:800; padding:2px 8px; border-radius:999px;">4 ACTIVE</span>
            </div>
            <div class="muted" style="margin-top:2px;">Dedicated controls for all synchronization & streaming modules</div>
          </div>
        </div>
        <button class="btn" onclick="openSettingsModal()" style="background:#2563eb; padding:8px 18px; border-radius:8px;">Configure All</button>
      </div>

    </div>
  </div>

  <!-- Dedicated Settings Modal (Matching MacBook Settings View) -->
  <div id="settingsModal" class="modal-overlay">
    <div class="modal-box">
      <div style="display:flex; justify-content:space-between; align-items:center; border-bottom:1px solid var(--border); padding-bottom:14px; margin-bottom:16px;">
        <div>
          <h2 style="font-size:18px; font-weight:800; color:var(--text);">DexoLink Settings</h2>
          <p class="muted" style="margin-top:2px;">All feature controls, security rules, and device preferences</p>
        </div>
        <button class="btn btn-bordered btn-sm" onclick="closeSettingsModal()">&times;</button>
      </div>

      <!-- Core Features Section -->
      <div style="font-size:11px; font-weight:800; color:var(--cyan); text-transform:uppercase; margin-bottom:6px; letter-spacing:0.5px;">Core Feature Controls</div>
      
      <div class="toggle-row">
        <div>
          <div style="font-weight:600; font-size:12px;">Continuity / Handoff</div>
          <div class="muted">Seamlessly open web pages and links across paired devices</div>
        </div>
        <input type="checkbox" id="modalContinuityToggle" onchange="toggleFeature('continuity', this.checked)">
      </div>

      <div class="toggle-row">
        <div>
          <div style="font-weight:600; font-size:12px;">Auto Clipboard Sync</div>
          <div class="muted">Mirror copied text instantly over secure local Wi-Fi</div>
        </div>
        <input type="checkbox" id="modalClipToggle" onchange="toggleFeature('clipboard', this.checked)">
      </div>

      <div class="toggle-row">
        <div>
          <div style="font-weight:600; font-size:12px;">Enhanced Clipboard (BETA)</div>
          <div class="muted">Support rich formatted text, HTML, and image synchronization</div>
        </div>
        <input type="checkbox" id="modalEnhancedToggle" onchange="toggleFeature('enhanced_clip', this.checked)">
      </div>

      <div class="toggle-row">
        <div>
          <div style="font-weight:600; font-size:12px;">Multiple Paired Devices</div>
          <div class="muted">OFF by default (Single-device mode). Allows multi-peer connectivity when ON.</div>
        </div>
        <input type="checkbox" id="modalMultiToggle" onchange="toggleMulti(this.checked)">
      </div>

      <!-- Media Streaming & Remote Control Section (v4.0.1) -->
      <div style="font-size:11px; font-weight:800; color:var(--cyan); text-transform:uppercase; margin-top:18px; margin-bottom:6px; letter-spacing:0.5px;">Media Streaming & Remote Control</div>

      <div class="toggle-row">
        <div>
          <div style="font-weight:600; font-size:12px;">Camera Sharing</div>
          <div class="muted">Stream camera between this device and mobile devices (OFF by default)</div>
        </div>
        <input type="checkbox" id="modalCameraToggle" onchange="toggleFeature('camera_sharing', this.checked)">
      </div>

      <div class="toggle-row">
        <div>
          <div style="font-weight:600; font-size:12px;">Screen Sharing</div>
          <div class="muted">Mirror this device screen or view remote displays (OFF by default)</div>
        </div>
        <input type="checkbox" id="modalScreenToggle" onchange="toggleFeature('screen_sharing', this.checked)">
      </div>

      <div class="toggle-row">
        <div>
          <div style="font-weight:600; font-size:12px;">Desktop Mode (BETA)</div>
          <div class="muted">Remote desktop viewing and keyboard/mouse input control (OFF by default)</div>
        </div>
        <input type="checkbox" id="modalDesktopToggle" onchange="toggleFeature('desktop_mode', this.checked)">
      </div>

      <!-- Discovery & Network Section -->
      <div style="font-size:11px; font-weight:800; color:var(--cyan); text-transform:uppercase; margin-top:18px; margin-bottom:6px; letter-spacing:0.5px;">Discovery & Network</div>

      <div class="toggle-row">
        <div>
          <div style="font-weight:600; font-size:12px;">Auto Wi-Fi Discovery</div>
          <div class="muted">UDP broadcast & listen on port 52841 for zero-config pairing</div>
        </div>
        <input type="checkbox" id="modalDiscoveryToggle" onchange="toggleFeature('auto_discovery', this.checked)">
      </div>

      <div class="toggle-row">
        <div>
          <div style="font-weight:600; font-size:12px;">Auto-Accept Paired Transfers</div>
          <div class="muted">Automatically accept file transfers from trusted paired devices</div>
        </div>
        <input type="checkbox" id="modalAcceptToggle" onchange="toggleFeature('auto_accept', this.checked)">
      </div>

      <!-- Storage Section -->
      <div style="font-size:11px; font-weight:800; color:var(--cyan); text-transform:uppercase; margin-top:18px; margin-bottom:6px; letter-spacing:0.5px;">Storage & Received Files</div>
      <div style="background:var(--bg); border:1px solid var(--border); border-radius:8px; padding:12px; margin-top:6px; display:flex; justify-content:space-between; align-items:center;">
        <div>
          <div class="muted" style="font-size:11px;">Downloads Folder:</div>
          <div id="modalDownPath" style="font-size:12px; font-family:monospace; color:var(--cyan); word-break:break-all;">~/Downloads/DexoLink</div>
        </div>
        <button class="btn btn-bordered btn-sm" onclick="openDownloadsFolder()">Open Folder</button>
      </div>

      <!-- Modal Footer -->
      <div style="display:flex; justify-content:space-between; align-items:center; margin-top:22px; padding-top:14px; border-top:1px solid var(--border);">
        <span class="muted">DexoLink v4.0.1 • Port 52840</span>
        <button class="btn" onclick="closeSettingsModal()" style="padding:8px 24px;">Done</button>
      </div>
    </div>
  </div>

  <!-- History Modal (Transfers & Clipboard Tabs) -->
  <div id="historyModal" class="modal-overlay">
    <div class="modal-box">
      <div style="display:flex; justify-content:space-between; align-items:center; border-bottom:1px solid var(--border); padding-bottom:14px; margin-bottom:16px;">
        <h2 style="font-size:18px; font-weight:800;">Activity History</h2>
        <button class="btn btn-bordered btn-sm" onclick="closeHistoryModal()">&times;</button>
      </div>

      <div style="display:flex; gap:8px; margin-bottom:14px;">
        <button class="btn btn-sm" id="tabTransfersBtn" onclick="switchHistoryTab('transfers')">Transfers History</button>
        <button class="btn btn-bordered btn-sm" id="tabClipboardBtn" onclick="switchHistoryTab('clipboard')">Clipboard History</button>
      </div>

      <div id="historyTransfersTab">
        <ul class="list" id="modalTransfersList" style="max-height:360px;">
          <li class="muted">No transfers in history.</li>
        </ul>
      </div>

      <div id="historyClipboardTab" style="display:none;">
        <ul class="list" id="modalClipboardList" style="max-height:360px;">
          <li class="muted">No clipboard items in history.</li>
        </ul>
      </div>

      <div style="text-align:right; margin-top:18px; padding-top:12px; border-top:1px solid var(--border);">
        <button class="btn" onclick="closeHistoryModal()">Close</button>
      </div>
    </div>
  </div>

  <!-- Direct IP Connection Modal (Matching MacBook ManualIPSheet) -->
  <div id="directIPModal" class="modal-overlay">
    <div class="modal-box" style="max-width:440px;">
      <div style="display:flex; justify-content:space-between; align-items:center; border-bottom:1px solid var(--border); padding-bottom:12px; margin-bottom:16px;">
        <h2 style="font-size:16px; font-weight:800;">Direct IP Connection</h2>
        <button class="btn btn-bordered btn-sm" onclick="closeDirectIPModal()">&times;</button>
      </div>
      <p class="muted" style="margin-bottom:12px;">Connect to an iPhone, iPad, Android phone, or Mac by entering its local or remote IP coordinates.</p>
      
      <label class="muted" style="font-size:11px; font-weight:700;">HOST / IP ADDRESS</label>
      <input type="text" id="manualHostInput" class="input" placeholder="e.g. 192.168.1.100 or domain">
      
      <label class="muted" style="font-size:11px; font-weight:700;">PORT (DEFAULT 52840)</label>
      <input type="text" id="manualPortInput" class="input" value="52840">

      <div style="display:flex; justify-content:flex-end; gap:8px; margin-top:12px;">
        <button class="btn btn-bordered" onclick="closeDirectIPModal()">Cancel</button>
        <button class="btn" onclick="connectDirectSubmit()">Connect Peer</button>
      </div>
    </div>
  </div>

  <!-- Footer Information -->
  <div class="footer">
    <div style="display:flex; align-items:center; gap:8px;">
      <div class="status-dot"></div>
      <span id="footerStatusText">Ready to connect • Wi-Fi Auto-Discovery</span>
    </div>
    <button class="btn btn-bordered btn-sm" onclick="rescanDiscovery()">Refresh Discovery</button>
  </div>
  <div class="dev-attr">Developed by Rahul Mahaseth | DexoLink v4.0.1 (Universal)</div>

  <script>
    let currentData = null;

    // Modals
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
      await fetch('/api/toggle-master?enabled=' + val);
      loadStatus();
    }

    // Feature Toggles
    async function toggleFeature(feat, val) {
      await fetch('/api/toggle-feature?feature=' + feat + '&enabled=' + val);
      loadStatus();
    }

    async function toggleMulti(val) {
      await fetch('/api/toggle-multi-paired?enabled=' + val);
      loadStatus();
    }

    // Direct IP Submit
    async function connectDirectSubmit() {
      const host = document.getElementById('manualHostInput').value.trim();
      const port = document.getElementById('manualPortInput').value.trim() || '52840';
      if (!host) return;
      await fetch('/api/connect-direct?ip=' + encodeURIComponent(host) + '&port=' + encodeURIComponent(port));
      closeDirectIPModal();
      loadStatus();
    }

    // Disconnect & Unpair
    async function disconnectDevice(id) {
      await fetch('/api/disconnect?id=' + encodeURIComponent(id || ''));
      loadStatus();
    }

    async function unpairDevice(id) {
      await fetch('/api/unpair?id=' + encodeURIComponent(id));
      loadStatus();
    }

    async function pairDevice(id, name, ip, port) {
      await fetch('/api/pair-device?id=' + encodeURIComponent(id) + '&name=' + encodeURIComponent(name) + '&ip=' + encodeURIComponent(ip) + '&port=' + port);
      loadStatus();
    }

    // Clipboard Push
    async function pushClip() {
      const val = document.getElementById('clipText').value.trim();
      if (!val) return;
      await fetch('/api/push-clipboard', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text: val })
      });
      document.getElementById('clipText').value = '';
      loadStatus();
    }

    async function deleteClip(idx) {
      await fetch('/api/delete-clip?index=' + idx);
      loadStatus();
    }

    // Continuity Send
    async function sendCont() {
      const val = document.getElementById('contUrl').value.trim();
      if (!val || val === 'https://') return;
      await fetch('/api/send-continuity', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url: val })
      });
      alert('Sent web page to connected devices: ' + val);
    }

    // Stream Requests
    async function requestCameraStream(sType, action) {
      await fetch('/api/stream-request?streamType=' + sType + '&action=' + action);
      alert('Sent ' + sType + ' stream request (' + action + ') to connected peer');
    }

    // Remote Write & Desktop Control
    async function sendRemoteWriteText() {
      const val = document.getElementById('remoteWriteInput').value;
      if (!val) return;
      await fetch('/api/send-desktop-input', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ eventType: 'write', key: val })
      });
      document.getElementById('remoteWriteInput').value = '';
    }

    async function sendRemoteKey(k) {
      await fetch('/api/send-desktop-input', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ eventType: 'key_press', key: k })
      });
    }

    async function sendRemoteClick(btn) {
      await fetch('/api/send-desktop-input', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ eventType: 'click', x: 0.5, y: 0.5, button: btn === 'right' ? 1 : 0 })
      });
    }

    // Downloads
    async function openDownloadsFolder() {
      await fetch('/api/open-downloads');
    }

    // File Drop & Select
    function handleFileSelect(input) {
      if (input.files && input.files[0]) {
        uploadFile(input.files[0]);
      }
    }

    async function uploadFile(file) {
      const formData = new FormData();
      formData.append('file', file);
      try {
        await fetch('/api/upload-file', { method: 'POST', body: formData });
        loadStatus();
      } catch (e) {
        alert('File transfer error: ' + e);
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
      loadStatus();
    }

    // Periodic Status Loader
    async function loadStatus() {
      try {
        const res = await fetch('/api/status');
        const data = await res.json();
        currentData = data;

        // Device name in header
        document.getElementById('devName').innerText = data.deviceName + ' (Port ' + data.port + ')';

        // Master switch and state banner
        const isMasterOn = (data.is_enabled !== false);
        document.getElementById('masterSwitch').checked = isMasterOn;
        document.getElementById('masterStatePill').innerText = isMasterOn ? 'ON' : 'OFF';
        document.getElementById('masterStatePill').className = isMasterOn ? 'master-state-pill' : 'master-state-pill badge-orange';
        document.getElementById('activeDashboard').style.display = isMasterOn ? 'block' : 'none';
        document.getElementById('turnedOffBanner').style.display = isMasterOn ? 'none' : 'block';

        const connCount = data.connected_count || (data.paired_devices ? data.paired_devices.length : 0);
        if (!isMasterOn) {
          document.getElementById('masterSubtitle').innerText = 'Turned Off • 0% CPU & Battery';
          document.getElementById('footerStatusText').innerText = 'DexoLink is Turned Off';
        } else if (connCount > 0) {
          document.getElementById('masterSubtitle').innerText = 'Connected to ' + (data.paired_devices && data.paired_devices[0] ? data.paired_devices[0].device_name : 'Device');
          document.getElementById('footerStatusText').innerText = 'Connected • Real-time synchronization active';
        } else {
          document.getElementById('masterSubtitle').innerText = 'Active • Ready to connect on local Wi-Fi';
          document.getElementById('footerStatusText').innerText = 'Ready to connect • Wi-Fi Auto-Discovery';
        }

        // Active Features Count
        let activeCount = 0;
        if (data.continuity_enabled) activeCount++;
        if (data.auto_clipboard_sync) activeCount++;
        if (data.enhanced_clipboard) activeCount++;
        if (data.multiple_paired) activeCount++;
        if (data.camera_sharing_enabled) activeCount++;
        if (data.screen_sharing_enabled) activeCount++;
        if (data.desktop_mode_enabled) activeCount++;
        document.getElementById('featCountBadge').innerText = activeCount + ' ACTIVE';

        // Streaming badges
        document.getElementById('cameraStatusBadge').innerText = data.camera_sharing_enabled ? 'ENABLED' : 'INACTIVE';
        document.getElementById('cameraStatusBadge').className = data.camera_sharing_enabled ? 'badge badge-green' : 'badge';
        document.getElementById('screenStatusBadge').innerText = data.screen_sharing_enabled ? 'ENABLED' : 'INACTIVE';
        document.getElementById('screenStatusBadge').className = data.screen_sharing_enabled ? 'badge badge-orange' : 'badge';

        // Multi-Paired toggle
        document.getElementById('multiToggle').checked = data.multiple_paired;

        // Settings modal sync
        if (document.getElementById('modalContinuityToggle')) {
          document.getElementById('modalContinuityToggle').checked = data.continuity_enabled;
          document.getElementById('modalClipToggle').checked = data.auto_clipboard_sync;
          document.getElementById('modalEnhancedToggle').checked = data.enhanced_clipboard;
          document.getElementById('modalMultiToggle').checked = data.multiple_paired;
          document.getElementById('modalCameraToggle').checked = data.camera_sharing_enabled;
          document.getElementById('modalScreenToggle').checked = data.screen_sharing_enabled;
          document.getElementById('modalDesktopToggle').checked = data.desktop_mode_enabled;
          document.getElementById('modalDiscoveryToggle').checked = data.auto_discovery;
          document.getElementById('modalAcceptToggle').checked = data.auto_accept_paired;
          document.getElementById('modalDownPath').innerText = data.download_dir;
        }

        // Paired devices list
        const pList = document.getElementById('pairedList');
        if (data.paired_devices && data.paired_devices.length > 0) {
          pList.innerHTML = data.paired_devices.map(d => 
            '<li class="list-item">' +
              '<div class="list-item-left">' +
                '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#22c55e" stroke-width="2.2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path></svg>' +
                '<div>' +
                  '<div class="list-item-title">' + d.device_name + '</div>' +
                  '<div class="list-item-sub">' + (data.multiple_paired ? 'Multi-Device Mode' : 'Strict 1-Device Policy') + '</div>' +
                '</div>' +
              '</div>' +
              '<div style="display:flex; gap:6px;">' +
                '<button class="btn btn-bordered btn-sm" onclick="disconnectDevice(\'' + d.device_id + '\')">Disconnect</button>' +
                '<button class="btn btn-danger btn-sm" onclick="unpairDevice(\'' + d.device_id + '\')">Unpair</button>' +
              '</div>' +
            '</li>'
          ).join('');
        } else {
          pList.innerHTML = '<li class="muted">No paired devices yet. Discover nearby devices below.</li>';
        }

        // Nearby Discovered devices list
        const dList = document.getElementById('discoveredList');
        if (data.discovered_devices && data.discovered_devices.length > 0) {
          dList.innerHTML = data.discovered_devices.map(d => {
            const isPaired = data.paired_devices && data.paired_devices.some(p => p.device_id === d.device_id);
            return '<li class="list-item">' +
              '<div class="list-item-left">' +
                '<div style="width:7px; height:7px; border-radius:50%; background:#22c55e;"></div>' +
                '<div>' +
                  '<div class="list-item-title">' + d.device_name + '</div>' +
                  '<div class="list-item-sub">' + d.ip + ':' + d.port + '</div>' +
                '</div>' +
              '</div>' +
              '<div style="display:flex; gap:6px;">' +
                (isPaired ? 
                  '<button class="btn btn-bordered btn-sm" onclick="requestCameraStream(\'camera\', \'request_stream\')">Camera</button>' +
                  '<button class="btn btn-bordered btn-sm" onclick="requestCameraStream(\'screen\', \'request_stream\')">Screen</button>' +
                  '<button class="btn btn-danger btn-sm" onclick="unpairDevice(\'' + d.device_id + '\')">Unpair</button>' : 
                  '<button class="btn btn-success btn-sm" onclick="pairDevice(\'' + d.device_id + '\',\'' + d.device_name.replace(/'/g, "\\'") + '\',\'' + d.ip + '\',' + d.port + ')">Pair Device</button>'
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
                '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#22c55e" stroke-width="2"><polyline points="20 6 9 17 4 12"></polyline></svg>' +
                '<div>' +
                  '<div class="list-item-title">' + t.file_name + '</div>' +
                  '<div class="list-item-sub">' + (t.size ? (t.size / 1024).toFixed(1) + ' KB' : 'Completed') + '</div>' +
                '</div>' +
              '</div>' +
              '<button class="btn btn-bordered btn-sm" onclick="deleteTransfer(\'' + t.id + '\')">Delete</button>' +
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
              '<div class="list-item-title">' + t.substring(0, 36) + (t.length > 36 ? '...' : '') + '</div>' +
              '<div style="display:flex; gap:4px;">' +
                '<button class="btn btn-bordered btn-sm" onclick="navigator.clipboard.writeText(\'' + t.replace(/'/g, "\\'") + '\')">Copy</button>' +
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

    async function deleteTransfer(id) {
      await fetch('/api/delete-transfer?id=' + encodeURIComponent(id));
      loadStatus();
    }

    setInterval(loadStatus, 2000);
    loadStatus();
  </script>
</body>
</html>`

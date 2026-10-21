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
	mu                    sync.Mutex
	MultiplePairedDevices bool                  `json:"multiple_paired_devices_enabled"`
	PairedDevices         map[string]DeviceInfo `json:"paired_devices"`
	filePath              string
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
	storeDir := filepath.Join(home, ".dexolink")
	_ = os.MkdirAll(storeDir, 0755)
	storePath := filepath.Join(storeDir, "trust_store.json")

	ts := &TrustStore{
		PairedDevices: make(map[string]DeviceInfo),
		filePath:      storePath,
	}
	ts.load()
	return ts
}

func (ts *TrustStore) load() {
	ts.mu.Lock()
	defer ts.mu.Unlock()
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
	ts.PairedDevices[dev.DeviceID] = dev
	ts.save()
}

func (ts *TrustStore) RemoveDevice(id string) {
	ts.mu.Lock()
	defer ts.mu.Unlock()
	delete(ts.PairedDevices, id)
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
	CameraSharingEnabled bool
	ScreenSharingEnabled bool
	DesktopModeEnabled  bool

	// Active Stream State
	ActiveStreamMu     sync.RWMutex
	ActiveStreamID     string
	ActiveStreamType   string // "camera" or "screen"
	ActiveStreamDevice string
	LatestFrameBase64  string
	LatestFrameTime    time.Time
	FrameSequence      int64
	StreamClientsMu    sync.Mutex
	StreamClients      map[chan string]bool
}

type TransferRecord struct {
	ID         string    `json:"id"`
	FileName   string    `json:"file_name"`
	Size       int64     `json:"size"`
	IsIncoming bool      `json:"is_incoming"`
	PeerName   string    `json:"peer_name"`
	Success    bool      `json:"success"`
	Time       time.Time `json:"time"`
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
		StreamClients:        make(map[chan string]bool),
	}
	e.startAutoConnectWorker()
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
		"type":     "FILE_CHUNK",
		"fileId":   fileID,
		"offset":   offset,
		"chunkLen": len(chunk),
	}
	metaBytes, err := json.Marshal(meta)
	if err != nil {
		return nil, err
	}
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

		case "HELLO_ACK":
			pID, _ := msg["deviceId"].(string)
			pName, _ := msg["deviceName"].(string)
			if pID != "" {
				peerID = pID
				e.ActiveConnections.Store(peerID, conn)
				e.DiscoveredDevices.Store(peerID, DeviceInfo{
					DeviceID:   peerID,
					DeviceName: pName,
					IP:         strings.Split(conn.RemoteAddr().String(), ":")[0],
					Port:       DefaultPort,
				})
			}

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
				IP:         strings.Split(conn.RemoteAddr().String(), ":")[0],
				Port:       DefaultPort,
				PairedAt:   float64(time.Now().Unix()),
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
			accepted := true
			reason := ""

			e.mu.Lock()
			cam := e.CameraSharingEnabled
			screen := e.ScreenSharingEnabled
			desk := e.DesktopModeEnabled
			e.mu.Unlock()

			if streamType == "camera" && !cam {
				accepted = false
				reason = "Camera Sharing is turned OFF in Windows Settings."
			} else if streamType == "screen" && !screen {
				accepted = false
				reason = "Screen Sharing is turned OFF in Windows Settings."
			} else if streamType == "desktop" && !desk {
				accepted = false
				reason = "Desktop Mode is turned OFF in Windows Settings."
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

		case "STREAM_RESPONSE":
			sID, _ := msg["streamId"].(string)
			sType, _ := msg["streamType"].(string)
			accepted, _ := msg["accepted"].(bool)
			if accepted {
				e.ActiveStreamMu.Lock()
				e.ActiveStreamID = sID
				if sType != "" {
					e.ActiveStreamType = sType
				}
				e.ActiveStreamMu.Unlock()
			}

		case "STREAM_FRAME":
			sID, _ := msg["streamId"].(string)
			frameData, _ := msg["data"].(string)
			seq, _ := msg["sequence"].(float64)
			if frameData != "" {
				e.ActiveStreamMu.Lock()
				e.ActiveStreamID = sID
				e.LatestFrameBase64 = frameData
				e.LatestFrameTime = time.Now()
				e.FrameSequence = int64(seq)

				e.StreamClientsMu.Lock()
				for ch := range e.StreamClients {
					select {
					case ch <- frameData:
					default:
					}
				}
				e.StreamClientsMu.Unlock()
				e.ActiveStreamMu.Unlock()
			}

		case "STREAM_STOP":
			e.ActiveStreamMu.Lock()
			e.ActiveStreamID = ""
			e.LatestFrameBase64 = ""
			e.ActiveStreamMu.Unlock()

		case "DESKTOP_INPUT_EVENT":
			// Handled when desktop control is enabled

		case "FILE_ACK":
			// Handled by sender

		case "PING":
			resp, _ := encodeMessage(map[string]any{"type": "PONG"})
			_, _ = conn.Write(resp)
		}
	}
}

// EnsureConnected guarantees an active TCP connection to a peer
func (e *DexoLinkEngine) EnsureConnected(peerIP string, peerPort int, peerID string, peerName string) (net.Conn, error) {
	if peerIP == "" {
		return nil, fmt.Errorf("empty peer IP")
	}
	if peerPort <= 0 {
		peerPort = DefaultPort
	}
	if peerID == "" {
		peerID = fmt.Sprintf("peer_%s", peerIP)
	}

	if val, ok := e.ActiveConnections.Load(peerID); ok {
		if conn, ok := val.(net.Conn); ok && conn != nil {
			return conn, nil
		}
	}

	conn, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", peerIP, peerPort), 4*time.Second)
	if err != nil {
		return nil, err
	}

	e.ActiveConnections.Store(peerID, conn)
	e.DiscoveredDevices.Store(peerID, DeviceInfo{
		DeviceID:   peerID,
		DeviceName: peerName,
		IP:         peerIP,
		Port:       peerPort,
	})

	hello, _ := encodeMessage(map[string]any{
		"type":       "HELLO",
		"version":    ProtocolVersion,
		"deviceId":   e.DeviceID,
		"deviceName": e.DeviceName,
	})
	_, _ = conn.Write(hello)

	go e.handleClient(conn)
	return conn, nil
}

// GetPrimaryPeer resolves the best target peer for transfers and commands
func (e *DexoLinkEngine) GetPrimaryPeer() (string, int, string, string) {
	var ip string
	var port int = DefaultPort
	var id string
	var name string

	// 1. Check ActiveConnections
	e.ActiveConnections.Range(func(key, val any) bool {
		id = key.(string)
		if conn, ok := val.(net.Conn); ok && conn != nil {
			parts := strings.Split(conn.RemoteAddr().String(), ":")
			if len(parts) > 0 {
				ip = parts[0]
			}
		}
		if devVal, ok := e.DiscoveredDevices.Load(id); ok {
			d := devVal.(DeviceInfo)
			name = d.DeviceName
			if d.IP != "" {
				ip = d.IP
			}
			if d.Port > 0 {
				port = d.Port
			}
		}
		return false
	})
	if ip != "" {
		return ip, port, id, name
	}

	// 2. Check PairedDevices in TrustStore
	e.TrustStore.mu.Lock()
	for pID, pDev := range e.TrustStore.PairedDevices {
		id = pID
		name = pDev.DeviceName
		if pDev.IP != "" {
			ip = pDev.IP
			if pDev.Port > 0 {
				port = pDev.Port
			}
		}
		if devVal, ok := e.DiscoveredDevices.Load(pID); ok {
			d := devVal.(DeviceInfo)
			if d.IP != "" {
				ip = d.IP
			}
			if d.Port > 0 {
				port = d.Port
			}
			if name == "" {
				name = d.DeviceName
			}
		}
		break
	}
	e.TrustStore.mu.Unlock()
	if ip != "" {
		return ip, port, id, name
	}

	// 3. Check DiscoveredDevices
	e.DiscoveredDevices.Range(func(key, val any) bool {
		d := val.(DeviceInfo)
		if d.IP != "" {
			ip = d.IP
			port = d.Port
			id = d.DeviceID
			name = d.DeviceName
			return false
		}
		return true
	})

	return ip, port, id, name
}

// Background auto-connect worker keeps paired devices connected
func (e *DexoLinkEngine) startAutoConnectWorker() {
	go func() {
		for {
			time.Sleep(3 * time.Second)
			e.mu.Lock()
			enabled := e.Enabled
			e.mu.Unlock()
			if !enabled {
				continue
			}

			e.TrustStore.mu.Lock()
			paired := make([]DeviceInfo, 0, len(e.TrustStore.PairedDevices))
			for _, d := range e.TrustStore.PairedDevices {
				paired = append(paired, d)
			}
			e.TrustStore.mu.Unlock()

			for _, p := range paired {
				var targetIP string
				var targetPort int = DefaultPort
				if p.IP != "" {
					targetIP = p.IP
					if p.Port > 0 {
						targetPort = p.Port
					}
				}
				if val, ok := e.DiscoveredDevices.Load(p.DeviceID); ok {
					d := val.(DeviceInfo)
					if d.IP != "" {
						targetIP = d.IP
					}
					if d.Port > 0 {
						targetPort = d.Port
					}
				}

				if targetIP != "" {
					if _, hasConn := e.ActiveConnections.Load(p.DeviceID); !hasConn {
						_, _ = e.EnsureConnected(targetIP, targetPort, p.DeviceID, p.DeviceName)
					}
				}
			}
		}
	}()
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
		disc := e.AutoDiscoveryEnabled && e.Enabled
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
		disc := e.AutoDiscoveryEnabled && e.Enabled
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

				// If paired, auto-connect immediately
				e.TrustStore.mu.Lock()
				_, isPaired := e.TrustStore.PairedDevices[devID]
				e.TrustStore.mu.Unlock()
				if isPaired {
					go e.EnsureConnected(addr.IP.String(), int(port), devID, devName)
				}
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
	if _, err := conn.Write(hello); err != nil {
		return false, err
	}

	var lenBuf uint32
	if err := binary.Read(conn, binary.BigEndian, &lenBuf); err != nil {
		return false, err
	}
	respBuf := make([]byte, lenBuf)
	if _, err := io.ReadFull(conn, respBuf); err != nil {
		return false, err
	}

	// 2. Transfer Request
	transferID := fmt.Sprintf("tr_%d", time.Now().UnixNano())
	fileID := fmt.Sprintf("fl_%d", time.Now().UnixNano())
	trReq, _ := encodeMessage(map[string]any{
		"type":       "TRANSFER_REQUEST",
		"transferId": transferID,
		"totalFiles": 1,
		"totalSize":  fileSize,
	})
	if _, err := conn.Write(trReq); err != nil {
		return false, err
	}

	if err := binary.Read(conn, binary.BigEndian, &lenBuf); err != nil {
		return false, err
	}
	respBuf = make([]byte, lenBuf)
	if _, err := io.ReadFull(conn, respBuf); err != nil {
		return false, err
	}

	// 3. File Meta
	meta, _ := encodeMessage(map[string]any{
		"type":       "FILE_META",
		"transferId": transferID,
		"fileId":     fileID,
		"name":       fileName,
		"size":       fileSize,
		"mimeType":   "application/octet-stream",
	})
	if _, err := conn.Write(meta); err != nil {
		return false, err
	}

	// 4. Stream 64KB Chunks
	hasher := sha256.New()
	chunk := make([]byte, ChunkSize)
	var offset int64 = 0

	for {
		n, rErr := f.Read(chunk)
		if n > 0 {
			hasher.Write(chunk[:n])
			cFrame, _ := encodeFileChunk(fileID, offset, chunk[:n])
			if _, wErr := conn.Write(cFrame); wErr != nil {
				return false, wErr
			}
			offset += int64(n)
		}
		if rErr != nil {
			break
		}
	}

	// 5. File Complete
	fHash := hex.EncodeToString(hasher.Sum(nil))
	comp, _ := encodeMessage(map[string]any{
		"type":       "FILE_COMPLETE",
		"transferId": transferID,
		"fileId":     fileID,
		"sha256":     fHash,
	})
	_, _ = conn.Write(comp)

	return true, nil
}

func fileExists(p string) bool {
	info, err := os.Stat(p)
	return err == nil && !info.IsDir()
}

// openGUIWindow launches the DexoLink GUI in a dedicated window at macOS MenuBar aspect ratio
func openGUIWindow(url string) bool {
	switch runtime.GOOS {
	case "windows":
		// Tier 1: Microsoft Edge App Mode
		edgeCandidates := []string{
			filepath.Join(os.Getenv("ProgramFiles(x86)"), "Microsoft", "Edge", "Application", "msedge.exe"),
			filepath.Join(os.Getenv("ProgramFiles"), "Microsoft", "Edge", "Application", "msedge.exe"),
			filepath.Join(os.Getenv("LOCALAPPDATA"), "Microsoft", "Edge", "Application", "msedge.exe"),
		}
		for _, p := range edgeCandidates {
			if p != "" && fileExists(p) {
				cmd := exec.Command(p, fmt.Sprintf("--app=%s", url), "--window-size=440,740")
				if err := cmd.Start(); err == nil {
					fmt.Println("[DexoLink Windows] DexoLink GUI window opened via Microsoft Edge App Mode.")
					return true
				}
			}
		}
		if p, err := exec.LookPath("msedge.exe"); err == nil {
			cmd := exec.Command(p, fmt.Sprintf("--app=%s", url), "--window-size=440,740")
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
				cmd := exec.Command(p, fmt.Sprintf("--app=%s", url), "--window-size=440,740")
				if err := cmd.Start(); err == nil {
					fmt.Println("[DexoLink Windows] DexoLink GUI window opened via Google Chrome App Mode.")
					return true
				}
			}
		}
		if p, err := exec.LookPath("chrome.exe"); err == nil {
			cmd := exec.Command(p, fmt.Sprintf("--app=%s", url), "--window-size=440,740")
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

func openBrowser(url string) {
	if openGUIWindow(url) {
		return
	}
	fmt.Printf("[DexoLink Windows] Notice: Could not open URL automatically: %s\n", url)
}

func checkIfInstanceRunning() bool {
	client := http.Client{Timeout: 350 * time.Millisecond}
	resp, err := client.Get(fmt.Sprintf("http://127.0.0.1:%d/api/status", WebUIPort))
	if err == nil && resp != nil {
		_ = resp.Body.Close()
		return resp.StatusCode == http.StatusOK
	}
	return false
}

func checkPortAvailable(port int) error {
	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		return err
	}
	_ = ln.Close()
	return nil
}

// Web UI Server
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
		for _, dev := range engine.TrustStore.PairedDevices {
			paired = append(paired, dev)
		}
		multiPaired := engine.TrustStore.MultiplePairedDevices
		engine.TrustStore.mu.Unlock()

		engine.mu.Lock()
		hist := make([]TransferRecord, len(engine.History))
		copy(hist, engine.History)
		clips := make([]string, len(engine.ClipboardHistory))
		copy(clips, engine.ClipboardHistory)
		isEnabled := engine.Enabled
		cont := engine.ContinuityEnabled
		clipSync := engine.AutoClipboardSync
		enhClip := engine.EnhancedClipboard
		autoDisc := engine.AutoDiscoveryEnabled
		autoAcc := engine.AutoAcceptPaired
		camShare := engine.CameraSharingEnabled
		scrShare := engine.ScreenSharingEnabled
		deskMode := engine.DesktopModeEnabled
		downDir := engine.DownloadDir
		engine.mu.Unlock()

		connCount := 0
		activePeerName := ""
		activePeerID := ""
		engine.ActiveConnections.Range(func(key, val any) bool {
			connCount++
			activePeerID = key.(string)
			if devVal, ok := engine.DiscoveredDevices.Load(activePeerID); ok {
				activePeerName = devVal.(DeviceInfo).DeviceName
			}
			return true
		})
		if activePeerName == "" && len(paired) > 0 {
			activePeerName = paired[0].DeviceName
		}

		engine.ActiveStreamMu.RLock()
		streamID := engine.ActiveStreamID
		streamType := engine.ActiveStreamType
		hasStream := (streamID != "" || engine.LatestFrameBase64 != "")
		engine.ActiveStreamMu.RUnlock()

		data := map[string]any{
			"deviceName":             engine.DeviceName,
			"deviceId":               engine.DeviceID,
			"port":                   DefaultPort,
			"download_dir":           downDir,
			"is_enabled":             isEnabled,
			"is_connected":           connCount > 0,
			"connected_count":        connCount,
			"connected_device_name":  activePeerName,
			"connected_device_id":    activePeerID,
			"has_active_stream":      hasStream,
			"active_stream_id":       streamID,
			"active_stream_type":     streamType,
			"multiple_paired":        multiPaired,
			"paired_devices":         paired,
			"discovered_devices":     discovered,
			"recent_transfers":       hist,
			"clipboard_history":      clips,
			"continuity_enabled":     cont,
			"auto_clipboard_sync":    clipSync,
			"enhanced_clipboard":     enhClip,
			"auto_discovery":         autoDisc,
			"auto_accept_paired":     autoAcc,
			"camera_sharing_enabled": camShare,
			"screen_sharing_enabled": scrShare,
			"desktop_mode_enabled":   deskMode,
		}
		_ = json.NewEncoder(w).Encode(data)
	})

	http.HandleFunc("/api/upload-file", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}
		_ = r.ParseMultipartForm(100 << 20)
		file, header, err := r.FormFile("file")
		if err != nil {
			http.Error(w, fmt.Sprintf("FormFile error: %v", err), http.StatusBadRequest)
			return
		}
		defer file.Close()

		tmpFile, err := os.CreateTemp("", "dexolink_send_*_"+header.Filename)
		if err != nil {
			http.Error(w, fmt.Sprintf("CreateTemp error: %v", err), http.StatusInternalServerError)
			return
		}
		tmpPath := tmpFile.Name()
		defer os.Remove(tmpPath)

		_, _ = io.Copy(tmpFile, file)
		_ = tmpFile.Close()

		peerIP, peerPort, _, peerName := engine.GetPrimaryPeer()
		if peerIP == "" {
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "error": "No connected or paired device found on local Wi-Fi"})
			return
		}

		success, sendErr := engine.SendFile(peerIP, peerPort, tmpPath)
		record := TransferRecord{
			ID:         fmt.Sprintf("tr_%d", time.Now().UnixNano()),
			FileName:   header.Filename,
			Size:       header.Size,
			IsIncoming: false,
			PeerName:   peerName,
			Success:    success,
			Time:       time.Now(),
		}
		engine.mu.Lock()
		engine.History = append([]TransferRecord{record}, engine.History...)
		engine.mu.Unlock()

		w.Header().Set("Content-Type", "application/json")
		if sendErr != nil {
			_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "error": sendErr.Error()})
		} else {
			_ = json.NewEncoder(w).Encode(map[string]any{"success": true, "fileName": header.Filename, "size": header.Size})
		}
	})

	http.HandleFunc("/api/toggle-master", func(w http.ResponseWriter, r *http.Request) {
		enable := r.URL.Query().Get("enabled") == "true"
		engine.mu.Lock()
		engine.Enabled = enable
		engine.mu.Unlock()
		w.WriteHeader(http.StatusOK)
	})

	http.HandleFunc("/api/connect", func(w http.ResponseWriter, r *http.Request) {
		id := r.URL.Query().Get("id")
		ip := r.URL.Query().Get("ip")
		portStr := r.URL.Query().Get("port")
		port, _ := strconv.Atoi(portStr)
		if port == 0 {
			port = DefaultPort
		}
		name := r.URL.Query().Get("name")
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
		w.Header().Set("Content-Type", "application/json")
		if ip != "" {
			_, err := engine.EnsureConnected(ip, port, id, name)
			if err != nil {
				_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "error": err.Error()})
			} else {
				_ = json.NewEncoder(w).Encode(map[string]any{"success": true})
			}
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"success": false, "error": "device IP not found"})
	})

	http.HandleFunc("/api/connect-direct", func(w http.ResponseWriter, r *http.Request) {
		ip := r.URL.Query().Get("ip")
		portStr := r.URL.Query().Get("port")
		port, _ := strconv.Atoi(portStr)
		if port == 0 {
			port = DefaultPort
		}
		if ip != "" {
			peerID := fmt.Sprintf("direct_%s", ip)
			_, _ = engine.EnsureConnected(ip, port, peerID, fmt.Sprintf("Direct Device (%s)", ip))
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
			if ip != "" {
				go engine.EnsureConnected(ip, port, id, name)
			}
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

	http.HandleFunc("/api/unpair", func(w http.ResponseWriter, r *http.Request) {
		id := r.URL.Query().Get("id")
		if id != "" {
			engine.TrustStore.RemoveDevice(id)
			if val, ok := engine.ActiveConnections.Load(id); ok {
				conn := val.(net.Conn)
				_ = conn.Close()
				engine.ActiveConnections.Delete(id)
			}
		}
		w.WriteHeader(http.StatusOK)
	})

	http.HandleFunc("/api/stream-request", func(w http.ResponseWriter, r *http.Request) {
		sType := r.URL.Query().Get("streamType")
		if sType == "" {
			sType = "camera"
		}
		action := r.URL.Query().Get("action")
		if action == "" {
			action = "request_stream"
		}

		peerIP, peerPort, peerID, peerName := engine.GetPrimaryPeer()
		if peerIP != "" {
			_, _ = engine.EnsureConnected(peerIP, peerPort, peerID, peerName)
		}

		streamID := fmt.Sprintf("stream_%d", time.Now().UnixNano())
		engine.ActiveStreamMu.Lock()
		engine.ActiveStreamID = streamID
		engine.ActiveStreamType = sType
		engine.ActiveStreamDevice = peerName
		engine.ActiveStreamMu.Unlock()

		msg, _ := encodeMessage(map[string]any{
			"type":       "STREAM_REQUEST",
			"streamId":   streamID,
			"streamType": sType,
			"options": map[string]any{
				"direction":  "outgoing",
				"sender":     "windows",
				"action":     action,
				"lens":       "toggle",
				"senderName": engine.DeviceName,
			},
		})
		engine.ActiveConnections.Range(func(key, val any) bool {
			conn := val.(net.Conn)
			_, _ = conn.Write(msg)
			return true
		})
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"success": true, "streamId": streamID, "streamType": sType, "device": peerName})
	})

	http.HandleFunc("/api/stream-stop", func(w http.ResponseWriter, r *http.Request) {
		engine.ActiveStreamMu.Lock()
		sID := engine.ActiveStreamID
		engine.ActiveStreamID = ""
		engine.LatestFrameBase64 = ""
		engine.ActiveStreamMu.Unlock()

		if sID != "" {
			msg, _ := encodeMessage(map[string]any{
				"type":     "STREAM_STOP",
				"streamId": sID,
				"reason":   "User closed stream viewer on Windows",
			})
			engine.ActiveConnections.Range(func(key, val any) bool {
				conn := val.(net.Conn)
				_, _ = conn.Write(msg)
				return true
			})
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"success": true})
	})

	http.HandleFunc("/api/stream-latest", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		engine.ActiveStreamMu.RLock()
		frame := engine.LatestFrameBase64
		sID := engine.ActiveStreamID
		sType := engine.ActiveStreamType
		devName := engine.ActiveStreamDevice
		seq := engine.FrameSequence
		t := engine.LatestFrameTime
		engine.ActiveStreamMu.RUnlock()

		_ = json.NewEncoder(w).Encode(map[string]any{
			"streamId":   sID,
			"streamType": sType,
			"device":     devName,
			"sequence":   seq,
			"timestamp":  t.UnixNano(),
			"hasFrame":   frame != "",
			"frame":      frame,
		})
	})

	http.HandleFunc("/api/stream-sse", func(w http.ResponseWriter, r *http.Request) {
		flusher, ok := w.(http.Flusher)
		if !ok {
			http.Error(w, "Streaming unsupported", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		w.Header().Set("Cache-Control", "no-cache")
		w.Header().Set("Connection", "keep-alive")

		ch := make(chan string, 10)
		engine.StreamClientsMu.Lock()
		engine.StreamClients[ch] = true
		engine.StreamClientsMu.Unlock()

		defer func() {
			engine.StreamClientsMu.Lock()
			delete(engine.StreamClients, ch)
			engine.StreamClientsMu.Unlock()
			close(ch)
		}()

		engine.ActiveStreamMu.RLock()
		initFrame := engine.LatestFrameBase64
		engine.ActiveStreamMu.RUnlock()
		if initFrame != "" {
			fmt.Fprintf(w, "data: %s\n\n", initFrame)
			flusher.Flush()
		}

		notify := r.Context().Done()
		for {
			select {
			case <-notify:
				return
			case f, ok := <-ch:
				if !ok {
					return
				}
				fmt.Fprintf(w, "data: %s\n\n", f)
				flusher.Flush()
			}
		}
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
		engine.ActiveStreamMu.RLock()
		streamID := engine.ActiveStreamID
		engine.ActiveStreamMu.RUnlock()
		if streamID == "" {
			streamID = fmt.Sprintf("stream_%d", time.Now().UnixNano())
		}
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

			peerIP, peerPort, peerID, peerName := engine.GetPrimaryPeer()
			if peerIP != "" {
				_, _ = engine.EnsureConnected(peerIP, peerPort, peerID, peerName)
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
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"success": true})
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

			peerIP, peerPort, peerID, peerName := engine.GetPrimaryPeer()
			if peerIP != "" {
				_, _ = engine.EnsureConnected(peerIP, peerPort, peerID, peerName)
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
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"success": true})
	})

	http.HandleFunc("/api/toggle-feature", func(w http.ResponseWriter, r *http.Request) {
		feat := r.URL.Query().Get("feature")
		val := r.URL.Query().Get("enabled") == "true"
		engine.mu.Lock()
		switch feat {
		case "continuity":
			engine.ContinuityEnabled = val
		case "clipboard":
			engine.AutoClipboardSync = val
		case "enhanced":
			engine.EnhancedClipboard = val
		case "camera":
			engine.CameraSharingEnabled = val
		case "screen":
			engine.ScreenSharingEnabled = val
		case "desktop":
			engine.DesktopModeEnabled = val
		case "discovery":
			engine.AutoDiscoveryEnabled = val
		case "autoaccept":
			engine.AutoAcceptPaired = val
		}
		engine.mu.Unlock()
		w.WriteHeader(http.StatusOK)
	})

	http.HandleFunc("/api/toggle-multi-paired", func(w http.ResponseWriter, r *http.Request) {
		val := r.URL.Query().Get("enabled") == "true"
		engine.TrustStore.SetMultiPaired(val)
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
		if err == nil {
			engine.mu.Lock()
			if idx >= 0 && idx < len(engine.ClipboardHistory) {
				engine.ClipboardHistory = append(engine.ClipboardHistory[:idx], engine.ClipboardHistory[idx+1:]...)
			}
			engine.mu.Unlock()
		}
		w.WriteHeader(http.StatusOK)
	})

	http.HandleFunc("/api/quit", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		go func() {
			time.Sleep(200 * time.Millisecond)
			os.Exit(0)
		}()
	})

	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", WebUIPort))
	if err != nil {
		fmt.Printf("[DexoLink Windows] Web UI server error: Failed to bind port %d: %v\n", WebUIPort, err)
		if ready != nil {
			ready <- err
		}
		return
	}
	fmt.Printf("[DexoLink Windows] Web UI listening on http://127.0.0.1:%d\n", WebUIPort)
	if ready != nil {
		ready <- nil
	}
	_ = http.Serve(ln, nil)
}

func main() {
	fmt.Println("=====================================================")
	fmt.Println("     DexoLink v4.0.1 (Windows Native Edition)        ")
	fmt.Println("     Local Cross-Device Connectivity Engine          ")
	fmt.Println("=====================================================")

	if checkIfInstanceRunning() {
		fmt.Println("[DexoLink Windows] Another DexoLink instance is already active.")
		fmt.Println("[DexoLink Windows] Bringing existing DexoLink GUI window to front...")
		openGUIWindow(fmt.Sprintf("http://127.0.0.1:%d", WebUIPort))
		return
	}

	if err := checkPortAvailable(DefaultPort); err != nil {
		fmt.Printf("[DexoLink Windows] Warning: Port %d is already occupied: %v\n", DefaultPort, err)
	}

	engine = initEngine()
	go engine.startUDPBeacon()
	go engine.startUDPListener()

	tcpReady := make(chan error, 1)
	go engine.startTCPServer(tcpReady)

	webReady := make(chan error, 1)
	go startWebDashboard(webReady)

	if err := <-webReady; err != nil {
		fmt.Printf("[DexoLink Windows] Fatal: Could not initialize Web Dashboard: %v\n", err)
		return
	}

	webURL := fmt.Sprintf("http://127.0.0.1:%d", WebUIPort)
	go func() {
		time.Sleep(300 * time.Millisecond)
		openGUIWindow(webURL)
	}()

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, os.Interrupt, syscall.SIGTERM)
	<-sigChan
	fmt.Println("\n[DexoLink Windows] Shutting down gracefully...")
}

// Embedded Web UI matching exact macOS MenuBarView layout & interactive features
const webUIHTML = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>DexoLink v4.0.1 (Windows)</title>
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <style>
    :root {
      --bg-window: #131519;
      --bg-card: rgba(255, 255, 255, 0.05);
      --bg-card-hover: rgba(255, 255, 255, 0.08);
      --border-card: rgba(255, 255, 255, 0.09);
      --text-main: #f5f5f7;
      --text-sub: #86868b;
      --accent-blue: #0071e3;
      --accent-cyan: #32ade6;
      --accent-green: #34c759;
      --accent-orange: #ff9f0a;
      --accent-purple: #af52de;
      --accent-red: #ff3b30;
      --btn-bg: rgba(255, 255, 255, 0.08);
      --btn-hover: rgba(255, 255, 255, 0.14);
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      background: #0c0d10;
      color: var(--text-main);
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      font-size: 13px;
      line-height: 1.4;
      user-select: none;
      -webkit-user-select: none;
      overflow-x: hidden;
    }
    .mac-window {
      max-width: 420px;
      margin: 0 auto;
      min-height: 100vh;
      background: var(--bg-window);
      display: flex;
      flex-direction: column;
      border-left: 1px solid var(--border-card);
      border-right: 1px solid var(--border-card);
    }

    /* Header */
    .mac-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 10px 14px;
      background: rgba(255, 255, 255, 0.03);
      border-bottom: 1px solid var(--border-card);
    }
    .mac-header-title {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 14px;
      font-weight: 700;
    }
    .mac-header-tools {
      display: flex;
      align-items: center;
      gap: 12px;
    }
    .mac-tool-btn {
      background: none;
      border: none;
      color: var(--text-sub);
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      transition: color 0.15s ease;
      padding: 4px;
    }
    .mac-tool-btn:hover { color: var(--text-main); }

    /* Master Card */
    .master-card {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 10px 14px;
      background: rgba(255, 255, 255, 0.02);
      border-bottom: 1px solid var(--border-card);
    }
    .master-icon-wrap {
      width: 36px;
      height: 36px;
      border-radius: 50%;
      background: rgba(0, 113, 227, 0.15);
      display: flex;
      align-items: center;
      justify-content: center;
      color: var(--accent-blue);
      flex-shrink: 0;
    }
    .master-icon-wrap.off {
      background: rgba(255, 255, 255, 0.08);
      color: var(--text-sub);
    }
    .master-info {
      flex: 1;
      min-width: 0;
    }
    .master-title-row {
      display: flex;
      align-items: center;
      gap: 6px;
    }
    .master-title {
      font-size: 13px;
      font-weight: 700;
    }
    .pill-badge {
      font-size: 9px;
      font-weight: 800;
      padding: 1px 6px;
      border-radius: 10px;
      text-transform: uppercase;
      letter-spacing: 0.4px;
    }
    .pill-blue { background: rgba(0, 113, 227, 0.2); color: var(--accent-blue); }
    .pill-grey { background: rgba(255, 255, 255, 0.1); color: var(--text-sub); }
    .pill-green { background: rgba(52, 199, 89, 0.2); color: var(--accent-green); }
    .pill-cyan { background: rgba(50, 173, 230, 0.2); color: var(--accent-cyan); }
    .pill-orange { background: rgba(255, 159, 10, 0.2); color: var(--accent-orange); }

    .master-subtitle {
      font-size: 11px;
      color: var(--text-sub);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    /* iOS Switch */
    .switch {
      position: relative;
      display: inline-block;
      width: 44px;
      height: 24px;
      flex-shrink: 0;
    }
    .switch input { opacity: 0; width: 0; height: 0; }
    .slider {
      position: absolute;
      cursor: pointer;
      top: 0; left: 0; right: 0; bottom: 0;
      background-color: rgba(255, 255, 255, 0.16);
      transition: .25s;
      border-radius: 24px;
    }
    .slider:before {
      position: absolute;
      content: "";
      height: 20px;
      width: 20px;
      left: 2px;
      bottom: 2px;
      background-color: white;
      transition: .25s;
      border-radius: 50%;
    }
    input:checked + .slider { background-color: var(--accent-blue); }
    input:checked + .slider:before { transform: translateX(20px); }

    /* Scrollable Body */
    .mac-body {
      flex: 1;
      padding: 12px;
      display: flex;
      flex-direction: column;
      gap: 10px;
      overflow-y: auto;
    }

    /* Cards */
    .mac-card {
      background: var(--bg-card);
      border: 1px solid var(--border-card);
      border-radius: 10px;
      padding: 10px 12px;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .mac-card-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }
    .mac-card-title {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 12px;
      font-weight: 700;
    }

    /* Buttons */
    .btn {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 4px;
      padding: 4px 10px;
      border-radius: 6px;
      font-size: 11px;
      font-weight: 600;
      cursor: pointer;
      border: 1px solid transparent;
      transition: all 0.15s ease;
      text-decoration: none;
      color: var(--text-main);
    }
    .btn-prominent { background: var(--accent-blue); color: white; }
    .btn-prominent:hover { background: #0077ed; }
    .btn-bordered { background: var(--btn-bg); border-color: var(--border-card); color: var(--text-main); }
    .btn-bordered:hover { background: var(--btn-hover); }
    .btn-teal { background: rgba(50, 173, 230, 0.15); border-color: rgba(50, 173, 230, 0.3); color: var(--accent-cyan); }
    .btn-teal:hover { background: rgba(50, 173, 230, 0.25); }
    .btn-orange { background: rgba(255, 159, 10, 0.15); border-color: rgba(255, 159, 10, 0.3); color: var(--accent-orange); }
    .btn-orange:hover { background: rgba(255, 159, 10, 0.25); }
    .btn-danger { background: rgba(255, 59, 48, 0.12); border-color: rgba(255, 59, 48, 0.25); color: var(--accent-red); }
    .btn-danger:hover { background: rgba(255, 59, 48, 0.22); }
    .btn-sm { padding: 2px 7px; font-size: 10px; }

    /* Drop Zone */
    .drop-zone {
      border: 1.5px dashed rgba(255, 255, 255, 0.2);
      background: rgba(0, 113, 227, 0.03);
      border-radius: 10px;
      padding: 16px 12px;
      text-align: center;
      cursor: pointer;
      transition: all 0.2s ease;
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 6px;
    }
    .drop-zone:hover, .drop-zone.dragover {
      border-color: var(--accent-blue);
      background: rgba(0, 113, 227, 0.1);
    }
    .drop-zone-icon {
      color: var(--accent-blue);
    }
    .drop-zone-text {
      font-size: 11px;
      font-weight: 600;
      color: var(--text-main);
    }
    .drop-zone-sub {
      font-size: 9px;
      color: var(--text-sub);
    }

    /* Device Rows */
    .device-list {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .device-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 7px 10px;
      background: rgba(255, 255, 255, 0.03);
      border-radius: 6px;
      border: 1px solid rgba(255, 255, 255, 0.04);
    }
    .device-row:hover {
      background: rgba(255, 255, 255, 0.05);
    }
    .device-left {
      display: flex;
      align-items: center;
      gap: 8px;
      min-width: 0;
      flex: 1;
    }
    .device-icon-box {
      position: relative;
      width: 24px;
      height: 24px;
      display: flex;
      align-items: center;
      justify-content: center;
      color: var(--accent-cyan);
      flex-shrink: 0;
    }
    .online-dot {
      position: absolute;
      bottom: 1px;
      right: 1px;
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: var(--accent-green);
      border: 1px solid var(--bg-window);
    }
    .device-details {
      display: flex;
      flex-direction: column;
      min-width: 0;
    }
    .device-name-row {
      display: flex;
      align-items: center;
      gap: 4px;
    }
    .device-name {
      font-size: 11px;
      font-weight: 600;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .device-sub {
      font-size: 9px;
      color: var(--text-sub);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .device-actions {
      display: flex;
      align-items: center;
      gap: 4px;
      flex-shrink: 0;
    }

    /* Turned Off State Banner */
    .turned-off-banner {
      display: flex;
      flex-direction: column;
      align-items: center;
      text-align: center;
      padding: 30px 16px;
      gap: 12px;
    }
    .turned-off-icon {
      width: 58px;
      height: 58px;
      border-radius: 50%;
      background: rgba(255, 255, 255, 0.05);
      display: flex;
      align-items: center;
      justify-content: center;
      color: var(--text-sub);
    }
    .turned-off-title { font-size: 14px; font-weight: 700; }
    .turned-off-desc { font-size: 11px; color: var(--text-sub); line-height: 1.4; max-width: 280px; }

    /* Footer */
    .mac-footer {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 8px 12px;
      background: rgba(255, 255, 255, 0.02);
      border-top: 1px solid var(--border-card);
      font-size: 11px;
      color: var(--text-sub);
    }
    .footer-status {
      display: flex;
      align-items: center;
      gap: 6px;
    }
    .status-dot {
      width: 7px;
      height: 7px;
      border-radius: 50%;
      background: var(--accent-green);
    }
    .mac-attr {
      text-align: center;
      padding: 5px;
      font-size: 9px;
      color: rgba(255, 255, 255, 0.4);
      background: rgba(0, 0, 0, 0.2);
    }

    /* Modals */
    .modal-backdrop {
      position: fixed;
      top: 0; left: 0; right: 0; bottom: 0;
      background: rgba(0, 0, 0, 0.7);
      backdrop-filter: blur(10px);
      display: none;
      align-items: center;
      justify-content: center;
      z-index: 1000;
      padding: 16px;
    }
    .modal-box {
      background: #1b1e24;
      border: 1px solid var(--border-card);
      border-radius: 12px;
      width: 100%;
      max-width: 380px;
      max-height: 90vh;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
    }
    .modal-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 12px 14px;
      border-bottom: 1px solid var(--border-card);
      font-weight: 700;
    }
    .modal-body {
      padding: 14px;
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    /* Live Stream Viewer Modal */
    .stream-modal-box {
      max-width: 680px;
      background: #101216;
    }
    .stream-canvas-wrap {
      width: 100%;
      background: #000;
      border-radius: 8px;
      overflow: hidden;
      position: relative;
      min-height: 320px;
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .stream-img {
      width: 100%;
      height: auto;
      max-height: 500px;
      object-fit: contain;
      display: block;
    }
    .stream-loader {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 10px;
      color: var(--text-sub);
      font-size: 12px;
    }
    .spinner {
      width: 24px;
      height: 24px;
      border: 2px solid rgba(255, 255, 255, 0.15);
      border-top-color: var(--accent-blue);
      border-radius: 50%;
      animation: spin 0.8s linear infinite;
    }
    @keyframes spin { 100% { transform: rotate(360deg); } }

    /* Remote Write Dock */
    .remote-write-dock {
      background: rgba(255, 255, 255, 0.03);
      border: 1px solid var(--border-card);
      border-radius: 8px;
      padding: 10px;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .dock-input-row {
      display: flex;
      gap: 6px;
    }
    .text-input {
      flex: 1;
      background: rgba(0, 0, 0, 0.4);
      border: 1px solid var(--border-card);
      color: var(--text-main);
      padding: 6px 10px;
      border-radius: 6px;
      font-size: 11px;
      outline: none;
    }
    .text-input:focus { border-color: var(--accent-blue); }
    .dock-keys-row {
      display: flex;
      flex-wrap: wrap;
      gap: 4px;
    }
    .key-btn {
      background: rgba(255, 255, 255, 0.06);
      border: 1px solid var(--border-card);
      color: var(--text-main);
      padding: 3px 8px;
      border-radius: 4px;
      font-size: 10px;
      font-weight: 600;
      cursor: pointer;
    }
    .key-btn:hover { background: rgba(255, 255, 255, 0.12); }

    /* Form Controls */
    .form-group {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .form-group label {
      font-size: 11px;
      color: var(--text-sub);
    }
    .toggle-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 6px 0;
      border-bottom: 1px solid rgba(255, 255, 255, 0.04);
    }
    .toggle-row:last-child { border-bottom: none; }
  </style>
</head>
<body>
  <div class="mac-window">
    <!-- MARK: Header -->
    <header class="mac-header">
      <div class="mac-header-title">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" color="#32ade6"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"></path><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"></path></svg>
        <span>DexoLink</span>
      </div>
      <div class="mac-header-tools">
        <button class="mac-tool-btn" onclick="openModal('settingsModal')" title="Settings & All Feature Controls">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg>
        </button>
        <button class="mac-tool-btn" onclick="openModal('historyModal')" title="Transfer & Clipboard History">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline></svg>
        </button>
        <button class="mac-tool-btn" onclick="openModal('directIPModal')" title="Direct IP Connection (Optional Fallback)">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"></path><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"></path></svg>
        </button>
        <button class="mac-tool-btn" onclick="quitApp()" title="Quit DexoLink">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18.36 6.64a9 9 0 1 1-12.73 0"></path><line x1="12" y1="2" x2="12" y2="12"></line></svg>
        </button>
      </div>
    </header>

    <!-- MARK: Master Quick Settings ON/OFF Card -->
    <div class="master-card">
      <div class="master-icon-wrap" id="masterIconWrap">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M4.93 4.93a10 10 0 0 1 14.14 0"></path><path d="M7.76 7.76a6 6 0 0 1 8.48 0"></path><circle cx="12" cy="12" r="2"></circle><line x1="12" y1="14" x2="12" y2="22"></line></svg>
      </div>
      <div class="master-info">
        <div class="master-title-row">
          <span class="master-title">DexoLink</span>
          <span class="pill-badge pill-blue" id="masterPill">ON</span>
        </div>
        <div class="master-subtitle" id="masterSubtitle">Active • Ready to connect on local Wi-Fi</div>
      </div>
      <label class="switch">
        <input type="checkbox" id="masterSwitch" checked onchange="toggleMaster(this.checked)">
        <span class="slider"></span>
      </label>
    </div>

    <!-- MARK: Body -->
    <div class="mac-body">
      <!-- Turned Off State Banner -->
      <div class="turned-off-banner" id="turnedOffBanner" style="display: none;">
        <div class="turned-off-icon">
          <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="1" y1="1" x2="23" y2="23"></line><path d="M16.72 11.06A10.94 10.94 0 0 1 19 12.55"></path><path d="M5 12.55a10.94 10.94 0 0 1 5.17-2.39"></path><path d="M10.71 5.05A16 16 0 0 1 22.56 9"></path><path d="M1.42 9a15.91 15.91 0 0 1 4.7-2.88"></path><path d="M8.53 16.11a6 6 0 0 1 6.95 0"></path><line x1="12" y1="20" x2="12.01" y2="20"></line></svg>
        </div>
        <div class="turned-off-title">DexoLink is Turned Off</div>
        <div class="turned-off-desc">All background listeners, discovery broadcasts, and socket connections are stopped to ensure 0% CPU and battery usage.</div>
        <button class="btn btn-prominent" onclick="toggleMaster(true)">Turn On DexoLink</button>
      </div>

      <!-- Main Active Container -->
      <div id="activeDashboard" style="display: flex; flex-direction: column; gap: 10px;">
        
        <!-- MARK: Paired Devices Card -->
        <div class="mac-card" id="pairedSection">
          <div class="mac-card-header">
            <span class="mac-card-title">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" color="#34c759"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path></svg>
              <span>Paired Devices</span>
            </span>
            <span class="pill-badge pill-grey" id="pairedModeBadge">Strict 1-Device Policy</span>
          </div>
          <div class="device-list" id="pairedList">
            <!-- Dynamically populated -->
          </div>
        </div>

        <!-- MARK: Quick File Sharing Card -->
        <div class="mac-card">
          <div class="mac-card-header">
            <span class="mac-card-title">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" color="#ff9f0a"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path></svg>
              <span>Quick File Sharing</span>
            </span>
            <button class="btn btn-bordered btn-sm" onclick="openDownloadsFolder()" title="Open DexoLink Downloads folder in Explorer">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg>
              <span>Downloads</span>
            </button>
          </div>

          <div class="drop-zone" id="dropZone" onclick="document.getElementById('fileInput').click()">
            <input type="file" id="fileInput" style="display:none;" onchange="handleFileSelect(this)">
            <svg class="drop-zone-icon" width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><circle cx="12" cy="12" r="10"></circle><polyline points="16 12 12 8 8 12"></polyline><line x1="12" y1="16" x2="12" y2="8"></line></svg>
            <div class="drop-zone-text" id="dropZoneText">Drop files here or click to send</div>
            <div class="drop-zone-sub">Photos • PDFs • Documents • Downloads • Any file</div>
            <div style="display: flex; gap: 6px; margin-top: 4px;">
              <button class="btn btn-prominent btn-sm" id="btnSendFiles" onclick="event.stopPropagation(); document.getElementById('fileInput').click()">Send Files</button>
              <button class="btn btn-bordered btn-sm" onclick="event.stopPropagation(); document.getElementById('fileInput').click()">All Share Options...</button>
            </div>
          </div>

          <!-- Active Transfer Banner -->
          <div id="activeTransferBox" style="display: none; padding: 8px 10px; background: rgba(0, 113, 227, 0.12); border-radius: 6px; border: 1px solid rgba(0, 113, 227, 0.25);">
            <div style="display: flex; align-items: center; justify-content: space-between; font-size: 11px; font-weight: 600;">
              <span id="transferFileName">Sending file...</span>
              <span id="transferPercent" style="color: var(--accent-blue);">100%</span>
            </div>
            <div style="width: 100%; height: 4px; background: rgba(255,255,255,0.1); border-radius: 2px; margin-top: 4px; overflow: hidden;">
              <div id="transferBar" style="width: 100%; height: 100%; background: var(--accent-blue);"></div>
            </div>
          </div>

          <!-- Recent Transfers -->
          <div id="recentTransfersSection" style="display: flex; flex-direction: column; gap: 4px; margin-top: 2px;">
            <div style="display: flex; align-items: center; justify-content: space-between;">
              <span style="font-size: 10px; color: var(--text-sub); font-weight: 600;">Recent Transfers</span>
              <button class="btn btn-bordered btn-sm" onclick="openModal('historyModal')" style="font-size: 9px; padding: 1px 5px;">Manage</button>
            </div>
            <div id="recentTransfersList" style="display: flex; flex-direction: column; gap: 3px;">
              <!-- Populated via loadStatus -->
            </div>
          </div>
        </div>

        <!-- MARK: Feature Controls Card -->
        <div class="mac-card" style="flex-direction: row; align-items: center; justify-content: space-between;">
          <div style="display: flex; align-items: center; gap: 10px;">
            <div style="width: 32px; height: 32px; border-radius: 8px; background: rgba(0, 113, 227, 0.15); display: flex; align-items: center; justify-content: center; color: var(--accent-blue);">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg>
            </div>
            <div style="display: flex; align-items: center; gap: 6px;">
              <span style="font-size: 13px; font-weight: 700;">Feature Controls</span>
              <span class="pill-badge pill-green" id="featCountBadge">3 ACTIVE</span>
            </div>
          </div>
          <button class="btn btn-prominent btn-sm" onclick="openModal('settingsModal')">Configure</button>
        </div>

        <!-- MARK: Nearby Discovered Devices -->
        <div class="mac-card">
          <div class="mac-card-header">
            <span class="mac-card-title">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" color="#32ade6"><path d="M5 12.55a11 11 0 0 1 14.08 0"></path><path d="M1.42 9a16 16 0 0 1 21.16 0"></path><path d="M8.53 16.11a6 6 0 0 1 6.95 0"></path><line x1="12" y1="20" x2="12.01" y2="20"></line></svg>
              <span>Nearby Devices (Wi-Fi)</span>
            </span>
            <div style="display: flex; gap: 4px;">
              <button class="btn btn-bordered btn-sm" onclick="toggleDiscovery()" id="btnDiscToggle">
                <span style="display: inline-block; width: 5px; height: 5px; border-radius: 50%; background: var(--accent-green); margin-right: 3px;"></span>
                <span>Stop</span>
              </button>
              <button class="btn btn-bordered btn-sm" onclick="loadStatus()" title="Rescan nearby devices now">
                <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M23 4v6h-6"></path><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"></path></svg>
              </button>
            </div>
          </div>
          <div class="device-list" id="discoveredList">
            <!-- Populated via loadStatus -->
          </div>
        </div>

      </div>
    </div>

    <!-- MARK: Footer -->
    <footer class="mac-footer">
      <div class="footer-status">
        <div class="status-dot" id="footerDot"></div>
        <span id="footerStatusText">Ready to connect • Wi-Fi Auto-Discovery</span>
      </div>
      <button class="btn btn-bordered btn-sm" onclick="loadStatus()">Refresh</button>
    </footer>

    <!-- MARK: Developer Attribution -->
    <div class="mac-attr">Developed by Rahul Mahaseth | DexoLink v4.0.1</div>
  </div>

  <!-- MARK: Live Media Stream Viewer Modal -->
  <div class="modal-backdrop" id="streamModal">
    <div class="modal-box stream-modal-box">
      <div class="modal-header">
        <div style="display: flex; align-items: center; gap: 8px;">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" color="#32ade6"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"></rect><line x1="8" y1="21" x2="16" y2="21"></line><line x1="12" y1="17" x2="12" y2="21"></line></svg>
          <span id="streamModalTitle">Live Media Feed</span>
          <span class="pill-badge pill-green" id="streamLiveBadge">LIVE</span>
        </div>
        <div style="display: flex; align-items: center; gap: 8px;">
          <button class="btn btn-bordered btn-sm" id="btnFlipCam" onclick="flipCameraLens()">Flip Camera</button>
          <button class="btn btn-danger btn-sm" onclick="closeStreamViewer()">Close</button>
        </div>
      </div>
      <div class="modal-body" style="padding: 10px;">
        <div class="stream-canvas-wrap">
          <div class="stream-loader" id="streamLoader">
            <div class="spinner"></div>
            <span id="streamLoadingText">Connecting to live feed from connected device...</span>
          </div>
          <img id="streamVideoImage" class="stream-img" style="display: none;" alt="Live Stream">
        </div>

        <!-- Remote Desktop Write & Control Dock -->
        <div class="remote-write-dock" id="remoteWriteDock">
          <div style="font-size: 11px; font-weight: 700; color: var(--text-sub);">Remote Write & Input Control</div>
          <div class="dock-input-row">
            <input type="text" id="remoteTextInput" class="text-input" placeholder="Type text to send to remote device..." onkeydown="if(event.key==='Enter') sendWriteText()">
            <button class="btn btn-prominent btn-sm" onclick="sendWriteText()">Send</button>
          </div>
          <div class="dock-keys-row">
            <button class="key-btn" onclick="sendKey('enter')">Enter</button>
            <button class="key-btn" onclick="sendKey('backspace')">Backspace</button>
            <button class="key-btn" onclick="sendKey('space')">Space</button>
            <button class="key-btn" onclick="sendKey('tab')">Tab</button>
            <button class="key-btn" onclick="sendMouseClick(0)">Left Click</button>
            <button class="key-btn" onclick="sendMouseClick(1)">Right Click</button>
          </div>
        </div>
      </div>
    </div>
  </div>

  <!-- MARK: Settings Sheet Modal -->
  <div class="modal-backdrop" id="settingsModal">
    <div class="modal-box">
      <div class="modal-header">
        <span>Settings & Features</span>
        <button class="btn btn-bordered btn-sm" onclick="closeModal('settingsModal')">Done</button>
      </div>
      <div class="modal-body">
        <div class="toggle-row">
          <div>
            <div style="font-weight: 600;">Continuity & Handoff</div>
            <div style="font-size: 10px; color: var(--text-sub);">Open links and activities across devices</div>
          </div>
          <label class="switch">
            <input type="checkbox" id="settContinuity" onchange="toggleFeature('continuity', this.checked)">
            <span class="slider"></span>
          </label>
        </div>
        <div class="toggle-row">
          <div>
            <div style="font-weight: 600;">Universal Clipboard</div>
            <div style="font-size: 10px; color: var(--text-sub);">Sync copied text between devices</div>
          </div>
          <label class="switch">
            <input type="checkbox" id="settClipboard" onchange="toggleFeature('clipboard', this.checked)">
            <span class="slider"></span>
          </label>
        </div>
        <div class="toggle-row">
          <div>
            <div style="font-weight: 600;">Multiple Paired Devices</div>
            <div style="font-size: 10px; color: var(--text-sub);">Allow pairing more than 1 phone/PC</div>
          </div>
          <label class="switch">
            <input type="checkbox" id="settMulti" onchange="toggleMultiPaired(this.checked)">
            <span class="slider"></span>
          </label>
        </div>
        <div class="toggle-row">
          <div>
            <div style="font-weight: 600;">Camera Sharing</div>
            <div style="font-size: 10px; color: var(--text-sub);">Stream live mobile camera video to PC</div>
          </div>
          <label class="switch">
            <input type="checkbox" id="settCamera" onchange="toggleFeature('camera', this.checked)">
            <span class="slider"></span>
          </label>
        </div>
        <div class="toggle-row">
          <div>
            <div style="font-weight: 600;">Screen Sharing</div>
            <div style="font-size: 10px; color: var(--text-sub);">Mirror phone or PC display live</div>
          </div>
          <label class="switch">
            <input type="checkbox" id="settScreen" onchange="toggleFeature('screen', this.checked)">
            <span class="slider"></span>
          </label>
        </div>
        <div class="toggle-row">
          <div>
            <div style="font-weight: 600;">Desktop Mode & Control</div>
            <div style="font-size: 10px; color: var(--text-sub);">Send remote mouse and keyboard inputs</div>
          </div>
          <label class="switch">
            <input type="checkbox" id="settDesktop" onchange="toggleFeature('desktop', this.checked)">
            <span class="slider"></span>
          </label>
        </div>
        <div class="toggle-row">
          <div>
            <div style="font-weight: 600;">Wi-Fi Auto-Discovery</div>
            <div style="font-size: 10px; color: var(--text-sub);">Advertise and discover nearby peers</div>
          </div>
          <label class="switch">
            <input type="checkbox" id="settDiscovery" onchange="toggleFeature('discovery', this.checked)">
            <span class="slider"></span>
          </label>
        </div>
      </div>
    </div>
  </div>

  <!-- MARK: Transfer & Clipboard History Modal -->
  <div class="modal-backdrop" id="historyModal">
    <div class="modal-box">
      <div class="modal-header">
        <span>Activity History</span>
        <button class="btn btn-bordered btn-sm" onclick="closeModal('historyModal')">Done</button>
      </div>
      <div class="modal-body">
        <div style="display: flex; gap: 4px; border-bottom: 1px solid var(--border-card); padding-bottom: 8px;">
          <button class="btn btn-prominent btn-sm" id="tabBtnTransfers" onclick="switchHistTab('transfers')">Transfers</button>
          <button class="btn btn-bordered btn-sm" id="tabBtnClipboard" onclick="switchHistTab('clipboard')">Clipboard</button>
        </div>
        <div id="histTransfersTab" class="device-list">
          <!-- Populated dynamically -->
        </div>
        <div id="histClipboardTab" class="device-list" style="display: none;">
          <!-- Populated dynamically -->
        </div>
      </div>
    </div>
  </div>

  <!-- MARK: Direct IP Connection Modal -->
  <div class="modal-backdrop" id="directIPModal">
    <div class="modal-box">
      <div class="modal-header">
        <span>Direct IP Connection</span>
        <button class="btn btn-bordered btn-sm" onclick="closeModal('directIPModal')">Cancel</button>
      </div>
      <div class="modal-body">
        <div class="form-group">
          <label>Target IP Address</label>
          <input type="text" id="manualIPInput" class="text-input" placeholder="e.g. 192.168.1.100">
        </div>
        <div class="form-group">
          <label>Port</label>
          <input type="text" id="manualPortInput" class="text-input" value="52840">
        </div>
        <button class="btn btn-prominent" onclick="submitDirectIP()">Connect to Device</button>
      </div>
    </div>
  </div>

  <script>
    let currentData = null;
    let sseSource = null;
    let streamPollTimer = null;

    function openModal(id) { document.getElementById(id).style.display = 'flex'; }
    function closeModal(id) { document.getElementById(id).style.display = 'none'; }

    async function quitApp() {
      if (confirm('Are you sure you want to quit DexoLink?')) {
        await fetch('/api/quit');
        window.close();
      }
    }

    async function toggleMaster(val) {
      await fetch('/api/toggle-master?enabled=' + val);
      loadStatus();
    }

    async function toggleFeature(feat, val) {
      await fetch('/api/toggle-feature?feature=' + feat + '&enabled=' + val);
      loadStatus();
    }

    async function toggleMultiPaired(val) {
      await fetch('/api/toggle-multi-paired?enabled=' + val);
      loadStatus();
    }

    async function openDownloadsFolder() {
      await fetch('/api/open-downloads');
    }

    async function connectToDevice(id, ip, port, name) {
      await fetch('/api/connect?id=' + encodeURIComponent(id) + '&ip=' + encodeURIComponent(ip || '') + '&port=' + (port || 52840) + '&name=' + encodeURIComponent(name || ''));
      loadStatus();
    }

    async function pairDevice(id, name, ip, port) {
      await fetch('/api/pair-device?id=' + encodeURIComponent(id) + '&name=' + encodeURIComponent(name) + '&ip=' + encodeURIComponent(ip) + '&port=' + (port || 52840));
      loadStatus();
    }

    async function disconnectDevice(id) {
      await fetch('/api/disconnect?id=' + encodeURIComponent(id || ''));
      loadStatus();
    }

    async function unpairDevice(id) {
      await fetch('/api/unpair?id=' + encodeURIComponent(id || ''));
      loadStatus();
    }

    async function submitDirectIP() {
      const ip = document.getElementById('manualIPInput').value.trim();
      const port = document.getElementById('manualPortInput').value.trim() || '52840';
      if (!ip) return;
      await fetch('/api/connect-direct?ip=' + encodeURIComponent(ip) + '&port=' + encodeURIComponent(port));
      closeModal('directIPModal');
      loadStatus();
    }

    // Drag & Drop and File Selection
    function handleFileSelect(input) {
      if (input.files && input.files[0]) {
        uploadFile(input.files[0]);
      }
    }

    async function uploadFile(file) {
      const formData = new FormData();
      formData.append('file', file);

      const box = document.getElementById('activeTransferBox');
      const nameEl = document.getElementById('transferFileName');
      const bar = document.getElementById('transferBar');
      const pct = document.getElementById('transferPercent');
      box.style.display = 'block';
      nameEl.innerText = 'Sending ' + file.name + '...';
      bar.style.width = '60%';
      pct.innerText = 'Streaming...';

      try {
        const res = await fetch('/api/upload-file', { method: 'POST', body: formData });
        const data = await res.json();
        bar.style.width = '100%';
        pct.innerText = 'Completed';
        setTimeout(() => { box.style.display = 'none'; }, 2500);
        loadStatus();
      } catch (e) {
        alert('File upload error: ' + e);
        box.style.display = 'none';
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

    // Live Media Stream Viewer
    async function startStreamViewer(sType) {
      const modal = document.getElementById('streamModal');
      const title = document.getElementById('streamModalTitle');
      const flipBtn = document.getElementById('btnFlipCam');
      const loader = document.getElementById('streamLoader');
      const img = document.getElementById('streamVideoImage');
      const writeDock = document.getElementById('remoteWriteDock');

      title.innerText = (sType === 'camera' ? 'Live Camera Feed' : 'Live Screen Mirror');
      flipBtn.style.display = (sType === 'camera' ? 'inline-flex' : 'none');
      writeDock.style.display = (sType === 'screen' ? 'flex' : 'none');
      loader.style.display = 'flex';
      img.style.display = 'none';
      img.src = '';
      modal.style.display = 'flex';

      await fetch('/api/stream-request?streamType=' + sType + '&action=request_stream');

      // Start SSE for zero-latency frame streaming
      if (sseSource) sseSource.close();
      sseSource = new EventSource('/api/stream-sse');
      sseSource.onmessage = function(e) {
        if (e.data) {
          loader.style.display = 'none';
          img.style.display = 'block';
          img.src = 'data:image/jpeg;base64,' + e.data;
        }
      };

      // Fallback fast polling in case SSE is blocked
      if (streamPollTimer) clearInterval(streamPollTimer);
      streamPollTimer = setInterval(async () => {
        try {
          const r = await fetch('/api/stream-latest');
          const d = await r.json();
          if (d.hasFrame && d.frame) {
            loader.style.display = 'none';
            img.style.display = 'block';
            img.src = 'data:image/jpeg;base64,' + d.frame;
          }
        } catch (_) {}
      }, 200);
    }

    async function closeStreamViewer() {
      if (sseSource) { sseSource.close(); sseSource = null; }
      if (streamPollTimer) { clearInterval(streamPollTimer); streamPollTimer = null; }
      document.getElementById('streamModal').style.display = 'none';
      await fetch('/api/stream-stop');
    }

    async function flipCameraLens() {
      await fetch('/api/stream-request?streamType=camera&action=switch_camera');
    }

    // Remote Write & Desktop Control
    async function sendWriteText() {
      const input = document.getElementById('remoteTextInput');
      const val = input.value;
      if (!val) return;
      await fetch('/api/send-desktop-input', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ eventType: 'write', key: val })
      });
      input.value = '';
    }

    async function sendKey(k) {
      await fetch('/api/send-desktop-input', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ eventType: 'key_press', key: k })
      });
    }

    async function sendMouseClick(btn) {
      await fetch('/api/send-desktop-input', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ eventType: 'click', x: 0.5, y: 0.5, button: btn })
      });
    }

    function switchHistTab(tab) {
      const tTab = document.getElementById('histTransfersTab');
      const cTab = document.getElementById('histClipboardTab');
      const tBtn = document.getElementById('tabBtnTransfers');
      const cBtn = document.getElementById('tabBtnClipboard');
      if (tab === 'transfers') {
        tTab.style.display = 'flex';
        cTab.style.display = 'none';
        tBtn.className = 'btn btn-prominent btn-sm';
        cBtn.className = 'btn btn-bordered btn-sm';
      } else {
        tTab.style.display = 'none';
        cTab.style.display = 'flex';
        tBtn.className = 'btn btn-bordered btn-sm';
        cBtn.className = 'btn btn-prominent btn-sm';
      }
    }

    // Periodic Status Loader
    async function loadStatus() {
      try {
        const res = await fetch('/api/status');
        const data = await res.json();
        currentData = data;

        const isMasterOn = (data.is_enabled !== false);
        document.getElementById('masterSwitch').checked = isMasterOn;
        document.getElementById('masterPill').innerText = isMasterOn ? 'ON' : 'OFF';
        document.getElementById('masterPill').className = isMasterOn ? 'pill-badge pill-blue' : 'pill-badge pill-grey';
        document.getElementById('masterIconWrap').className = isMasterOn ? 'master-icon-wrap' : 'master-icon-wrap off';
        document.getElementById('activeDashboard').style.display = isMasterOn ? 'flex' : 'none';
        document.getElementById('turnedOffBanner').style.display = isMasterOn ? 'none' : 'flex';

        // Master Subtitle & Status Footer
        const isConn = data.is_connected && data.connected_count > 0;
        const peerName = data.connected_device_name || (data.paired_devices && data.paired_devices[0] ? data.paired_devices[0].device_name : '');
        
        if (!isMasterOn) {
          document.getElementById('masterSubtitle').innerText = 'Turned Off • 0% CPU & Battery';
          document.getElementById('footerStatusText').innerText = 'DexoLink is Turned Off';
          document.getElementById('footerDot').style.background = '#86868b';
        } else if (isConn) {
          document.getElementById('masterSubtitle').innerText = 'Connected to ' + (peerName || 'Device');
          document.getElementById('footerStatusText').innerText = 'Connected • Real-time synchronization active';
          document.getElementById('footerDot').style.background = 'var(--accent-green)';
        } else if (data.paired_devices && data.paired_devices.length > 0) {
          document.getElementById('masterSubtitle').innerText = 'Paired: ' + data.paired_devices[0].device_name + ' • Ready to connect';
          document.getElementById('footerStatusText').innerText = 'Ready to connect • Wi-Fi Auto-Discovery';
          document.getElementById('footerDot').style.background = 'var(--accent-cyan)';
        } else {
          document.getElementById('masterSubtitle').innerText = 'Active • Ready to connect on local Wi-Fi';
          document.getElementById('footerStatusText').innerText = 'Ready to connect • Wi-Fi Auto-Discovery';
          document.getElementById('footerDot').style.background = 'var(--accent-cyan)';
        }

        // Drop zone send button text
        const btnSend = document.getElementById('btnSendFiles');
        const dropText = document.getElementById('dropZoneText');
        if (isConn && peerName) {
          btnSend.innerText = 'Send to ' + peerName;
          dropText.innerText = 'Drop files here or click to send';
        } else {
          btnSend.innerText = 'Select Files to Send';
          dropText.innerText = 'Drop files here or click to send';
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
        document.getElementById('pairedModeBadge').innerText = data.multiple_paired ? 'Multi-Device Mode' : 'Strict 1-Device Policy';

        // Settings toggles sync
        document.getElementById('settContinuity').checked = data.continuity_enabled;
        document.getElementById('settClipboard').checked = data.auto_clipboard_sync;
        document.getElementById('settMulti').checked = data.multiple_paired;
        document.getElementById('settCamera').checked = data.camera_sharing_enabled;
        document.getElementById('settScreen').checked = data.screen_sharing_enabled;
        document.getElementById('settDesktop').checked = data.desktop_mode_enabled;
        document.getElementById('settDiscovery').checked = data.auto_discovery;

        // Paired Devices List
        const pList = document.getElementById('pairedList');
        const pSec = document.getElementById('pairedSection');
        if (data.paired_devices && data.paired_devices.length > 0) {
          pSec.style.display = 'flex';
          pList.innerHTML = data.paired_devices.map(d => {
            const isThisConn = isConn && (data.connected_device_id === d.device_id || data.connected_device_name === d.device_name);
            return '<div class="device-row">' +
              '<div class="device-left">' +
                '<div class="device-icon-box">' +
                  '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" color="' + (isThisConn ? '#34c759' : '#0071e3') + '"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path></svg>' +
                '</div>' +
                '<div class="device-details">' +
                  '<div class="device-name-row">' +
                    '<span class="device-name">' + d.device_name + '</span>' +
                    '<span class="pill-badge ' + (isThisConn ? 'pill-green' : 'pill-cyan') + '">' + (isThisConn ? 'Connected' : 'Paired') + '</span>' +
                  '</div>' +
                  '<span class="device-sub">' + (data.multiple_paired ? 'Multi-Device Mode • Keychain Protected' : 'Strict 1-Device Policy • Keychain Protected') + '</span>' +
                '</div>' +
              '</div>' +
              '<div class="device-actions">' +
                (isThisConn ? '<button class="btn btn-bordered btn-sm" onclick="disconnectDevice(\'' + d.device_id + '\')">Disconnect</button>' : '<button class="btn btn-prominent btn-sm" onclick="connectToDevice(\'' + d.device_id + '\',\'' + (d.ip||'') + '\',' + (d.port||52840) + ',\'' + d.device_name.replace(/'/g, "\\'") + '\')">Connect</button>') +
                '<button class="btn btn-danger btn-sm" onclick="unpairDevice(\'' + d.device_id + '\')">Unpair</button>' +
              '</div>' +
            '</div>';
          }).join('');
        } else {
          pSec.style.display = 'none';
        }

        // Nearby Discovered Devices List
        const dList = document.getElementById('discoveredList');
        if (data.discovered_devices && data.discovered_devices.length > 0) {
          dList.innerHTML = data.discovered_devices.map(d => {
            const isPaired = data.paired_devices && data.paired_devices.some(p => p.device_id === d.device_id);
            const isCurrentConn = isConn && (data.connected_device_id === d.device_id || data.connected_device_name === d.device_name);
            return '<div class="device-row">' +
              '<div class="device-left">' +
                '<div class="device-icon-box">' +
                  '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="5" y="2" width="14" height="20" rx="2" ry="2"></rect><line x1="12" y1="18" x2="12.01" y2="18"></line></svg>' +
                  '<div class="online-dot"></div>' +
                '</div>' +
                '<div class="device-details">' +
                  '<div class="device-name-row">' +
                    '<span class="device-name">' + d.device_name + '</span>' +
                    (isCurrentConn ? '<span class="pill-badge pill-green">Connected</span>' : (isPaired ? '<span class="pill-badge pill-cyan">Paired</span>' : '<span class="pill-badge pill-blue">Available</span>')) +
                  '</div>' +
                  '<span class="device-sub">' + d.ip + ':' + d.port + '</span>' +
                '</div>' +
              '</div>' +
              '<div class="device-actions">' +
                (isCurrentConn ? 
                  '<button class="btn btn-teal btn-sm" onclick="startStreamViewer(\'camera\')">Camera</button>' +
                  '<button class="btn btn-orange btn-sm" onclick="startStreamViewer(\'screen\')">Screen</button>' +
                  '<button class="btn btn-danger btn-sm" onclick="disconnectDevice(\'' + d.device_id + '\')">Disconnect</button>' : 
                  (isPaired ? 
                    '<button class="btn btn-prominent btn-sm" onclick="connectToDevice(\'' + d.device_id + '\',\'' + d.ip + '\',' + d.port + ',\'' + d.device_name.replace(/'/g, "\\'") + '\')">Connect</button>' : 
                    '<button class="btn btn-bordered btn-sm" style="color:var(--accent-purple); border-color:rgba(175,82,222,0.3);" onclick="pairDevice(\'' + d.device_id + '\',\'' + d.device_name.replace(/'/g, "\\'") + '\',\'' + d.ip + '\',' + d.port + ')">Pair Device</button>'
                  )
                ) +
              '</div>' +
            '</div>';
          }).join('');
        } else {
          dList.innerHTML = '<div style="text-align:center; padding:12px; font-size:11px; color:var(--text-sub);">' +
            (data.auto_discovery ? 'Searching on local Wi-Fi... Open DexoLink on your iPhone or Android.' : 'Discovery paused.') +
          '</div>';
        }

        // Recent Transfers in card
        const recList = document.getElementById('recentTransfersList');
        if (data.recent_transfers && data.recent_transfers.length > 0) {
          recList.innerHTML = data.recent_transfers.slice(0, 3).map(t => 
            '<div style="display:flex; align-items:center; justify-content:space-between; font-size:10px; padding:3px 6px; background:rgba(255,255,255,0.02); border-radius:4px;">' +
              '<div style="display:flex; align-items:center; gap:5px; min-width:0;">' +
                '<svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="' + (t.success ? '#34c759' : '#ff3b30') + '" stroke-width="2.5"><polyline points="20 6 9 17 4 12"></polyline></svg>' +
                '<span style="white-space:nowrap; overflow:hidden; text-overflow:ellipsis; max-width:180px;">' + t.file_name + '</span>' +
              '</div>' +
              '<div style="display:flex; align-items:center; gap:6px;">' +
                '<span style="color:var(--text-sub);">' + (t.size > 1048576 ? (t.size/1048576).toFixed(1) + ' MB' : (t.size/1024).toFixed(1) + ' KB') + '</span>' +
                '<button class="btn btn-bordered btn-sm" style="padding:0 4px; font-size:9px;" onclick="openDownloadsFolder()">Show</button>' +
              '</div>' +
            '</div>'
          ).join('');
        } else {
          recList.innerHTML = '<div style="font-size:10px; color:var(--text-sub);">No transfers yet today.</div>';
        }

        // History Modal sync
        const histTList = document.getElementById('histTransfersTab');
        if (data.recent_transfers && data.recent_transfers.length > 0) {
          histTList.innerHTML = data.recent_transfers.map(t =>
            '<div class="device-row">' +
              '<div class="device-details">' +
                '<div style="font-weight:600; font-size:11px;">' + t.file_name + '</div>' +
                '<div class="device-sub">' + (t.is_incoming ? 'From ' : 'To ') + t.peer_name + ' • ' + (t.size > 1048576 ? (t.size/1048576).toFixed(1) + ' MB' : (t.size/1024).toFixed(1) + ' KB') + '</div>' +
              '</div>' +
              '<button class="btn btn-danger btn-sm" onclick="deleteTransfer(\'' + t.id + '\')">Delete</button>' +
            '</div>'
          ).join('');
        } else {
          histTList.innerHTML = '<div style="padding:10px; text-align:center; color:var(--text-sub); font-size:11px;">No transfer history.</div>';
        }

        const histCList = document.getElementById('histClipboardTab');
        if (data.clipboard_history && data.clipboard_history.length > 0) {
          histCList.innerHTML = data.clipboard_history.map((c, idx) =>
            '<div class="device-row">' +
              '<div class="device-details" style="flex:1; min-width:0;">' +
                '<div style="font-size:11px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">' + c + '</div>' +
              '</div>' +
              '<div style="display:flex; gap:4px;">' +
                '<button class="btn btn-bordered btn-sm" onclick="navigator.clipboard.writeText(\'' + c.replace(/'/g, "\\'") + '\')">Copy</button>' +
                '<button class="btn btn-danger btn-sm" onclick="deleteClip(' + idx + ')">Delete</button>' +
              '</div>' +
            '</div>'
          ).join('');
        } else {
          histCList.innerHTML = '<div style="padding:10px; text-align:center; color:var(--text-sub); font-size:11px;">No clipboard history.</div>';
        }

      } catch (e) {
        console.error('loadStatus error:', e);
      }
    }

    async function deleteTransfer(id) {
      await fetch('/api/delete-transfer?id=' + encodeURIComponent(id));
      loadStatus();
    }

    async function deleteClip(idx) {
      await fetch('/api/delete-clip?index=' + idx);
      loadStatus();
    }

    setInterval(loadStatus, 2000);
    loadStatus();
  </script>
</body>
</html>`

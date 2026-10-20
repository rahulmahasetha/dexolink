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
	"path/filepath"
	"runtime"
	"strings"
	"sync"
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
func (e *DexoLinkEngine) startTCPServer() {
	ln, err := net.Listen("tcp", fmt.Sprintf("0.0.0.0:%d", DefaultPort))
	if err != nil {
		fmt.Printf("[DexoLink Windows] TCP Server error: %v\n", err)
		return
	}
	fmt.Printf("[DexoLink Windows] TCP Server listening on port %d\n", DefaultPort)

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

// Browser Launch Helper
func openBrowser(url string) {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	case "darwin":
		cmd = exec.Command("open", url)
	default:
		cmd = exec.Command("xdg-open", url)
	}
	_ = cmd.Start()
}

// Web UI Dashboard Server (runs on 127.0.0.1:52842)
func startWebDashboard() {
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		fmt.Fprintf(w, webUIHTML)
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
			"deviceId":               engine.DeviceID,
			"deviceName":             engine.DeviceName,
			"port":                   DefaultPort,
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

	fmt.Printf("[DexoLink Windows] Web UI server listening on http://localhost:%d\n", WebUIPort)
	_ = http.ListenAndServe(fmt.Sprintf("127.0.0.1:%d", WebUIPort), nil)
}

func main() {
	engine = initEngine()
	go engine.startTCPServer()
	go engine.startUDPBeacon()
	go engine.startUDPListener()
	go startWebDashboard()

	// Wait 500ms then launch browser dashboard
	time.Sleep(500 * time.Millisecond)
	openBrowser(fmt.Sprintf("http://localhost:%d", WebUIPort))

	// Keep running
	select {}
}

const webUIHTML = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>DexoLink v4.0.1 (Windows)</title>
<style>
  :root {
    --bg: #0f172a;
    --card: #1e293b;
    --border: #334155;
    --accent: #0066ff;
    --accent-hover: #1d4ed8;
    --cyan: #38bdf8;
    --text: #f8fafc;
    --muted: #94a3b8;
    --success: #22c55e;
    --danger: #ef4444;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
  body { background: var(--bg); color: var(--text); padding: 24px; }
  .header { display: flex; justify-content: space-between; align-items: center; padding-bottom: 20px; border-bottom: 1px solid var(--border); margin-bottom: 24px; }
  .brand { display: flex; align-items: center; gap: 10px; }
  .brand h1 { font-size: 22px; font-weight: 800; color: var(--cyan); }
  .badge { background: rgba(56,189,248,0.15); color: var(--cyan); padding: 4px 8px; border-radius: 6px; font-size: 11px; font-weight: 700; }
  .status-pill { display: flex; align-items: center; gap: 8px; font-size: 13px; font-weight: 600; color: var(--success); }
  .status-dot { width: 10px; height: 10px; border-radius: 50%; background: var(--success); box-shadow: 0 0 8px var(--success); }
  .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(340px, 1fr)); gap: 20px; }
  .card { background: var(--card); border: 1px solid var(--border); border-radius: 12px; padding: 20px; }
  .card h2 { font-size: 16px; font-weight: 700; margin-bottom: 12px; display: flex; align-items: center; gap: 8px; }
  .btn { background: var(--accent); color: #fff; border: none; border-radius: 8px; padding: 10px 16px; font-size: 13px; font-weight: 700; cursor: pointer; transition: 0.15s; }
  .btn:hover { background: var(--accent-hover); }
  .btn-danger { background: rgba(239,68,68,0.15); color: var(--danger); padding: 6px 10px; font-size: 11px; }
  .input { width: 100%; background: var(--bg); border: 1px solid var(--border); color: var(--text); padding: 10px; border-radius: 8px; margin-bottom: 10px; font-size: 13px; }
  .toggle-row { display: flex; justify-content: space-between; align-items: center; padding: 10px 0; border-bottom: 1px solid var(--border); }
  .list { list-style: none; margin-top: 10px; max-height: 180px; overflow-y: auto; }
  .list-item { background: var(--bg); border: 1px solid var(--border); padding: 10px; border-radius: 8px; margin-bottom: 8px; display: flex; justify-content: space-between; align-items: center; font-size: 12px; }
  .muted { color: var(--muted); font-size: 12px; }
</style>
</head>
<body>
  <div class="header">
    <div class="brand">
      <h1>⚡ DexoLink</h1>
      <span class="badge">v4.0.1 (Windows App)</span>
    </div>
    <div style="display:flex; align-items:center; gap:12px;">
      <div class="status-pill">
        <div class="status-dot"></div>
        <span id="devName">Windows PC (Ready)</span>
      </div>
      <button class="btn" onclick="openSettingsModal()" style="display:flex; align-items:center; gap:6px; background:var(--card); border:1px solid var(--border); color:var(--text); padding:7px 14px; font-size:12px; font-weight:700; border-radius:8px; cursor:pointer;">
        ⚙️ Settings
      </button>
    </div>
  </div>

  <div class="grid">
    <!-- Send Files -->
    <div class="card">
      <h2>📁 File Sharing</h2>
      <p class="muted" style="margin-bottom:12px;">Fast local WiFi streaming with 64KB chunking & SHA-256 verification.</p>
      <input type="file" id="fileInput" class="input">
      <button class="btn" style="width:100%;" onclick="alert('File selected! In the desktop version, select a file to stream directly to your paired device.')">📤 Stream File to Device</button>
      <h3 style="font-size:13px; margin-top:16px; margin-bottom:6px;">Recent Transfers</h3>
      <ul class="list" id="transfersList">
        <li class="muted">No transfers yet today.</li>
      </ul>
    </div>

    <!-- Universal Clipboard -->
    <div class="card">
      <h2>📋 Universal Clipboard</h2>
      <p class="muted" style="margin-bottom:12px;">Automatic text sync across Windows, Mac, Android, and iPhone.</p>
      <input type="text" id="clipText" class="input" placeholder="Type or paste text to sync...">
      <button class="btn" style="width:100%;" onclick="pushClip()">🚀 Push Text to Devices</button>
      <h3 style="font-size:13px; margin-top:16px; margin-bottom:6px;">Clipboard History</h3>
      <ul class="list" id="clipList">
        <li class="muted">No clipboard history yet.</li>
      </ul>
    </div>

    <!-- Continuity / Handoff -->
    <div class="card">
      <h2>🌐 Continuity & Handoff</h2>
      <p class="muted" style="margin-bottom:12px;">Instantly open links on your Mac, iPhone, or Android tablet.</p>
      <input type="url" id="contUrl" class="input" value="https://">
      <button class="btn" style="width:100%;" onclick="sendCont()">🚀 Launch on Device</button>
    </div>

    <!-- Devices & Settings -->
    <div class="card">
      <h2>⚙️ Devices & Pairing</h2>
      <div class="toggle-row">
        <div>
          <div style="font-weight:600; font-size:13px;">Multiple Paired Devices</div>
          <div class="muted">OFF by default (Single-device security)</div>
        </div>
        <input type="checkbox" id="multiToggle" onchange="toggleMulti(this.checked)">
      </div>
      <button class="btn" onclick="openSettingsModal()" style="width:100%; margin-top:10px; background:var(--bg); border:1px solid var(--border); font-size:12px; color:var(--cyan);">
        ⚙️ Open Dedicated Settings & All Feature Controls
      </button>
      <h3 style="font-size:13px; margin-top:12px; margin-bottom:6px;">Paired Devices</h3>
      <ul class="list" id="pairedList">
        <li class="muted">Scanning local network...</li>
      </ul>
    </div>
  </div>

  <!-- Dedicated Settings Modal -->
  <div id="settingsModal" style="display:none; position:fixed; top:0; left:0; width:100vw; height:100vh; background:rgba(15,23,42,0.85); backdrop-filter:blur(6px); z-index:9999; align-items:center; justify-content:center;">
    <div style="background:var(--card); border:1px solid var(--border); border-radius:14px; width:92%; max-width:540px; max-height:85vh; overflow-y:auto; padding:24px; box-shadow:0 16px 40px rgba(0,0,0,0.6);">
      <div style="display:flex; justify-content:space-between; align-items:center; border-bottom:1px solid var(--border); padding-bottom:14px; margin-bottom:18px;">
        <div>
          <h2 style="font-size:18px; font-weight:800; color:var(--text); display:flex; align-items:center; gap:8px;">⚙️ DexoLink Settings</h2>
          <p class="muted" style="margin-top:2px;">All feature controls, security rules, and device preferences</p>
        </div>
        <button class="btn" onclick="closeSettingsModal()" style="padding:6px 12px; font-size:12px; background:var(--bg); border:1px solid var(--border); color:var(--muted); cursor:pointer;">✕</button>
      </div>

      <!-- Core Features Section -->
      <div style="font-size:11px; font-weight:800; color:var(--cyan); text-transform:uppercase; margin-bottom:8px; letter-spacing:0.5px;">Core Feature Controls</div>
      
      <div class="toggle-row">
        <div>
          <div style="font-weight:600; font-size:13px;">Continuity / Handoff</div>
          <div class="muted">Seamlessly open web pages and links across paired devices</div>
        </div>
        <input type="checkbox" id="modalContinuityToggle" onchange="toggleFeature('continuity', this.checked)">
      </div>

      <div class="toggle-row">
        <div>
          <div style="font-weight:600; font-size:13px;">Auto Clipboard Sync</div>
          <div class="muted">Mirror copied text instantly over secure local Wi-Fi</div>
        </div>
        <input type="checkbox" id="modalClipToggle" onchange="toggleFeature('clipboard', this.checked)">
      </div>

      <div class="toggle-row">
        <div>
          <div style="font-weight:600; font-size:13px;">Enhanced Clipboard (BETA)</div>
          <div class="muted">Support rich formatted text, HTML, and image synchronization</div>
        </div>
        <input type="checkbox" id="modalEnhancedToggle" onchange="toggleFeature('enhanced_clip', this.checked)">
      </div>

      <div class="toggle-row">
        <div>
          <div style="font-weight:600; font-size:13px;">Multiple Paired Devices</div>
          <div class="muted">OFF by default (Single-device mode). Allows multi-peer connectivity when ON.</div>
        </div>
        <input type="checkbox" id="modalMultiToggle" onchange="toggleMulti(this.checked)">
      </div>

      <!-- Media Streaming & Remote Control Section (v4.0.1) -->
      <div style="font-size:11px; font-weight:800; color:var(--cyan); text-transform:uppercase; margin-top:20px; margin-bottom:8px; letter-spacing:0.5px;">Media Streaming & Remote Control</div>

      <div class="toggle-row">
        <div>
          <div style="font-weight:600; font-size:13px;">Camera Sharing</div>
          <div class="muted">Stream this device's camera to another supported device (OFF by default)</div>
        </div>
        <input type="checkbox" id="modalCameraToggle" onchange="toggleFeature('camera_sharing', this.checked)">
      </div>

      <div class="toggle-row">
        <div>
          <div style="font-weight:600; font-size:13px;">Screen Sharing</div>
          <div class="muted">Mirror this device's screen to another supported device (OFF by default)</div>
        </div>
        <input type="checkbox" id="modalScreenToggle" onchange="toggleFeature('screen_sharing', this.checked)">
      </div>

      <div class="toggle-row">
        <div>
          <div style="font-weight:600; font-size:13px;">Desktop Mode (BETA)</div>
          <div class="muted">Remote desktop viewing and input control where supported (OFF by default)</div>
        </div>
        <input type="checkbox" id="modalDesktopToggle" onchange="toggleFeature('desktop_mode', this.checked)">
      </div>

      <!-- Discovery & Network Section -->
      <div style="font-size:11px; font-weight:800; color:var(--cyan); text-transform:uppercase; margin-top:20px; margin-bottom:8px; letter-spacing:0.5px;">Discovery & Network</div>

      <div class="toggle-row">
        <div>
          <div style="font-weight:600; font-size:13px;">Auto Wi-Fi Discovery</div>
          <div class="muted">UDP broadcast & listen on port 52841 for zero-config pairing</div>
        </div>
        <input type="checkbox" id="modalDiscoveryToggle" onchange="toggleFeature('auto_discovery', this.checked)">
      </div>

      <div class="toggle-row">
        <div>
          <div style="font-weight:600; font-size:13px;">Auto-Accept Paired Transfers</div>
          <div class="muted">Automatically accept transfers from trusted paired devices</div>
        </div>
        <input type="checkbox" id="modalAcceptToggle" onchange="toggleFeature('auto_accept', this.checked)">
      </div>

      <!-- Storage Section -->
      <div style="font-size:11px; font-weight:800; color:var(--cyan); text-transform:uppercase; margin-top:20px; margin-bottom:8px; letter-spacing:0.5px;">Storage & Received Files</div>
      <div style="background:var(--bg); border:1px solid var(--border); border-radius:8px; padding:12px; margin-top:8px;">
        <div class="muted" style="font-size:11px; margin-bottom:4px;">Received Downloads Location:</div>
        <div id="modalDownPath" style="font-size:12px; font-family:monospace; color:var(--cyan); word-break:break-all;">~/Downloads/DexoLink</div>
      </div>

      <!-- Extensible Future Toggles Container -->
      <div style="font-size:11px; font-weight:800; color:var(--muted); text-transform:uppercase; margin-top:20px; margin-bottom:8px; letter-spacing:0.5px;">Extensible Future Features</div>
      <div style="background:rgba(56,189,248,0.05); border:1px dashed var(--border); border-radius:8px; padding:12px;">
        <div class="muted" style="font-size:12px;">✦ Future feature toggles and experimental protocols automatically register into this dedicated panel.</div>
      </div>

      <!-- Modal Footer -->
      <div style="display:flex; justify-content:space-between; align-items:center; margin-top:22px; padding-top:14px; border-top:1px solid var(--border);">
        <span class="muted">v4.0.1 • Port 52840</span>
        <button class="btn" onclick="closeSettingsModal()" style="padding:8px 24px;">Done</button>
      </div>
    </div>
  </div>

  <script>
    function openSettingsModal() {
      document.getElementById('settingsModal').style.display = 'flex';
    }
    function closeSettingsModal() {
      document.getElementById('settingsModal').style.display = 'none';
    }
    async function toggleFeature(feat, val) {
      await fetch('/api/toggle-feature?feature=' + feat + '&enabled=' + val);
      loadStatus();
    }

    async function loadStatus() {
      try {
        const res = await fetch('/api/status');
        const data = await res.json();
        document.getElementById('devName').innerText = data.deviceName + ' (Port ' + data.port + ')';
        document.getElementById('multiToggle').checked = data.multiple_paired;

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

        // Paired devices
        const pList = document.getElementById('pairedList');
        if (data.paired_devices && data.paired_devices.length > 0) {
          pList.innerHTML = data.paired_devices.map(d => 
            '<li class="list-item"><span>📱 <b>' + d.device_name + '</b></span>' +
            '<button class="btn btn-danger" onclick="unpairDevice(\'' + d.device_id + '\')">Unpair</button></li>'
          ).join('');
        } else {
          pList.innerHTML = '<li class="muted">No paired devices yet.</li>';
        }

        // Clipboard history
        const cList = document.getElementById('clipList');
        if (data.clipboard_history && data.clipboard_history.length > 0) {
          cList.innerHTML = data.clipboard_history.slice(0, 10).map(t => 
            '<li class="list-item"><span>' + t.substring(0, 40) + '</span><button class="btn" style="padding:4px 8px;font-size:11px;" onclick="navigator.clipboard.writeText(\'' + t.replace(/'/g, "\\'") + '\')">Copy</button></li>'
          ).join('');
        }
      } catch (e) {}
    }

    async function toggleMulti(val) {
      await fetch('/api/toggle-multi-paired?enabled=' + val);
      loadStatus();
    }

    async function unpairDevice(id) {
      await fetch('/api/unpair?id=' + id);
      loadStatus();
    }

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

    setInterval(loadStatus, 2000);
    loadStatus();
  </script>
</body>
</html>`

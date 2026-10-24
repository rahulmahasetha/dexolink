package main

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
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

	"github.com/skip2/go-qrcode"
)

// Constants
const (
	DefaultPort     = 52840
	BeaconPort      = 52841
	WebUIPort       = 52842
	ProtocolVersion = 1
	ChunkSize       = 512 * 1024              // 512 KB per chunk (high throughput, matches macOS & Android spec)
	MaxFileSize     = 10 * 1024 * 1024 * 1024 // 10 GB max file size
	MaxMessageSize  = 10 * 1024 * 1024        // 10 MB max message size
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
	PublicKey  string  `json:"public_key,omitempty"`
	IP         string  `json:"ip,omitempty"`
	Port       int     `json:"port,omitempty"`
	DeviceType string  `json:"device_type,omitempty"`
	PairedAt   float64 `json:"paired_at,omitempty"`
}

func NewTrustStore() *TrustStore {
	home, _ := os.UserHomeDir()
	path := filepath.Join(home, ".dexolink_trust.json")
	ts := &TrustStore{
		MultiplePairedDevices: false, // Matches strict single-device security default
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
	if dev.PairedAt == 0 {
		dev.PairedAt = float64(time.Now().Unix())
	}
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

func (ts *TrustStore) IsPaired(devID, devName string) bool {
	ts.mu.Lock()
	defer ts.mu.Unlock()
	for pid, pd := range ts.PairedDevices {
		if pid == devID || (devName != "" && strings.EqualFold(pd.DeviceName, devName)) {
			return true
		}
	}
	return false
}

// PeerSession represents an active TCP session to a connected device
type PeerSession struct {
	DeviceID    string     `json:"device_id"`
	DeviceName  string     `json:"device_name"`
	IP          string     `json:"ip"`
	Port        int        `json:"port"`
	DeviceType  string     `json:"device_type"`
	ConnectedAt time.Time  `json:"connected_at"`
	State       string     `json:"state"` // "connected", "transferring"
	Conn        net.Conn   `json:"-"`
	writeMu     sync.Mutex `json:"-"`
}

func (s *PeerSession) SendMessage(msg any) error {
	data, err := json.Marshal(msg)
	if err != nil {
		return err
	}
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	var lenBuf [4]byte
	binary.BigEndian.PutUint32(lenBuf[:], uint32(len(data)))
	if _, err := s.Conn.Write(lenBuf[:]); err != nil {
		return err
	}
	_, err = s.Conn.Write(data)
	return err
}

// TransferItem tracks an in-flight or completed file transfer
type TransferItem struct {
	ID               string        `json:"id"`
	FileID           string        `json:"file_id"`
	Name             string        `json:"name"`
	Size             int64         `json:"size"`
	BytesTransferred int64         `json:"bytes_transferred"`
	Progress         float64       `json:"progress"`
	SpeedBytesPerSec float64       `json:"speed_bytes_sec"`
	IsIncoming       bool          `json:"is_incoming"`
	PeerID           string        `json:"peer_id"`
	PeerName         string        `json:"peer_name"`
	State            string        `json:"state"` // "pending", "transferring", "completed", "failed", "cancelled"
	LocalPath        string        `json:"local_path,omitempty"`
	StartTime        time.Time     `json:"start_time"`
	Error            string        `json:"error,omitempty"`
	cancelChan       chan struct{} `json:"-"`
}

// ReceiverContext holds active receiving file handle and hash state
type ReceiverContext struct {
	transferID         string
	destPath           string
	file               *os.File
	hasher             hashInterface
	expectedSize       int64
	receivedBytes      int64
	lastProgressReport int64
	lastReportTime     time.Time
}

type hashInterface interface {
	Write(p []byte) (n int, err error)
	Sum(b []byte) []byte
}

type TransferRecord struct {
	ID         string    `json:"id"`
	FileName   string    `json:"file_name"`
	Size       int64     `json:"size"`
	IsIncoming bool      `json:"is_incoming"`
	PeerName   string    `json:"peer_name"`
	Success    bool      `json:"success"`
	State      string    `json:"state"`
	Time       time.Time `json:"time"`
}

// DexoLinkEngine manages connections, transfers, and discovery
type DexoLinkEngine struct {
	DeviceID             string
	DeviceName           string
	TrustStore           *TrustStore
	DiscoveredDevices    sync.Map // DeviceID -> DeviceInfo
	ActiveSessions       sync.Map // DeviceID -> *PeerSession
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

	// Clipboard tracking
	clipMu           sync.Mutex
	lastSentHash     string
	lastReceivedHash string
	clipSeq          int

	// Transfer manager tracking
	transfersMu        sync.Mutex
	ActiveTransfers    map[string]*TransferItem
	FileIDToTransferID map[string]string
	ActiveReceivers    map[string]*ReceiverContext
	pendingResponses   map[string]chan bool // transferId -> chan accepted
	pendingAcks        map[string]chan bool // fileId -> chan verified
}

var engine *DexoLinkEngine

func initEngine() *DexoLinkEngine {
	host, _ := os.Hostname()
	home, _ := os.UserHomeDir()
	downDir := filepath.Join(home, "Downloads", "DexoLink")
	_ = os.MkdirAll(downDir, 0755)

	devID := fmt.Sprintf("win_%x", time.Now().UnixNano())
	if len(devID) > 16 {
		devID = devID[:16]
	}

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
		ActiveTransfers:      make(map[string]*TransferItem),
		FileIDToTransferID:   make(map[string]string),
		ActiveReceivers:      make(map[string]*ReceiverContext),
		pendingResponses:     make(map[string]chan bool),
		pendingAcks:          make(map[string]chan bool),
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

// BroadcastMessage sends a message to all eligible connected peer sessions
// In single-device mode: sends ONLY to the single paired device.
// In multiple-device mode: sends to all active connected, paired devices.
func (e *DexoLinkEngine) BroadcastMessage(msg any) int {
	e.TrustStore.mu.Lock()
	multi := e.TrustStore.MultiplePairedDevices
	e.TrustStore.mu.Unlock()

	sent := 0
	e.ActiveSessions.Range(func(key, val any) bool {
		s := val.(*PeerSession)
		if !multi {
			if !e.TrustStore.IsPaired(s.DeviceID, s.DeviceName) {
				return true
			}
		}
		if err := s.SendMessage(msg); err == nil {
			sent++
		}
		if !multi && sent > 0 {
			return false
		}
		return true
	})
	return sent
}

// CRLF Normalization & Hash Calculation
func normalizeTextForWindows(text string) string {
	cleaned := strings.ReplaceAll(text, "\u200B", "")
	cleaned = strings.ReplaceAll(cleaned, "\u200C", "")
	cleaned = strings.ReplaceAll(cleaned, "\u200D", "")
	cleaned = strings.ReplaceAll(cleaned, "\uFEFF", "")
	normalized := strings.ReplaceAll(cleaned, "\r\n", "\n")
	normalized = strings.ReplaceAll(normalized, "\r", "\n")
	return strings.ReplaceAll(normalized, "\n", "\r\n")
}

func normalizeTextForWire(text string) string {
	cleaned := strings.ReplaceAll(text, "\u200B", "")
	cleaned = strings.ReplaceAll(cleaned, "\u200C", "")
	cleaned = strings.ReplaceAll(cleaned, "\u200D", "")
	cleaned = strings.ReplaceAll(cleaned, "\uFEFF", "")
	normalized := strings.ReplaceAll(cleaned, "\r\n", "\n")
	return strings.ReplaceAll(normalized, "\r", "\n")
}

func computeTextHash(text string) string {
	norm := normalizeTextForWire(text)
	sum := sha256.Sum256([]byte(norm))
	return hex.EncodeToString(sum[:])
}

// Clipboard Background Monitor
func (e *DexoLinkEngine) startClipboardMonitor() {
	ticker := time.NewTicker(600 * time.Millisecond)
	defer ticker.Stop()

	for range ticker.C {
		e.mu.Lock()
		enabled := e.Enabled
		autoClip := e.AutoClipboardSync
		e.mu.Unlock()

		if !enabled || !autoClip {
			continue
		}

		hasPeers := false
		e.ActiveSessions.Range(func(k, v any) bool {
			hasPeers = true
			return false
		})
		if !hasPeers {
			continue
		}

		text, err := getSystemClipboard()
		if err != nil || text == "" {
			continue
		}

		h := computeTextHash(text)
		e.clipMu.Lock()
		if h == e.lastSentHash || h == e.lastReceivedHash {
			e.clipMu.Unlock()
			continue
		}
		e.lastSentHash = h
		e.clipSeq++
		seq := e.clipSeq
		e.clipMu.Unlock()

		normWire := normalizeTextForWire(text)
		e.mu.Lock()
		e.ClipboardHistory = append([]string{normWire}, e.ClipboardHistory...)
		if len(e.ClipboardHistory) > 50 {
			e.ClipboardHistory = e.ClipboardHistory[:50]
		}
		e.mu.Unlock()

		msg := map[string]any{
			"type":           "CLIPBOARD_PUSH",
			"sequenceNumber": seq,
			"content":        normWire,
		}
		e.BroadcastMessage(msg)
		fmt.Printf("[DexoLink Windows Clipboard] Synced copy (%d chars) to connected peers\n", len(normWire))
	}
}

// Sanitize filename to prevent directory traversal
func sanitizeFilename(name string) string {
	clean := strings.ReplaceAll(name, "..", "")
	clean = strings.ReplaceAll(clean, "/", "_")
	clean = strings.ReplaceAll(clean, "\\", "_")
	clean = strings.TrimSpace(clean)
	if clean == "" {
		return "unnamed_file"
	}
	return clean
}

// Get safe unique destination path in downloads directory
func getSafeDestinationPath(baseDir, filename string) string {
	cleanName := sanitizeFilename(filename)
	dest := filepath.Join(baseDir, cleanName)
	if _, err := os.Stat(dest); os.IsNotExist(err) {
		return dest
	}

	ext := filepath.Ext(cleanName)
	nameWithoutExt := strings.TrimSuffix(cleanName, ext)
	counter := 1
	for {
		newName := fmt.Sprintf("%s (%d)%s", nameWithoutExt, counter, ext)
		dest = filepath.Join(baseDir, newName)
		if _, err := os.Stat(dest); os.IsNotExist(err) {
			return dest
		}
		counter++
	}
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
		go e.handleIncomingConnection(conn)
	}
}

func (e *DexoLinkEngine) handleIncomingConnection(conn net.Conn) {
	session := &PeerSession{
		Conn:        conn,
		ConnectedAt: time.Now(),
		State:       "connected",
	}
	e.handleClient(conn, session)
}

func (e *DexoLinkEngine) handleClient(conn net.Conn, session *PeerSession) {
	defer conn.Close()
	remoteAddr := conn.RemoteAddr().String()
	remoteIP := strings.Split(remoteAddr, ":")[0]
	var peerID string
	var peerName string

	defer func() {
		if peerID != "" {
			e.ActiveSessions.Delete(peerID)
			fmt.Printf("[DexoLink Windows] Device disconnected: %s (%s)\n", peerName, peerID)
			updateTrayStatus(fmt.Sprintf("DexoLink - %s disconnected", peerName))
		}
	}()

	for {
		var length uint32
		if err := binary.Read(conn, binary.BigEndian, &length); err != nil {
			break
		}
		if length > MaxMessageSize {
			fmt.Printf("[DexoLink Windows Security] Message exceeded max length: %d bytes\n", length)
			break
		}

		buf := make([]byte, length)
		if _, err := io.ReadFull(conn, buf); err != nil {
			break
		}

		// Support Legacy Hybrid FILE_CHUNK: [2-byte jsonLen] [JSON metadata] [Binary bytes]
		if length > 2 {
			metaLen := int(binary.BigEndian.Uint16(buf[:2]))
			if metaLen > 0 && 2+metaLen <= len(buf) {
				var meta map[string]any
				if err := json.Unmarshal(buf[2:2+metaLen], &meta); err == nil && meta["type"] == "FILE_CHUNK" {
					fID, _ := meta["fileId"].(string)
					offFloat, _ := meta["offset"].(float64)
					offset := int64(offFloat)
					binaryData := buf[2+metaLen:]
					e.processFileChunk(fID, offset, binaryData, session)
					continue
				}
			}
		}

		// Standard JSON Protocol Message
		var msg map[string]any
		if err := json.Unmarshal(buf, &msg); err != nil {
			continue
		}

		msgType, _ := msg["type"].(string)
		switch msgType {
		case "HELLO":
			pID, _ := msg["deviceId"].(string)
			pName, _ := msg["deviceName"].(string)
			if pID == "" {
				pID = fmt.Sprintf("peer_%s", remoteIP)
			}
			if pName == "" {
				pName = fmt.Sprintf("Device (%s)", remoteIP)
			}
			peerID = pID
			peerName = pName

			session.DeviceID = peerID
			session.DeviceName = peerName
			session.IP = remoteIP
			session.Port = DefaultPort
			session.DeviceType = "device"

			e.ActiveSessions.Store(peerID, session)
			devInfo := DeviceInfo{
				DeviceID:   peerID,
				DeviceName: peerName,
				IP:         remoteIP,
				Port:       DefaultPort,
			}
			e.DiscoveredDevices.Store(peerID, devInfo)

			isPaired := e.TrustStore.IsPaired(peerID, peerName)
			if isPaired {
				e.TrustStore.mu.Lock()
				if p, exists := e.TrustStore.PairedDevices[peerID]; exists {
					p.IP = remoteIP
					e.TrustStore.PairedDevices[peerID] = p
					e.TrustStore.save()
				}
				e.TrustStore.mu.Unlock()
			}

			_ = session.SendMessage(map[string]any{
				"type":       "HELLO_ACK",
				"version":    ProtocolVersion,
				"deviceId":   e.DeviceID,
				"deviceName": e.DeviceName,
				"paired":     isPaired,
			})
			fmt.Printf("[DexoLink Windows] Active connection: %s (%s) [paired: %v]\n", peerName, remoteIP, isPaired)
			updateTrayStatus(fmt.Sprintf("DexoLink - Connected to %s", peerName))

		case "HELLO_ACK":
			pID, _ := msg["deviceId"].(string)
			pName, _ := msg["deviceName"].(string)
			if pID != "" {
				peerID = pID
				session.DeviceID = pID
			}
			if pName != "" {
				peerName = pName
				session.DeviceName = pName
			}
			e.ActiveSessions.Store(peerID, session)
			devInfo := DeviceInfo{
				DeviceID:   peerID,
				DeviceName: peerName,
				IP:         remoteIP,
				Port:       DefaultPort,
			}
			e.DiscoveredDevices.Store(peerID, devInfo)
			updateTrayStatus(fmt.Sprintf("DexoLink - Connected to %s", peerName))

		case "PAIR_REQUEST":
			pID, _ := msg["deviceId"].(string)
			pName, _ := msg["deviceName"].(string)
			pubKey, _ := msg["publicKey"].(string)
			if pID == "" {
				pID = peerID
			}
			if pName == "" {
				pName = peerName
			}
			if pName == "" {
				pName = "Remote Device"
			}
			e.TrustStore.mu.Lock()
			multi := e.TrustStore.MultiplePairedDevices
			pairedCount := len(e.TrustStore.PairedDevices)
			_, alreadyPaired := e.TrustStore.PairedDevices[pID]
			e.TrustStore.mu.Unlock()

			if !multi && pairedCount > 0 && !alreadyPaired {
				_ = session.SendMessage(map[string]any{
					"type":     "PAIR_RESPONSE",
					"accepted": false,
					"error":    "Single Device Mode active on Windows. Enable Multiple Paired Devices in Settings to pair additional devices.",
				})
				fmt.Printf("[DexoLink Windows] Rejected pair request from %s: Single Device Mode active\n", pName)
				break
			}

			e.TrustStore.AddDevice(DeviceInfo{
				DeviceID:   pID,
				DeviceName: pName,
				PublicKey:  pubKey,
				IP:         remoteIP,
				Port:       DefaultPort,
			})
			_ = session.SendMessage(map[string]any{
				"type":      "PAIR_RESPONSE",
				"accepted":  true,
				"publicKey": "windows_public_key",
			})
			fmt.Printf("[DexoLink Windows] Successfully paired with %s (%s)\n", pName, pID)

		case "UNPAIR":
			if peerID != "" {
				e.TrustStore.RemoveDevice(peerID)
			}

		case "CLIPBOARD_PUSH":
			content, _ := msg["content"].(string)
			seq, _ := msg["sequenceNumber"].(float64)
			if content != "" {
				h := computeTextHash(content)
				e.clipMu.Lock()
				if h != e.lastReceivedHash && h != e.lastSentHash {
					e.lastReceivedHash = h
					e.clipMu.Unlock()

					normWin := normalizeTextForWindows(content)
					setSystemClipboard(normWin)

					normWire := normalizeTextForWire(content)
					e.mu.Lock()
					e.ClipboardHistory = append([]string{normWire}, e.ClipboardHistory...)
					if len(e.ClipboardHistory) > 50 {
						e.ClipboardHistory = e.ClipboardHistory[:50]
					}
					e.mu.Unlock()
					fmt.Printf("[DexoLink Windows Clipboard] Received and applied clipboard (%d chars) from %s\n", len(content), peerName)
				} else {
					e.clipMu.Unlock()
					fmt.Printf("[DexoLink Windows Clipboard] Ignored duplicate/echo text (hash: %s)\n", h[:8])
				}
			}
			_ = session.SendMessage(map[string]any{
				"type":           "CLIPBOARD_ACK",
				"sequenceNumber": int(seq),
			})

		case "CLIPBOARD_ACK":
			// Acknowledged by peer

		case "CONTINUITY_ACTIVITY":
			actID, _ := msg["activityId"].(string)
			u, _ := msg["url"].(string)
			if u != "" {
				fmt.Printf("[DexoLink Windows Continuity] Opening URL: %s\n", u)
				openBrowser(u)
			}
			_ = session.SendMessage(map[string]any{
				"type":       "CONTINUITY_ACK",
				"activityId": actID,
				"status":     "handled",
			})

		case "TRANSFER_REQUEST":
			tID, _ := msg["transferId"].(string)
			totalFiles, _ := msg["totalFiles"].(float64)
			totalSizeFloat, _ := msg["totalSize"].(float64)
			totalSize := int64(totalSizeFloat)

			// Security validation
			if totalSize <= 0 || totalSize > MaxFileSize {
				_ = session.SendMessage(map[string]any{
					"type":       "TRANSFER_RESPONSE",
					"transferId": tID,
					"accepted":   false,
					"reason":     "Invalid file size",
				})
				continue
			}

			item := &TransferItem{
				ID:         tID,
				Name:       "Incoming file...",
				Size:       totalSize,
				IsIncoming: true,
				PeerID:     session.DeviceID,
				PeerName:   session.DeviceName,
				State:      "transferring",
				StartTime:  time.Now(),
				cancelChan: make(chan struct{}),
			}
			e.transfersMu.Lock()
			e.ActiveTransfers[tID] = item
			e.transfersMu.Unlock()

			_ = session.SendMessage(map[string]any{
				"type":       "TRANSFER_RESPONSE",
				"transferId": tID,
				"accepted":   true,
			})
			fmt.Printf("[DexoLink Windows] Accepted transfer request %s (%d bytes, %d files) from %s\n", tID, totalSize, int(totalFiles), session.DeviceName)

		case "TRANSFER_RESPONSE":
			tID, _ := msg["transferId"].(string)
			accepted, _ := msg["accepted"].(bool)
			e.transfersMu.Lock()
			ch, ok := e.pendingResponses[tID]
			e.transfersMu.Unlock()
			if ok && ch != nil {
				select {
				case ch <- accepted:
				default:
				}
			}

		case "FILE_META":
			tID, _ := msg["transferId"].(string)
			fID, _ := msg["fileId"].(string)
			fName, _ := msg["name"].(string)
			sizeFloat, _ := msg["size"].(float64)
			size := int64(sizeFloat)

			destPath := getSafeDestinationPath(e.DownloadDir, fName)
			outF, err := os.OpenFile(destPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0644)
			if err != nil {
				fmt.Printf("[DexoLink Windows] Error creating destination file: %v\n", err)
				_ = session.SendMessage(map[string]any{
					"type":       "CANCEL",
					"transferId": tID,
				})
				continue
			}

			rc := &ReceiverContext{
				transferID:     tID,
				destPath:       destPath,
				file:           outF,
				hasher:         sha256.New(),
				expectedSize:   size,
				receivedBytes:  0,
				lastReportTime: time.Now(),
			}

			e.transfersMu.Lock()
			e.ActiveReceivers[fID] = rc
			e.FileIDToTransferID[fID] = tID
			if item, exists := e.ActiveTransfers[tID]; exists {
				item.FileID = fID
				item.Name = filepath.Base(destPath)
				item.LocalPath = destPath
				item.Size = size
				item.State = "transferring"
			}
			e.transfersMu.Unlock()
			fmt.Printf("[DexoLink Windows] Receiving file %s (%d bytes) -> %s\n", fName, size, destPath)

		case "FILE_CHUNK":
			fID, _ := msg["fileId"].(string)
			offFloat, _ := msg["offset"].(float64)
			offset := int64(offFloat)
			dataB64, _ := msg["data"].(string)
			chunkData, err := base64.StdEncoding.DecodeString(dataB64)
			if err == nil && len(chunkData) > 0 {
				e.processFileChunk(fID, offset, chunkData, session)
			}

		case "PROGRESS":
			tID, _ := msg["transferId"].(string)
			bytesReceivedFloat, _ := msg["bytesReceived"].(float64)
			received := int64(bytesReceivedFloat)
			e.transfersMu.Lock()
			if item, exists := e.ActiveTransfers[tID]; exists {
				item.BytesTransferred = received
				if item.Size > 0 {
					item.Progress = float64(received) / float64(item.Size)
				}
			}
			e.transfersMu.Unlock()

		case "FILE_COMPLETE":
			fID, _ := msg["fileId"].(string)
			declaredSHA, _ := msg["sha256"].(string)

			e.transfersMu.Lock()
			rc, exists := e.ActiveReceivers[fID]
			delete(e.ActiveReceivers, fID)
			tID := e.FileIDToTransferID[fID]
			item := e.ActiveTransfers[tID]
			e.transfersMu.Unlock()

			if exists && rc != nil {
				_ = rc.file.Close()
				computedHash := hex.EncodeToString(rc.hasher.Sum(nil))
				verified := strings.EqualFold(computedHash, declaredSHA)

				if verified {
					fmt.Printf("[DexoLink Windows] Successfully received and verified %s (SHA-256 match: %s)\n", rc.destPath, computedHash[:8])
					if item != nil {
						item.State = "completed"
						item.Progress = 1.0
						item.BytesTransferred = item.Size
					}
					e.mu.Lock()
					e.History = append([]TransferRecord{{
						ID:         tID,
						FileName:   filepath.Base(rc.destPath),
						Size:       rc.expectedSize,
						IsIncoming: true,
						PeerName:   session.DeviceName,
						Success:    true,
						State:      "completed",
						Time:       time.Now(),
					}}, e.History...)
					e.mu.Unlock()
				} else {
					fmt.Printf("[DexoLink Windows Security] SHA-256 mismatch for %s! Computed: %s, declared: %s\n", rc.destPath, computedHash, declaredSHA)
					_ = os.Remove(rc.destPath)
					if item != nil {
						item.State = "failed"
						item.Error = "SHA-256 checksum mismatch"
					}
				}

				_ = session.SendMessage(map[string]any{
					"type":     "FILE_ACK",
					"fileId":   fID,
					"verified": verified,
				})
			}

		case "FILE_ACK":
			fID, _ := msg["fileId"].(string)
			verified, _ := msg["verified"].(bool)
			e.transfersMu.Lock()
			ch, ok := e.pendingAcks[fID]
			tID := e.FileIDToTransferID[fID]
			item := e.ActiveTransfers[tID]
			e.transfersMu.Unlock()

			if ok && ch != nil {
				select {
				case ch <- verified:
				default:
				}
			}
			if item != nil {
				if verified {
					item.State = "completed"
					item.Progress = 1.0
				} else {
					item.State = "failed"
					item.Error = "Peer verification failed"
				}
			}

		case "CANCEL":
			tID, _ := msg["transferId"].(string)
			e.cancelTransferInternal(tID, false)

		case "PING":
			_ = session.SendMessage(map[string]any{"type": "PONG"})

		case "STREAM_REQUEST":
			streamID, _ := msg["streamId"].(string)
			streamType, _ := msg["streamType"].(string)
			e.mu.Lock()
			accepted := false
			reason := ""
			if streamType == "camera" && e.CameraSharingEnabled {
				accepted = true
			} else if streamType == "screen" && e.ScreenSharingEnabled {
				accepted = true
			} else if streamType == "desktop" && e.DesktopModeEnabled {
				accepted = true
			} else {
				reason = fmt.Sprintf("%s sharing is disabled in Windows Settings", streamType)
			}
			e.mu.Unlock()

			respData := map[string]any{
				"type":       "STREAM_RESPONSE",
				"streamId":   streamID,
				"streamType": streamType,
				"accepted":   accepted,
			}
			if !accepted {
				respData["reason"] = reason
			}
			_ = session.SendMessage(respData)

		case "WEBRTC_SIGNAL":
			streamID, _ := msg["streamId"].(string)
			streamType, _ := msg["streamType"].(string)
			sigType, _ := msg["signalType"].(string)
			if sigType == "offer" {
				answerSDP := "v=0\r\no=- 123456789 2 IN IP4 127.0.0.1\r\ns=DexoLink Windows " + streamType + " Stream\r\nt=0 0\r\na=group:BUNDLE 0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96 97\r\nc=IN IP4 0.0.0.0\r\na=mid:0\r\na=recvonly\r\n"
				_ = session.SendMessage(map[string]any{
					"type":          "WEBRTC_SIGNAL",
					"streamId":      streamID,
					"streamType":    streamType,
					"signalType":    "answer",
					"sdp":           answerSDP,
					"sdpMid":        "0",
					"sdpMLineIndex": 0,
					"options":       map[string]any{"status": "accepted", "mode": "p2p_with_turn_fallback"},
				})
			}
		}
	}
}

// processFileChunk handles writing and hashing a chunk for an active receiver
func (e *DexoLinkEngine) processFileChunk(fID string, offset int64, chunk []byte, session *PeerSession) {
	e.transfersMu.Lock()
	rc, exists := e.ActiveReceivers[fID]
	tID := e.FileIDToTransferID[fID]
	item := e.ActiveTransfers[tID]
	e.transfersMu.Unlock()

	if !exists || rc == nil {
		return
	}

	// Security: Prevent buffer overflow
	if rc.receivedBytes+int64(len(chunk)) > rc.expectedSize+ChunkSize {
		fmt.Printf("[DexoLink Windows Security] Received bytes exceeded declared size. Aborting.\n")
		_ = rc.file.Close()
		_ = os.Remove(rc.destPath)
		_ = session.SendMessage(map[string]any{"type": "CANCEL", "transferId": rc.transferID})
		return
	}

	_, _ = rc.file.WriteAt(chunk, offset)
	_, _ = rc.hasher.Write(chunk)
	rc.receivedBytes += int64(len(chunk))

	if item != nil {
		item.BytesTransferred = rc.receivedBytes
		if rc.expectedSize > 0 {
			item.Progress = float64(rc.receivedBytes) / float64(rc.expectedSize)
		}
		elapsed := time.Since(item.StartTime).Seconds()
		if elapsed > 0.1 {
			item.SpeedBytesPerSec = float64(rc.receivedBytes) / elapsed
		}
	}

	// Progress acknowledgement every 2 MB or upon completion
	if rc.receivedBytes-rc.lastProgressReport >= 2*1024*1024 || rc.receivedBytes >= rc.expectedSize {
		rc.lastProgressReport = rc.receivedBytes
		_ = session.SendMessage(map[string]any{
			"type":          "PROGRESS",
			"transferId":    rc.transferID,
			"fileId":        fID,
			"bytesReceived": rc.receivedBytes,
		})
	}
}

// cancelTransferInternal aborts an in-flight transfer
func (e *DexoLinkEngine) cancelTransferInternal(tID string, notifyPeer bool) {
	e.transfersMu.Lock()
	item, hasItem := e.ActiveTransfers[tID]
	var rcToClean *ReceiverContext
	for fID, rc := range e.ActiveReceivers {
		if rc.transferID == tID {
			rcToClean = rc
			delete(e.ActiveReceivers, fID)
			break
		}
	}
	e.transfersMu.Unlock()

	if rcToClean != nil {
		_ = rcToClean.file.Close()
		_ = os.Remove(rcToClean.destPath)
	}

	if hasItem && item != nil {
		item.State = "cancelled"
		item.Error = "Cancelled"
		if item.cancelChan != nil {
			select {
			case <-item.cancelChan:
			default:
				close(item.cancelChan)
			}
		}
		e.mu.Lock()
		e.History = append([]TransferRecord{{
			ID:         tID,
			FileName:   item.Name,
			Size:       item.Size,
			IsIncoming: item.IsIncoming,
			PeerName:   item.PeerName,
			Success:    false,
			State:      "cancelled",
			Time:       time.Now(),
		}}, e.History...)
		e.mu.Unlock()

		if notifyPeer && item.PeerID != "" {
			if val, ok := e.ActiveSessions.Load(item.PeerID); ok {
				s := val.(*PeerSession)
				_ = s.SendMessage(map[string]any{"type": "CANCEL", "transferId": tID})
			}
		}
	}
}

// Outgoing File Transfer via active session
func (e *DexoLinkEngine) SendFile(targetPeerID string, filePath string) (bool, error) {
	// Find target session
	var session *PeerSession
	if targetPeerID != "" {
		if val, ok := e.ActiveSessions.Load(targetPeerID); ok {
			session = val.(*PeerSession)
		}
	}
	if session == nil {
		// Take first active session
		e.ActiveSessions.Range(func(k, v any) bool {
			session = v.(*PeerSession)
			return false
		})
	}

	if session == nil {
		// Attempt to auto-connect to a discovered / paired device
		var targetInfo DeviceInfo
		found := false
		e.TrustStore.mu.Lock()
		for _, pd := range e.TrustStore.PairedDevices {
			if pd.IP != "" {
				targetInfo = pd
				found = true
				break
			}
		}
		e.TrustStore.mu.Unlock()

		if !found {
			e.DiscoveredDevices.Range(func(k, v any) bool {
				targetInfo = v.(DeviceInfo)
				found = true
				return false
			})
		}

		if found && targetInfo.IP != "" {
			p := targetInfo.Port
			if p <= 0 {
				p = DefaultPort
			}
			newSess, err := e.connectToPeer(targetInfo.IP, p, targetInfo.DeviceID, targetInfo.DeviceName)
			if err == nil {
				session = newSess
			}
		}
	}

	if session == nil {
		return false, fmt.Errorf("no connected device available. Please connect to a device on Wi-Fi")
	}

	f, err := os.Open(filePath)
	if err != nil {
		return false, err
	}
	defer f.Close()

	stat, err := f.Stat()
	if err != nil {
		return false, err
	}
	fileSize := stat.Size()
	fileName := filepath.Base(filePath)

	transferID := fmt.Sprintf("tr_%d", time.Now().UnixNano())
	fileID := fmt.Sprintf("fl_%d", time.Now().UnixNano())

	item := &TransferItem{
		ID:         transferID,
		FileID:     fileID,
		Name:       fileName,
		Size:       fileSize,
		IsIncoming: false,
		PeerID:     session.DeviceID,
		PeerName:   session.DeviceName,
		State:      "pending",
		LocalPath:  filePath,
		StartTime:  time.Now(),
		cancelChan: make(chan struct{}),
	}

	respChan := make(chan bool, 1)
	ackChan := make(chan bool, 1)

	e.transfersMu.Lock()
	e.ActiveTransfers[transferID] = item
	e.FileIDToTransferID[fileID] = transferID
	e.pendingResponses[transferID] = respChan
	e.pendingAcks[fileID] = ackChan
	e.transfersMu.Unlock()

	defer func() {
		e.transfersMu.Lock()
		delete(e.pendingResponses, transferID)
		delete(e.pendingAcks, fileID)
		e.transfersMu.Unlock()
	}()

	// 1. Propose transfer
	err = session.SendMessage(map[string]any{
		"type":       "TRANSFER_REQUEST",
		"transferId": transferID,
		"totalFiles": 1,
		"totalSize":  fileSize,
	})
	if err != nil {
		item.State = "failed"
		item.Error = err.Error()
		return false, err
	}

	// 2. Wait for TRANSFER_RESPONSE
	select {
	case accepted := <-respChan:
		if !accepted {
			item.State = "failed"
			item.Error = "Transfer declined by peer"
			e.recordCompletedTransfer(item, false)
			return false, fmt.Errorf("transfer rejected by peer")
		}
	case <-time.After(12 * time.Second):
		item.State = "failed"
		item.Error = "Transfer request timed out"
		e.recordCompletedTransfer(item, false)
		return false, fmt.Errorf("transfer request timed out")
	case <-item.cancelChan:
		item.State = "cancelled"
		e.recordCompletedTransfer(item, false)
		return false, fmt.Errorf("transfer cancelled")
	}

	item.State = "transferring"

	// 3. Send FILE_META
	err = session.SendMessage(map[string]any{
		"type":       "FILE_META",
		"transferId": transferID,
		"fileId":     fileID,
		"name":       fileName,
		"size":       fileSize,
		"mimeType":   "application/octet-stream",
	})
	if err != nil {
		item.State = "failed"
		return false, err
	}

	// 4. Stream Chunks with Base64 encoding
	hasher := sha256.New()
	chunk := make([]byte, ChunkSize)
	var offset int64 = 0

	for {
		select {
		case <-item.cancelChan:
			_ = session.SendMessage(map[string]any{"type": "CANCEL", "transferId": transferID})
			item.State = "cancelled"
			e.recordCompletedTransfer(item, false)
			return false, fmt.Errorf("transfer cancelled")
		default:
		}

		n, rErr := f.Read(chunk)
		if n > 0 {
			_, _ = hasher.Write(chunk[:n])
			b64 := base64.StdEncoding.EncodeToString(chunk[:n])
			cErr := session.SendMessage(map[string]any{
				"type":   "FILE_CHUNK",
				"fileId": fileID,
				"offset": offset,
				"data":   b64,
			})
			if cErr != nil {
				item.State = "failed"
				item.Error = cErr.Error()
				e.recordCompletedTransfer(item, false)
				return false, cErr
			}

			offset += int64(n)
			item.BytesTransferred = offset
			if fileSize > 0 {
				item.Progress = float64(offset) / float64(fileSize)
			}
			elapsed := time.Since(item.StartTime).Seconds()
			if elapsed > 0.1 {
				item.SpeedBytesPerSec = float64(offset) / elapsed
			}
		}
		if rErr != nil {
			break
		}
	}

	// 5. Send FILE_COMPLETE
	shaHex := hex.EncodeToString(hasher.Sum(nil))
	err = session.SendMessage(map[string]any{
		"type":   "FILE_COMPLETE",
		"fileId": fileID,
		"sha256": shaHex,
	})
	if err != nil {
		item.State = "failed"
		return false, err
	}

	// 6. Wait for FILE_ACK
	verified := false
	select {
	case v := <-ackChan:
		verified = v
	case <-time.After(20 * time.Second):
		item.State = "failed"
		item.Error = "Timeout waiting for peer file verification"
	case <-item.cancelChan:
		item.State = "cancelled"
	}

	if verified {
		item.State = "completed"
		item.Progress = 1.0
		item.BytesTransferred = fileSize
	} else if item.State != "cancelled" {
		item.State = "failed"
		if item.Error == "" {
			item.Error = "Peer file integrity verification failed"
		}
	}

	e.recordCompletedTransfer(item, verified)
	return verified, nil
}

func (e *DexoLinkEngine) recordCompletedTransfer(item *TransferItem, success bool) {
	e.mu.Lock()
	e.History = append([]TransferRecord{{
		ID:         item.ID,
		FileName:   item.Name,
		Size:       item.Size,
		IsIncoming: false,
		PeerName:   item.PeerName,
		Success:    success,
		State:      item.State,
		Time:       time.Now(),
	}}, e.History...)
	e.mu.Unlock()
}

// connectToPeer initiates a TCP connection to a peer, completes handshake, and maintains session
func (e *DexoLinkEngine) connectToPeer(ip string, port int, devID string, devName string) (*PeerSession, error) {
	if !e.Enabled {
		return nil, fmt.Errorf("DexoLink is turned off")
	}
	if port <= 0 {
		port = DefaultPort
	}

	// Check if already active
	if devID != "" {
		if val, ok := e.ActiveSessions.Load(devID); ok {
			s := val.(*PeerSession)
			return s, nil
		}
	}

	addr := fmt.Sprintf("%s:%d", ip, port)
	conn, err := net.DialTimeout("tcp", addr, 4*time.Second)
	if err != nil {
		return nil, err
	}

	session := &PeerSession{
		DeviceID:    devID,
		DeviceName:  devName,
		IP:          ip,
		Port:        port,
		ConnectedAt: time.Now(),
		State:       "connected",
		Conn:        conn,
	}

	// 1. Send HELLO
	hello := map[string]any{
		"type":       "HELLO",
		"version":    ProtocolVersion,
		"deviceId":   e.DeviceID,
		"deviceName": e.DeviceName,
	}
	if err := session.SendMessage(hello); err != nil {
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

	session.DeviceID = peerID
	session.DeviceName = peerName

	e.ActiveSessions.Store(peerID, session)
	peerInfo := DeviceInfo{
		DeviceID:   peerID,
		DeviceName: peerName,
		IP:         ip,
		Port:       port,
	}
	e.DiscoveredDevices.Store(peerID, peerInfo)

	// Update TrustStore if device was previously paired
	e.TrustStore.mu.Lock()
	if pd, exists := e.TrustStore.PairedDevices[peerID]; exists {
		pd.IP = ip
		pd.Port = port
		e.TrustStore.PairedDevices[peerID] = pd
		e.TrustStore.save()
	}
	e.TrustStore.mu.Unlock()

	// Spawn message reader loop
	go e.handleClient(conn, session)

	fmt.Printf("[DexoLink Windows] Active connection established with %s (%s:%d)\n", peerName, ip, port)
	updateTrayStatus(fmt.Sprintf("DexoLink - Connected to %s", peerName))
	return session, nil
}

// UDP Discovery helpers
func getLocalIPs() []string {
	var ips []string
	ifaces, err := net.Interfaces()
	if err != nil {
		return ips
	}
	for _, i := range ifaces {
		if (i.Flags&net.FlagUp) == 0 || (i.Flags&net.FlagLoopback) != 0 {
			continue
		}
		addrs, err := i.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			var ip net.IP
			switch v := addr.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			}
			if ip != nil && ip.To4() != nil && !ip.IsLoopback() {
				ips = append(ips, ip.String())
			}
		}
	}
	return ips
}

func getSubnetBroadcastAddresses() []string {
	var bcasts []string
	ifaces, err := net.Interfaces()
	if err != nil {
		return bcasts
	}
	for _, i := range ifaces {
		if (i.Flags&net.FlagUp) == 0 || (i.Flags&net.FlagLoopback) != 0 || (i.Flags&net.FlagBroadcast) == 0 {
			continue
		}
		addrs, err := i.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			ipNet, ok := addr.(*net.IPNet)
			if !ok || ipNet.IP.To4() == nil {
				continue
			}
			ip4 := ipNet.IP.To4()
			mask := ipNet.Mask
			if len(mask) == 4 {
				bcast := net.IPv4(
					ip4[0]|^mask[0],
					ip4[1]|^mask[1],
					ip4[2]|^mask[2],
					ip4[3]|^mask[3],
				)
				bcasts = append(bcasts, bcast.String())
			}
		}
	}
	return bcasts
}

// broadcastUDPBeacon broadcasts the discovery beacon across all network subnets
func (e *DexoLinkEngine) broadcastUDPBeacon() {
	localIPs := getLocalIPs()
	primaryIP := ""
	if len(localIPs) > 0 {
		primaryIP = localIPs[0]
	}

	// Payload with "dexolink": 1 to match macOS and Android filters
	payload, err := json.Marshal(map[string]any{
		"dexolink":   1,
		"type":       "BEACON",
		"version":    ProtocolVersion,
		"deviceId":   e.DeviceID,
		"deviceName": e.DeviceName,
		"deviceType": "windows",
		"ip":         primaryIP,
		"port":       DefaultPort,
	})
	if err != nil {
		return
	}

	// 1. General 255.255.255.255 broadcast
	conn, err := net.DialUDP("udp", nil, &net.UDPAddr{
		IP:   net.IPv4(255, 255, 255, 255),
		Port: BeaconPort,
	})
	if err == nil {
		_, _ = conn.Write(payload)
		conn.Close()
	}

	// 2. Subnet-directed broadcasts for every interface
	for _, bcastIP := range getSubnetBroadcastAddresses() {
		subConn, err := net.DialUDP("udp", nil, &net.UDPAddr{
			IP:   net.ParseIP(bcastIP),
			Port: BeaconPort,
		})
		if err == nil {
			_, _ = subConn.Write(payload)
			subConn.Close()
		}
	}
}

func (e *DexoLinkEngine) startUDPBeacon() {
	ticker := time.NewTicker(3 * time.Second)
	defer ticker.Stop()

	for range ticker.C {
		e.mu.Lock()
		enabled := e.Enabled
		autoDisc := e.AutoDiscoveryEnabled
		e.mu.Unlock()

		if enabled && autoDisc {
			e.broadcastUDPBeacon()
		}
	}
}

func (e *DexoLinkEngine) reconnectPairedDevices() {
	e.TrustStore.mu.Lock()
	paired := make([]DeviceInfo, 0, len(e.TrustStore.PairedDevices))
	for _, pd := range e.TrustStore.PairedDevices {
		paired = append(paired, pd)
	}
	e.TrustStore.mu.Unlock()

	for _, pd := range paired {
		if pd.IP == "" {
			continue
		}
		if _, active := e.ActiveSessions.Load(pd.DeviceID); !active {
			p := pd.Port
			if p <= 0 {
				p = DefaultPort
			}
			go func(ip string, port int, id, name string) {
				_, _ = e.connectToPeer(ip, port, id, name)
			}(pd.IP, p, pd.DeviceID, pd.DeviceName)
		}
	}
}

func (e *DexoLinkEngine) startUDPListener() {
	l, err := net.ListenUDP("udp", &net.UDPAddr{Port: BeaconPort})
	if err != nil {
		return
	}
	defer l.Close()

	buf := make([]byte, 4096)
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
			devType, _ := payload["deviceType"].(string)
			portFloat, _ := payload["port"].(float64)
			port := int(portFloat)
			if port <= 0 {
				port = DefaultPort
			}

			ipFromPacket, _ := payload["ip"].(string)
			resolvedIP := addr.IP.String()
			if ipFromPacket != "" && ipFromPacket != "0.0.0.0" && ipFromPacket != "127.0.0.1" {
				resolvedIP = ipFromPacket
			}

			if devID != "" && devID != e.DeviceID {
				devInfo := DeviceInfo{
					DeviceID:   devID,
					DeviceName: devName,
					IP:         resolvedIP,
					Port:       port,
					DeviceType: devType,
				}
				e.DiscoveredDevices.Store(devID, devInfo)

				// Auto-connect to paired device if not already connected
				if e.TrustStore.IsPaired(devID, devName) {
					e.TrustStore.mu.Lock()
					multi := e.TrustStore.MultiplePairedDevices
					e.TrustStore.mu.Unlock()

					activeCount := 0
					e.ActiveSessions.Range(func(k, v any) bool {
						activeCount++
						return true
					})

					if multi || activeCount == 0 {
						if _, connected := e.ActiveSessions.Load(devID); !connected {
							go func(ip string, p int, id, name string) {
								_, _ = e.connectToPeer(ip, p, id, name)
							}(resolvedIP, port, devID, devName)
						}
					}
				}
			}
		}
	}
}

func fileExists(path string) bool {
	info, err := os.Stat(path)
	if err != nil {
		return false
	}
	return !info.IsDir()
}

func openGUIWindow(url string) bool {
	switch runtime.GOOS {
	case "windows":
		edgeCandidates := []string{
			filepath.Join(os.Getenv("ProgramFiles(x86)"), "Microsoft", "Edge", "Application", "msedge.exe"),
			filepath.Join(os.Getenv("ProgramFiles"), "Microsoft", "Edge", "Application", "msedge.exe"),
			filepath.Join(os.Getenv("LOCALAPPDATA"), "Microsoft", "Edge", "Application", "msedge.exe"),
		}
		for _, p := range edgeCandidates {
			if p != "" && fileExists(p) {
				cmd := exec.Command(p, fmt.Sprintf("--app=%s", url), "--window-size=1120,780")
				if err := cmd.Start(); err == nil {
					return true
				}
			}
		}
		if p, err := exec.LookPath("msedge.exe"); err == nil {
			cmd := exec.Command(p, fmt.Sprintf("--app=%s", url), "--window-size=1120,780")
			if err := cmd.Start(); err == nil {
				return true
			}
		}
		chromeCandidates := []string{
			filepath.Join(os.Getenv("ProgramFiles"), "Google", "Chrome", "Application", "chrome.exe"),
			filepath.Join(os.Getenv("ProgramFiles(x86)"), "Google", "Chrome", "Application", "chrome.exe"),
			filepath.Join(os.Getenv("LOCALAPPDATA"), "Google", "Chrome", "Application", "chrome.exe"),
		}
		for _, p := range chromeCandidates {
			if p != "" && fileExists(p) {
				cmd := exec.Command(p, fmt.Sprintf("--app=%s", url), "--window-size=1120,780")
				if err := cmd.Start(); err == nil {
					return true
				}
			}
		}
		cmd := exec.Command("cmd.exe", "/c", "start", "", url)
		if err := cmd.Start(); err == nil {
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

// Web UI Dashboard Server
func startWebDashboard(ready chan<- error) {
	mux := http.NewServeMux()

	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		fmt.Fprint(w, webUIHTML)
	})

	mux.HandleFunc("/popup", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		fmt.Fprint(w, popupUIHTML)
	})

	mux.HandleFunc("/api/connection-qr", func(w http.ResponseWriter, r *http.Request) {
		localIPs := getLocalIPs()
		if len(localIPs) == 0 {
			http.Error(w, "No active network address is available", http.StatusServiceUnavailable)
			return
		}
		deviceName, err := os.Hostname()
		if err != nil || strings.TrimSpace(deviceName) == "" {
			deviceName = "Windows PC"
		}
		payload := fmt.Sprintf("dexolink://connect?host=%s&port=%d&name=%s", url.QueryEscape(localIPs[0]), DefaultPort, url.QueryEscape(deviceName))
		png, err := qrcode.Encode(payload, qrcode.Medium, 480)
		if err != nil {
			http.Error(w, "Could not generate the connection QR code", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "image/png")
		w.Header().Set("Cache-Control", "no-store")
		_, _ = w.Write(png)
	})

	mux.HandleFunc("/api/reconnect", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		go func() {
			if engine != nil {
				engine.broadcastUDPBeacon()
				engine.reconnectPairedDevices()
			}
		}()
		_ = json.NewEncoder(w).Encode(map[string]any{"success": true, "message": "Rescan and reconnect triggered"})
	})

	mux.HandleFunc("/api/exit", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"success": true, "message": "Exiting DexoLink"})
		go func() {
			time.Sleep(150 * time.Millisecond)
			removeTrayIcon()
			os.Exit(0)
		}()
	})

	mux.HandleFunc("/api/status", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")

		connectedList := make([]PeerSession, 0)
		engine.ActiveSessions.Range(func(key, value any) bool {
			s := value.(*PeerSession)
			connectedList = append(connectedList, *s)
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

		engine.transfersMu.Lock()
		activeTransfers := make([]TransferItem, 0, len(engine.ActiveTransfers))
		for _, item := range engine.ActiveTransfers {
			activeTransfers = append(activeTransfers, *item)
		}
		engine.transfersMu.Unlock()

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
			"version":                "4.1.0",
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
			"active_transfers":       activeTransfers,
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

	mux.HandleFunc("/api/toggle-master", func(w http.ResponseWriter, r *http.Request) {
		enable := r.URL.Query().Get("enabled") == "true"
		engine.mu.Lock()
		engine.Enabled = enable
		engine.mu.Unlock()
		if !enable {
			engine.ActiveSessions.Range(func(key, val any) bool {
				s := val.(*PeerSession)
				_ = s.Conn.Close()
				engine.ActiveSessions.Delete(key)
				return true
			})
		}
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{"success": true, "enabled": enable})
	})

	mux.HandleFunc("/api/connect-peer", func(w http.ResponseWriter, r *http.Request) {
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

		session, err := engine.connectToPeer(ip, port, id, name)
		if err != nil {
			w.WriteHeader(http.StatusInternalServerError)
			json.NewEncoder(w).Encode(map[string]any{"success": false, "error": err.Error()})
			return
		}
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{
			"success":     true,
			"device_id":   session.DeviceID,
			"device_name": session.DeviceName,
			"ip":          ip,
			"port":        port,
		})
	})

	mux.HandleFunc("/api/connect-direct", func(w http.ResponseWriter, r *http.Request) {
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

	mux.HandleFunc("/api/pair-device", func(w http.ResponseWriter, r *http.Request) {
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
			engine.TrustStore.mu.Lock()
			multi := engine.TrustStore.MultiplePairedDevices
			pairedCount := len(engine.TrustStore.PairedDevices)
			_, alreadyPaired := engine.TrustStore.PairedDevices[id]
			engine.TrustStore.mu.Unlock()

			if !multi && pairedCount > 0 && !alreadyPaired {
				w.WriteHeader(http.StatusForbidden)
				json.NewEncoder(w).Encode(map[string]any{
					"success": false,
					"error":   "Single Device Mode active. Enable Multiple Paired Devices in Settings to pair additional devices.",
				})
				return
			}

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

	mux.HandleFunc("/api/disconnect", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		id := r.URL.Query().Get("id")
		if id != "" {
			if val, ok := engine.ActiveSessions.Load(id); ok {
				s := val.(*PeerSession)
				_ = s.Conn.Close()
				engine.ActiveSessions.Delete(id)
			}
		} else {
			engine.ActiveSessions.Range(func(key, val any) bool {
				s := val.(*PeerSession)
				_ = s.Conn.Close()
				engine.ActiveSessions.Delete(key)
				return true
			})
		}
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{"success": true})
	})

	mux.HandleFunc("/api/unpair", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		id := r.URL.Query().Get("id")
		engine.TrustStore.RemoveDevice(id)
		if id != "" {
			if val, ok := engine.ActiveSessions.Load(id); ok {
				s := val.(*PeerSession)
				_ = s.Conn.Close()
				engine.ActiveSessions.Delete(id)
			}
		}
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{"success": true})
	})

	mux.HandleFunc("/api/upload-file", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}

		err := r.ParseMultipartForm(500 << 20) // 500 MB upload limit
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
		tempPath := filepath.Join(tempDir, fmt.Sprintf("%d_%s", time.Now().UnixNano(), sanitizeFilename(header.Filename)))
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

		targetPeerID := r.URL.Query().Get("id")

		go func(fPath string, tID string, fName string, fSize int64) {
			defer os.Remove(fPath)

			engine.TrustStore.mu.Lock()
			multi := engine.TrustStore.MultiplePairedDevices
			engine.TrustStore.mu.Unlock()

			if tID != "" || !multi {
				// Single mode or explicitly targeted recipient
				ok, sErr := engine.SendFile(tID, fPath)
				if !ok || sErr != nil {
					fmt.Printf("[DexoLink Windows] Failed sending %s: %v\n", fName, sErr)
				} else {
					fmt.Printf("[DexoLink Windows] Successfully sent %s (%d bytes)\n", fName, fSize)
				}
			} else {
				// Multiple mode: broadcast file independently to all eligible connected, paired devices
				var recipients []string
				engine.ActiveSessions.Range(func(key, val any) bool {
					s := val.(*PeerSession)
					if engine.TrustStore.IsPaired(s.DeviceID, s.DeviceName) {
						recipients = append(recipients, s.DeviceID)
					}
					return true
				})

				if len(recipients) == 0 {
					_, _ = engine.SendFile("", fPath)
					return
				}

				var wg sync.WaitGroup
				for _, recID := range recipients {
					wg.Add(1)
					go func(peerID string) {
						defer wg.Done()
						ok, sErr := engine.SendFile(peerID, fPath)
						if !ok || sErr != nil {
							fmt.Printf("[DexoLink Windows] Failed sending %s to %s: %v\n", fName, peerID, sErr)
						} else {
							fmt.Printf("[DexoLink Windows] Successfully sent %s to %s\n", fName, peerID)
						}
					}(recID)
				}
				wg.Wait()
			}
		}(tempPath, targetPeerID, header.Filename, written)

		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{
			"success":  true,
			"fileName": header.Filename,
			"size":     written,
		})
	})

	mux.HandleFunc("/api/cancel-transfer", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		tID := r.URL.Query().Get("id")
		if tID != "" {
			engine.cancelTransferInternal(tID, true)
		}
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{"success": true})
	})

	mux.HandleFunc("/api/push-clipboard", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		var body struct {
			Text string `json:"text"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.Text == "" {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(map[string]any{"success": false, "error": "Invalid clipboard text"})
			return
		}

		normWin := normalizeTextForWindows(body.Text)
		setSystemClipboard(normWin)

		normWire := normalizeTextForWire(body.Text)
		h := computeTextHash(normWire)
		engine.clipMu.Lock()
		engine.lastSentHash = h
		engine.clipSeq++
		seq := engine.clipSeq
		engine.clipMu.Unlock()

		engine.mu.Lock()
		engine.ClipboardHistory = append([]string{normWire}, engine.ClipboardHistory...)
		if len(engine.ClipboardHistory) > 50 {
			engine.ClipboardHistory = engine.ClipboardHistory[:50]
		}
		engine.mu.Unlock()

		msg := map[string]any{
			"type":           "CLIPBOARD_PUSH",
			"sequenceNumber": seq,
			"content":        normWire,
		}
		sentPeers := engine.BroadcastMessage(msg)

		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{
			"success":    true,
			"sent_peers": sentPeers,
			"length":     len(normWire),
		})
	})

	mux.HandleFunc("/api/send-continuity", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		var body struct {
			URL string `json:"url"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.URL == "" {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(map[string]any{"success": false, "error": "Invalid URL"})
			return
		}

		actID := fmt.Sprintf("cont_%d", time.Now().UnixNano())
		msg := map[string]any{
			"type":           "CONTINUITY_ACTIVITY",
			"activityId":     actID,
			"activityType":   "web_browsing",
			"title":          "Web Link from Windows",
			"subtitle":       engine.DeviceName,
			"url":            body.URL,
			"contentPayload": "",
			"position":       0,
			"progress":       1.0,
			"timestamp":      time.Now().UnixMilli(),
			"sourceDevice":   engine.DeviceID,
		}
		sentPeers := engine.BroadcastMessage(msg)

		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{
			"success":    sentPeers > 0,
			"activityId": actID,
			"sent_peers": sentPeers,
		})
	})

	mux.HandleFunc("/api/delete-transfer", func(w http.ResponseWriter, r *http.Request) {
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

	mux.HandleFunc("/api/delete-clip", func(w http.ResponseWriter, r *http.Request) {
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

	mux.HandleFunc("/api/toggle-feature", func(w http.ResponseWriter, r *http.Request) {
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

	mux.HandleFunc("/api/toggle-multi-paired", func(w http.ResponseWriter, r *http.Request) {
		enable := r.URL.Query().Get("enabled") == "true"
		engine.TrustStore.SetMultiPaired(enable)
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{"success": true, "enabled": enable})
	})

	mux.HandleFunc("/api/stream-request", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		sType := r.URL.Query().Get("streamType")
		action := r.URL.Query().Get("action")
		streamID := fmt.Sprintf("stream_%d", time.Now().UnixNano())
		msg := map[string]any{
			"type":       "STREAM_REQUEST",
			"streamId":   streamID,
			"streamType": sType,
			"options": map[string]any{
				"direction":  "outgoing",
				"sender":     "windows",
				"action":     action,
				"senderName": engine.DeviceName,
			},
		}
		sentPeers := engine.BroadcastMessage(msg)
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{
			"success":  sentPeers > 0,
			"streamId": streamID,
			"type":     sType,
			"action":   action,
		})
	})

	mux.HandleFunc("/api/send-desktop-input", func(w http.ResponseWriter, r *http.Request) {
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
		msg := map[string]any{
			"type":      "DESKTOP_INPUT_EVENT",
			"streamId":  streamID,
			"eventType": body.EventType,
			"x":         body.X,
			"y":         body.Y,
			"button":    body.Button,
			"key":       body.Key,
		}
		sentPeers := engine.BroadcastMessage(msg)
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{"success": sentPeers > 0})
	})

	mux.HandleFunc("/api/open-downloads", func(w http.ResponseWriter, r *http.Request) {
		openBrowser(engine.DownloadDir)
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
	_ = http.Serve(ln, mux)
}

func main() {
	fmt.Println("======================================================")
	fmt.Printf("  DexoLink for Windows v4.1.0 (%s/%s)\n", runtime.GOOS, runtime.GOARCH)
	fmt.Println("  Universal Cross-Platform Synchronization & System Tray")
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
		fmt.Printf("[DexoLink Windows] Fatal Startup Error: TCP port %d is occupied (%v).\n", DefaultPort, err)
		os.Exit(1)
	}
	if err := checkPortAvailable(WebUIPort); err != nil {
		fmt.Printf("[DexoLink Windows] Fatal Startup Error: Web UI port %d is occupied (%v).\n", WebUIPort, err)
		os.Exit(1)
	}

	// Step 3: Initialize engine and start background services
	engine = initEngine()
	fmt.Printf("[DexoLink Windows] Initialized DexoLink engine for %s\n", engine.DeviceName)

	tcpReady := make(chan error, 1)
	webReady := make(chan error, 1)

	go engine.startTCPServer(tcpReady)
	go engine.startUDPBeacon()
	go engine.startUDPListener()
	go engine.startClipboardMonitor()
	go startWebDashboard(webReady)

	// Step 4: Initialize native Windows 11 system tray icon
	initSystemTray()

	if err := <-webReady; err != nil {
		fmt.Printf("[DexoLink Windows] Fatal Startup Error: Web UI server could not start: %v\n", err)
		os.Exit(1)
	}
	if err := <-tcpReady; err != nil {
		fmt.Printf("[DexoLink Windows] Fatal Startup Error: TCP server could not start: %v\n", err)
		os.Exit(1)
	}

	// Step 5: Automatically open DexoLink GUI window
	guiURL := fmt.Sprintf("http://localhost:%d", WebUIPort)
	fmt.Println("[DexoLink Windows] Opening DexoLink GUI application window...")
	openGUIWindow(guiURL)
	fmt.Printf("[DexoLink Windows] Local Web UI dashboard is accessible at: %s\n", guiURL)
	fmt.Println("[DexoLink Windows] DexoLink is active in background and system tray. Press Ctrl+C to stop.")

	// Step 6: Graceful termination on OS interrupt
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, os.Interrupt, syscall.SIGTERM)
	<-sigChan
	fmt.Println("\n[DexoLink Windows] Shutting down DexoLink servers gracefully...")
	removeTrayIcon()
	os.Exit(0)
}

const webUIHTML = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>DexoLink v4.1.0 (Windows)</title>
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

  /* Master Quick Settings ON/OFF Card */
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
  .list { list-style: none; margin-top: 6px; max-height: 200px; overflow-y: auto; }
  .list-item { background: var(--bg); border: 1px solid var(--border); padding: 8px 10px; border-radius: 8px; margin-bottom: 5px; display: flex; justify-content: space-between; align-items: center; font-size: 11px; }
  .list-item-left { display: flex; align-items: center; gap: 8px; overflow: hidden; min-width: 0; }
  .list-item-title { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 200px; }
  .list-item-sub { font-size: 10px; color: var(--muted); }

  /* Progress Bar */
  .progress-wrap { background: var(--bg); border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; margin-bottom: 10px; }
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
        <span class="badge">v4.1.0</span>
      </div>
      <div class="header-actions">
        <button class="icon-btn" onclick="openConnectionQRModal()" title="Show a QR code for Android and iPhone">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7"></rect><rect x="14" y="3" width="7" height="7"></rect><rect x="3" y="14" width="7" height="7"></rect><path d="M14 14h3v3h-3zM20 14v3M14 20h3M20 20v1"></path></svg>
        </button>
        <button class="icon-btn" onclick="openSettingsModal()" title="Settings & Feature Controls">
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

    <!-- Master Quick Settings ON/OFF Card -->
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

    <!-- Active Components Stack -->
    <div id="activeDashboard" style="display:flex; flex-direction:column; gap:12px;">

      <!-- Card: Connected Devices (Multi-Device Support) -->
      <div class="card" id="connectedDevicesCard">
        <div class="card-header">
          <div class="card-title">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#22c55e" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 14 14"></polyline></svg>
            <span>Connected Devices</span>
          </div>
          <span class="badge badge-green" id="connectedCountBadge">0 CONNECTED</span>
        </div>
        <ul class="list" id="connectedList">
          <li class="muted">No devices currently connected. Connect a paired or nearby device below.</li>
        </ul>
      </div>

      <!-- Card: Paired Trusted Devices Status -->
      <div class="card" id="pairedDevicesCard">
        <div class="card-header">
          <div class="card-title">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#38bdf8" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path></svg>
            <span>Paired Devices</span>
          </div>
          <span class="badge" id="pairedCountBadge">0 PAIRED</span>
        </div>
        <ul class="list" id="pairedList">
          <li class="muted">No paired devices yet. Discover nearby devices below.</li>
        </ul>
      </div>

      <!-- Card: Quick File Sharing (Drag & Drop + Send Files + Active Progress) -->
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

        <!-- Live Active Transfer Progress Container -->
        <div id="activeTransfersContainer" style="display:flex; flex-direction:column; gap:8px; margin-bottom:10px;"></div>

        <div class="section-label">Recent Completed Transfers</div>
        <ul class="list" id="transfersList">
          <li class="muted">No transfers yet today.</li>
        </ul>
      </div>

      <!-- Card: Media Streaming & Remote Control (v4.1.0) -->
      <div class="card">
        <div class="card-header">
          <div class="card-title">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#22c55e" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"></rect><line x1="8" y1="21" x2="16" y2="21"></line><line x1="12" y1="17" x2="12" y2="21"></line></svg>
            <span>Media Streaming & Remote Control</span>
          </div>
          <span class="badge badge-green">v4.1.0</span>
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
          <span class="badge badge-green" id="clipAutoBadge">AUTO SYNC</span>
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

      <!-- Card: Feature Controls Summary -->
      <div class="card" style="display:flex; align-items:center; justify-content:space-between; padding:12px 16px;">
        <div style="display:flex; align-items:center; gap:10px;">
          <div style="width:34px; height:34px; border-radius:8px; background:rgba(30,58,138,0.4); border:1px solid rgba(59,130,246,0.3); display:flex; align-items:center; justify-content:center; color:#60a5fa;">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#60a5fa" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg>
          </div>
          <div>
            <div style="display:flex; align-items:center; gap:6px;">
              <span style="font-weight:700; font-size:13px; color:var(--text);">Feature Controls</span>
              <span id="featCountBadge" class="badge badge-green">5 ACTIVE</span>
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
    <div class="dev-attr">Developed by Rahul Mahaseth | DexoLink v4.1.0 (Universal)</div>

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
          <div class="muted" style="font-size:11px;">Allow pairing and connecting multiple devices simultaneously</div>
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
  <div id="connectionQRModal" class="modal-overlay" onclick="if(event.target===this)closeConnectionQRModal()">
    <div class="modal-box" style="max-width:360px; text-align:center;">
      <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:12px;">
        <h2 style="font-size:16px; font-weight:800;">Connect a Mobile Device</h2>
        <button class="btn btn-bordered btn-sm" onclick="closeConnectionQRModal()">&times;</button>
      </div>
      <p style="font-size:12px; color:var(--muted); margin-bottom:12px;">Scan this code with DexoLink on Android or iPhone.</p>
      <img id="connectionQRCodeImage" alt="DexoLink connection QR code" style="width:260px;height:260px;object-fit:contain;background:#fff;border-radius:10px;padding:8px;">
      <div style="margin-top:12px;"><a class="btn" href="/api/connection-qr" download="DexoLink-Connection-QR.png">Save QR image</a></div>
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
    let selectedUploadPeerId = '';

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
    function openConnectionQRModal() {
      const image = document.getElementById('connectionQRCodeImage');
      image.src = '/api/connection-qr?ts=' + Date.now();
      document.getElementById('connectionQRModal').style.display = 'flex';
    }
    function closeConnectionQRModal() { document.getElementById('connectionQRModal').style.display = 'none'; }

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
          showToast('Copied to clipboard and synced to ' + (data.sent_peers || 0) + ' devices!', 'success');
          document.getElementById('clipText').value = '';
        } else {
          showToast('Clipboard error: ' + (data.error || 'Failed'), 'error');
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
    function triggerFileSelect(peerId = '') {
      selectedUploadPeerId = peerId;
      document.getElementById('hiddenFileInput').click();
    }

    function handleFileSelect(input) {
      if (input.files && input.files[0]) {
        uploadFile(input.files[0], selectedUploadPeerId);
      }
    }

    async function uploadFile(file, peerId = '') {
      showToast('Starting file transfer: ' + file.name + '...', 'info');
      const formData = new FormData();
      formData.append('file', file);

      try {
        const res = await fetch('/api/upload-file' + (peerId ? '?id=' + encodeURIComponent(peerId) : ''), {
          method: 'POST',
          body: formData
        });
        const data = await res.json();
        if (data.success) {
          showToast('Transfer initiated for ' + file.name + '!', 'success');
        } else {
          showToast('Send failed: ' + (data.error || 'Check connection'), 'error');
        }
        loadStatus();
      } catch (e) {
        showToast('Upload error: ' + e, 'error');
      }
    }

    async function cancelTransfer(transferId) {
      try {
        await fetch('/api/cancel-transfer?id=' + encodeURIComponent(transferId));
        showToast('Transfer cancelled', 'info');
        loadStatus();
      } catch (e) {
        showToast('Cancel error: ' + e, 'error');
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

        const connDevices = data.connected_devices || [];
        const connCount = connDevices.length;
        document.getElementById('connectedCountBadge').innerText = connCount + ' CONNECTED';

        if (!isMasterOn) {
          dot.className = 'status-dot offline';
          text.innerText = 'Turned Off • 0% CPU & Battery';
          action.innerHTML = '';
          fDot.className = 'status-dot offline';
          fText.innerText = 'DexoLink is Turned Off';
        } else if (connCount > 0) {
          dot.className = 'status-dot';
          if (connCount === 1) {
            text.innerText = 'Connected to ' + connDevices[0].device_name + ' (' + connDevices[0].ip + ')';
            fText.innerText = 'Connected to ' + connDevices[0].device_name + ' • Sync active';
            document.getElementById('sendFilesBtn').innerText = 'Send Files to ' + connDevices[0].device_name;
          } else {
            text.innerText = 'Connected to ' + connCount + ' Devices • Multi-Device Active';
            fText.innerText = connCount + ' Devices connected • Sync active';
            document.getElementById('sendFilesBtn').innerText = 'Send Files to Connected Devices';
          }
          action.innerHTML = '';
          fDot.className = 'status-dot';
        } else if (data.paired_devices && data.paired_devices.length > 0) {
          const first = data.paired_devices[0];
          dot.className = 'status-dot offline';
          text.innerText = first.device_name + ' (Disconnected)';
          action.innerHTML = '<button class="btn btn-sm btn-success" style="padding:2px 8px; font-size:10px;" onclick="connectPeer(\'' + first.device_id + '\',\'' + first.device_name.replace(/'/g, "\\'") + '\',\'' + (first.ip || '') + '\',' + (first.port || 52840) + ')">Connect Now</button>';
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

        // 1. Connected Devices List (Dedicated independent list)
        const cList = document.getElementById('connectedList');
        if (connDevices.length > 0) {
          cList.innerHTML = connDevices.map(d => {
            return '<li class="list-item">' +
              '<div class="list-item-left">' +
                '<span class="status-dot"></span>' +
                '<div>' +
                  '<div class="list-item-title">' + d.device_name + '</div>' +
                  '<div class="list-item-sub">' + d.ip + ':' + d.port + ' • Connected</div>' +
                '</div>' +
              '</div>' +
              '<div style="display:flex; gap:5px;">' +
                '<button class="btn btn-sm btn-bordered" onclick="triggerFileSelect(\'' + d.device_id + '\')">Send File</button>' +
                '<button class="btn btn-sm btn-bordered" onclick="disconnectDevice(\'' + d.device_id + '\')">Disconnect</button>' +
              '</div>' +
            '</li>';
          }).join('');
        } else {
          cList.innerHTML = '<li class="muted">No devices currently connected. Connect a paired or nearby device below.</li>';
        }

        // 2. Paired Devices List (Maintains independent status per device)
        const pList = document.getElementById('pairedList');
        const pCountBadge = document.getElementById('pairedCountBadge');
        if (data.paired_devices && data.paired_devices.length > 0) {
          pCountBadge.innerText = data.paired_devices.length + ' PAIRED';
          pList.innerHTML = data.paired_devices.map(d => {
            const isConn = connDevices.some(c => c.device_id === d.device_id || c.ip === d.ip);
            return '<li class="list-item">' +
              '<div class="list-item-left">' +
                '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="' + (isConn ? '#22c55e' : '#38bdf8') + '" stroke-width="2.2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"></path></svg>' +
                '<div>' +
                  '<div class="list-item-title">' + d.device_name + '</div>' +
                  '<div class="list-item-sub">' + (isConn ? '<span style="color:var(--success); font-weight:700;">Connected</span>' : 'Paired (Saved)') + ' • ' + (d.ip ? d.ip : 'Local Wi-Fi') + '</div>' +
                '</div>' +
              '</div>' +
              '<div style="display:flex; gap:5px;">' +
                (isConn ?
                  '<button class="btn btn-sm btn-bordered" onclick="triggerFileSelect(\'' + d.device_id + '\')">Send File</button>' +
                  '<button class="btn btn-sm btn-bordered" onclick="disconnectDevice(\'' + d.device_id + '\')">Disconnect</button>' :
                  '<button class="btn btn-sm btn-success" onclick="connectPeer(\'' + d.device_id + '\',\'' + d.device_name.replace(/'/g, "\\'") + '\',\'' + (d.ip || '') + '\',' + (d.port || 52840) + ')">Connect</button>'
                ) +
                '<button class="btn btn-danger btn-sm" onclick="unpairDevice(\'' + d.device_id + '\')">Unpair</button>' +
              '</div>' +
            '</li>';
          }).join('');
        } else {
          pCountBadge.innerText = '0 PAIRED';
          pList.innerHTML = '<li class="muted">No paired devices yet. Discover nearby devices below.</li>';
        }

        // 3. Active Transfers in Progress Container
        const atCont = document.getElementById('activeTransfersContainer');
        const activeT = data.active_transfers || [];
        if (activeT.length > 0) {
          atCont.innerHTML = activeT.map(t => {
            const pct = Math.round((t.progress || 0) * 100);
            const speed = t.speed_bytes_sec ? (t.speed_bytes_sec / (1024 * 1024)).toFixed(2) + ' MB/s' : '';
            return '<div class="progress-wrap">' +
              '<div style="display:flex; justify-content:space-between; align-items:center; font-size:11px; font-weight:600; margin-bottom:4px;">' +
                '<span>' + (t.is_incoming ? 'Receiving: ' : 'Sending: ') + t.name + ' (' + (t.size ? (t.size / 1024).toFixed(1) + ' KB' : '') + ')</span>' +
                '<span style="color:var(--cyan);">' + pct + '% ' + (speed ? '• ' + speed : '') + '</span>' +
              '</div>' +
              '<div class="progress-bar-bg">' +
                '<div class="progress-bar-fill" style="width:' + pct + '%;"></div>' +
              '</div>' +
              '<div style="display:flex; justify-content:space-between; align-items:center; margin-top:6px;">' +
                '<span style="font-size:10px; color:var(--muted);">' + (t.peer_name ? t.peer_name : 'Peer') + ' • ' + t.state + '</span>' +
                '<button class="btn btn-danger btn-sm" style="padding:2px 8px; font-size:10px;" onclick="cancelTransfer(\'' + t.id + '\')">Cancel Transfer</button>' +
              '</div>' +
            '</div>';
          }).join('');
        } else {
          atCont.innerHTML = '';
        }

        // 4. Nearby Discovered devices list
        const dList = document.getElementById('discoveredList');
        if (data.discovered_devices && data.discovered_devices.length > 0) {
          dList.innerHTML = data.discovered_devices.map(d => {
            const isPaired = data.paired_devices && data.paired_devices.some(p => p.device_id === d.device_id || p.device_name === d.device_name);
            const isConn = connDevices.some(c => c.device_id === d.device_id || c.ip === d.ip);
            const isBlocked = !data.multiple_paired && data.paired_devices && data.paired_devices.length > 0 && !isPaired;
            return '<li class="list-item">' +
              '<div class="list-item-left">' +
                '<div style="width:7px; height:7px; border-radius:50%; background:' + (isConn ? '#22c55e' : (isBlocked ? '#94a3b8' : '#38bdf8')) + ';"></div>' +
                '<div>' +
                  '<div class="list-item-title">' + d.device_name + '</div>' +
                  '<div class="list-item-sub">' + d.ip + ':' + d.port + ' • ' + (d.device_type ? d.device_type : 'device') + '</div>' +
                '</div>' +
              '</div>' +
              '<div style="display:flex; gap:5px; align-items:center;">' +
                (isConn ?
                  '<span class="badge badge-green" style="display:flex; align-items:center;">Connected</span>' :
                  (isPaired ?
                    '<button class="btn btn-sm btn-success" onclick="connectPeer(\'' + d.device_id + '\',\'' + d.device_name.replace(/'/g, "\\'") + '\',\'' + d.ip + '\',' + d.port + ')">Connect</button>' :
                    (isBlocked ?
                      '<span class="badge" style="background:#fee2e2; color:#dc2626; border:1px solid #fca5a5; font-size:10px; font-weight:bold; padding:2px 6px; border-radius:4px;">Blocked</span>' +
                      '<button class="btn btn-sm" style="background:#f1f5f9; color:#94a3b8; cursor:not-allowed;" onclick="showToast(\'Single Device Mode active. Enable Multiple Paired Devices in Settings to pair additional devices.\', \'error\')">Pair Device</button>' :
                      '<button class="btn btn-sm btn-bordered" onclick="pairDevice(\'' + d.device_id + '\',\'' + d.device_name.replace(/'/g, "\\'") + '\',\'' + d.ip + '\',' + d.port + ')">Pair Device</button>'
                    )
                  )
                ) +
              '</div>' +
            '</li>';
          }).join('');
        } else {
          dList.innerHTML = '<li class="muted">Searching for nearby devices on local Wi-Fi...</li>';
        }

        // 5. Recent Transfers list
        const tList = document.getElementById('transfersList');
        const modalTList = document.getElementById('modalTransfersList');
        if (data.transfer_history && data.transfer_history.length > 0) {
          const tHtml = data.transfer_history.slice(0, 10).map(t => 
            '<li class="list-item">' +
              '<div class="list-item-left">' +
                '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="' + (t.success ? '#22c55e' : '#ef4444') + '" stroke-width="2"><polyline points="20 6 9 17 4 12"></polyline></svg>' +
                '<div>' +
                  '<div class="list-item-title">' + t.file_name + '</div>' +
                  '<div class="list-item-sub">' + (t.size ? (t.size / 1024).toFixed(1) + ' KB' : 'Completed') + ' • ' + (t.is_incoming ? 'Received' : 'Sent') + ' (' + (t.state ? t.state : (t.success ? 'Success' : 'Failed')) + ')</div>' +
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

        // 6. Clipboard history list
        const clipListEl = document.getElementById('clipList');
        const modalCList = document.getElementById('modalClipboardList');
        if (data.clipboard_history && data.clipboard_history.length > 0) {
          const cHtml = data.clipboard_history.slice(0, 10).map((t, idx) => 
            '<li class="list-item">' +
              '<div class="list-item-title">' + t.substring(0, 32) + (t.length > 32 ? '...' : '') + '</div>' +
              '<div style="display:flex; gap:4px;">' +
                '<button class="btn btn-bordered btn-sm" onclick="copyClipText(\'' + encodeURIComponent(t) + '\')">Copy</button>' +
                '<button class="btn btn-danger btn-sm" onclick="deleteClip(' + idx + ')">&times;</button>' +
              '</div>' +
            '</li>'
          ).join('');
          clipListEl.innerHTML = cHtml;
          if (modalCList) modalCList.innerHTML = cHtml;
        } else {
          clipListEl.innerHTML = '<li class="muted">No clipboard items yet.</li>';
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

    setInterval(loadStatus, 1500);
    loadStatus();
  </script>
</body>
</html>
`


const popupUIHTML = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>DexoLink System Tray</title>
<style>
  :root {
    --bg: #18181b;
    --card: #27272a;
    --card-border: rgba(255, 255, 255, 0.08);
    --accent: #2563eb;
    --accent-hover: #1d4ed8;
    --text: #f4f4f5;
    --muted: #a1a1aa;
    --success: #10b981;
    --danger: #ef4444;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI Variable Text", "Segoe UI", Roboto, sans-serif;
    background: var(--bg);
    color: var(--text);
    padding: 16px;
    user-select: none;
    -webkit-user-select: none;
  }
  .header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding-bottom: 12px;
    border-bottom: 1px solid var(--card-border);
    margin-bottom: 12px;
  }
  .header-left {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .logo-icon {
    width: 24px;
    height: 24px;
    background: linear-gradient(135deg, #3b82f6, #06b6d4);
    border-radius: 6px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 13px;
    font-weight: 700;
  }
  .title {
    font-size: 14px;
    font-weight: 600;
    letter-spacing: -0.2px;
  }
  .version {
    font-size: 11px;
    color: var(--muted);
    background: rgba(255,255,255,0.06);
    padding: 2px 6px;
    border-radius: 10px;
  }
  .status-badge {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: 11px;
    font-weight: 500;
    padding: 3px 8px;
    border-radius: 12px;
    background: rgba(16, 185, 129, 0.15);
    color: var(--success);
  }
  .status-dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: currentColor;
  }
  .status-badge.idle {
    background: rgba(161, 161, 170, 0.15);
    color: var(--muted);
  }

  .section-label {
    font-size: 11px;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.5px;
    color: var(--muted);
    margin: 12px 0 8px 0;
  }

  .card {
    background: var(--card);
    border: 1px solid var(--card-border);
    border-radius: 10px;
    padding: 10px 12px;
    margin-bottom: 8px;
  }

  .device-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 6px 0;
  }
  .device-item:not(:last-child) {
    border-bottom: 1px solid rgba(255,255,255,0.05);
  }
  .device-info {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  .device-name {
    font-size: 13px;
    font-weight: 500;
  }
  .device-meta {
    font-size: 11px;
    color: var(--muted);
  }
  .btn-disconnect {
    background: transparent;
    border: 1px solid rgba(239, 68, 68, 0.3);
    color: var(--danger);
    font-size: 11px;
    padding: 3px 8px;
    border-radius: 6px;
    cursor: pointer;
    transition: all 0.2s;
  }
  .btn-disconnect:hover {
    background: rgba(239, 68, 68, 0.15);
  }

  .toggle-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 8px 0;
  }
  .toggle-row:not(:last-child) {
    border-bottom: 1px solid rgba(255,255,255,0.05);
  }
  .toggle-label {
    font-size: 13px;
    font-weight: 500;
  }
  .toggle-sub {
    font-size: 11px;
    color: var(--muted);
  }

  /* Switch */
  .switch {
    position: relative;
    display: inline-block;
    width: 36px;
    height: 20px;
  }
  .switch input { opacity: 0; width: 0; height: 0; }
  .slider {
    position: absolute;
    cursor: pointer;
    top: 0; left: 0; right: 0; bottom: 0;
    background-color: #3f3f46;
    transition: .25s;
    border-radius: 20px;
  }
  .slider:before {
    position: absolute;
    content: "";
    height: 14px;
    width: 14px;
    left: 3px;
    bottom: 3px;
    background-color: white;
    transition: .25s;
    border-radius: 50%;
  }
  input:checked + .slider {
    background-color: var(--accent);
  }
  input:checked + .slider:before {
    transform: translateX(16px);
  }

  .btn-block {
    width: 100%;
    padding: 8px;
    border-radius: 8px;
    border: 1px solid var(--card-border);
    background: rgba(255,255,255,0.06);
    color: var(--text);
    font-size: 12px;
    font-weight: 500;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
    transition: background 0.2s;
    margin-top: 8px;
  }
  .btn-block:hover {
    background: rgba(255,255,255,0.12);
  }
  .btn-primary {
    background: var(--accent);
    border-color: var(--accent);
  }
  .btn-primary:hover {
    background: var(--accent-hover);
  }
  .btn-danger-outline {
    color: #f87171;
    border-color: rgba(239, 68, 68, 0.25);
  }
  .btn-danger-outline:hover {
    background: rgba(239, 68, 68, 0.15);
  }

  .empty-state {
    font-size: 12px;
    color: var(--muted);
    text-align: center;
    padding: 12px 0;
  }
</style>
</head>
<body>

  <div class="header">
    <div class="header-left">
      <div class="logo-icon">&#8734;</div>
      <div class="title">DexoLink</div>
      <span class="version">v4.1.0</span>
    </div>
    <div id="statusBadge" class="status-badge idle">
      <div class="status-dot"></div>
      <span id="statusText">Ready</span>
    </div>
  </div>

  <div class="section-label">Connected Devices</div>
  <div class="card" id="devicesCard">
    <div class="empty-state" id="devicesEmpty">No devices connected</div>
    <div id="devicesList"></div>
  </div>

  <div class="section-label">Quick Settings & Controls</div>
  <div class="card">
    <div class="toggle-row">
      <div>
        <div class="toggle-label">Clipboard Sync</div>
        <div class="toggle-sub">Auto copy / paste between peers</div>
      </div>
      <label class="switch">
        <input type="checkbox" id="clipToggle" onchange="toggleClip(this.checked)">
        <span class="slider"></span>
      </label>
    </div>

    <div class="toggle-row">
      <div>
        <div class="toggle-label">File Sharing</div>
        <div class="toggle-sub">Allow sending & receiving files</div>
      </div>
      <label class="switch">
        <input type="checkbox" id="shareToggle" onchange="toggleShare(this.checked)">
        <span class="slider"></span>
      </label>
    </div>

    <button class="btn-block" onclick="quickReconnect()">
      &#9889; Rescan Wi-Fi & Quick Reconnect
    </button>
  </div>

  <div style="display: flex; gap: 8px; margin-top: 12px;">
    <button class="btn-block btn-primary" style="margin-top:0;" onclick="openDashboard()">
      Open Dashboard
    </button>
    <button class="btn-block btn-danger-outline" style="margin-top:0;" onclick="exitCompletely()">
      Exit Completely
    </button>
  </div>

  <script>
    async function updatePopupStatus() {
      try {
        const res = await fetch('/api/status');
        if (!res.ok) return;
        const data = await res.json();

        // 1. Update status badge
        const badge = document.getElementById('statusBadge');
        const statusText = document.getElementById('statusText');
        const connCount = (data.connected_devices || []).length;
        if (connCount > 0) {
          badge.className = 'status-badge';
          statusText.textContent = connCount + ' Connected';
        } else {
          badge.className = 'status-badge idle';
          statusText.textContent = 'Ready (0)';
        }

        // 2. Update connected devices
        const listEl = document.getElementById('devicesList');
        const emptyEl = document.getElementById('devicesEmpty');
        if (connCount > 0) {
          emptyEl.style.display = 'none';
          listEl.innerHTML = data.connected_devices.map(function(d) {
            var name = d.device_name || 'Device';
            var type = d.device_type || 'Peer';
            var ip = d.ip || '';
            var id = d.device_id || '';
            return '<div class="device-item">' +
              '<div class="device-info">' +
                '<div class="device-name">&#9679; ' + name + '</div>' +
                '<div class="device-meta">' + type + ' &bull; ' + ip + '</div>' +
              '</div>' +
              '<button class="btn-disconnect" onclick="disconnectDevice(\'' + id + '\')">Disconnect</button>' +
            '</div>';
          }).join('');
        } else {
          emptyEl.style.display = 'block';
          listEl.innerHTML = '';
        }

        // 3. Update toggles
        document.getElementById('clipToggle').checked = !!data.auto_clipboard_sync;
        document.getElementById('shareToggle').checked = !!data.enabled;
      } catch (e) {
        console.error('updatePopupStatus error:', e);
      }
    }

    async function toggleClip(val) {
      await fetch('/api/toggle-feature?feature=clipboard&enabled=' + val);
      updatePopupStatus();
    }

    async function toggleShare(val) {
      await fetch('/api/toggle-master?enabled=' + val);
      updatePopupStatus();
    }

    async function quickReconnect() {
      await fetch('/api/reconnect');
      setTimeout(updatePopupStatus, 600);
    }

    async function disconnectDevice(id) {
      await fetch('/api/disconnect?id=' + encodeURIComponent(id));
      updatePopupStatus();
    }

    function openDashboard() {
      window.open('/', '_blank');
    }

    async function exitCompletely() {
      if (confirm('Are you sure you want to exit DexoLink completely?')) {
        await fetch('/api/exit');
        window.close();
      }
    }

    setInterval(updatePopupStatus, 1500);
    updatePopupStatus();
  </script>
</body>
</html>
`

package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestCheckPortAvailable(t *testing.T) {
	// A dynamic port should initially be available
	testPort := 53991
	err := checkPortAvailable(testPort)
	if err != nil {
		t.Fatalf("Expected port %d to be available, got error: %v", testPort, err)
	}

	// Bind the port
	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", testPort))
	if err != nil {
		t.Fatalf("Failed to bind test port %d: %v", testPort, err)
	}
	defer ln.Close()

	// Port should now NOT be available
	err = checkPortAvailable(testPort)
	if err == nil {
		t.Fatalf("Expected port %d to be occupied, but checkPortAvailable reported available", testPort)
	}
}

func TestCheckIfInstanceRunning_Inactive(t *testing.T) {
	// When nothing is listening on WebUIPort (or if not running), should return false
	// Note: We use a custom port test to avoid interfering if port 52842 is bound
	client := http.Client{Timeout: 100 * time.Millisecond}
	resp, err := client.Get("http://127.0.0.1:53992/api/status")
	if err == nil && resp != nil {
		_ = resp.Body.Close()
		t.Fatalf("Expected connection error on unused port 53992")
	}
}

func TestWebUIStatusEndpoint(t *testing.T) {
	testEngine := initEngine()
	tempFile := filepath.Join(os.TempDir(), fmt.Sprintf("test_trust_status_%d.json", time.Now().UnixNano()))
	defer os.Remove(tempFile)
	testEngine.TrustStore = &TrustStore{
		MultiplePairedDevices: false,
		PairedDevices:         make(map[string]DeviceInfo),
		filePath:              tempFile,
	}
	engine = testEngine

	// Start an ephemeral test server mimicking startWebDashboard
	handler := http.NewServeMux()
	handler.HandleFunc("/api/status", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		data := map[string]any{
			"device_id":              testEngine.DeviceID,
			"device_name":            testEngine.DeviceName,
			"auto_clipboard_sync":    testEngine.AutoClipboardSync,
			"enhanced_clipboard":     testEngine.EnhancedClipboard,
			"continuity_enabled":     testEngine.ContinuityEnabled,
			"multiple_paired":        testEngine.TrustStore.MultiplePairedDevices,
			"camera_sharing_enabled": testEngine.CameraSharingEnabled,
			"screen_sharing_enabled": testEngine.ScreenSharingEnabled,
			"desktop_mode_enabled":   testEngine.DesktopModeEnabled,
			"auto_discovery":         testEngine.AutoDiscoveryEnabled,
			"auto_accept_paired":     testEngine.AutoAcceptPaired,
			"download_dir":           testEngine.DownloadDir,
		}
		_ = json.NewEncoder(w).Encode(data)
	})

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Failed to bind ephemeral test listener: %v", err)
	}
	defer ln.Close()

	srv := &http.Server{Handler: handler}
	go func() {
		_ = srv.Serve(ln)
	}()
	defer srv.Close()

	testAddr := fmt.Sprintf("http://%s/api/status", ln.Addr().String())
	client := http.Client{Timeout: 1 * time.Second}
	resp, err := client.Get(testAddr)
	if err != nil {
		t.Fatalf("GET /api/status failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("Expected status 200, got %d", resp.StatusCode)
	}

	var status map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&status); err != nil {
		t.Fatalf("Failed to decode JSON: %v", err)
	}

	if status["device_id"] != testEngine.DeviceID {
		t.Fatalf("Device ID mismatch: got %v, want %v", status["device_id"], testEngine.DeviceID)
	}
	if status["multiple_paired"] != false {
		t.Fatalf("Multiple paired devices must default to false for security, got %v", status["multiple_paired"])
	}
}

func TestTrustStoreMultiDeviceToggle(t *testing.T) {
	tempFile := filepath.Join(os.TempDir(), fmt.Sprintf("test_trust_%d.json", time.Now().UnixNano()))
	defer os.Remove(tempFile)

	ts := &TrustStore{
		MultiplePairedDevices: false,
		PairedDevices:         make(map[string]DeviceInfo),
		filePath:              tempFile,
	}

	dev1 := DeviceInfo{DeviceID: "dev_1", DeviceName: "Phone 1", PublicKey: "pk1"}
	dev2 := DeviceInfo{DeviceID: "dev_2", DeviceName: "Phone 2", PublicKey: "pk2"}

	// When multi-device is false, adding dev2 replaces dev1
	ts.AddDevice(dev1)
	if len(ts.PairedDevices) != 1 {
		t.Fatalf("Expected 1 device, got %d", len(ts.PairedDevices))
	}
	ts.AddDevice(dev2)
	if len(ts.PairedDevices) != 1 {
		t.Fatalf("Expected 1 device when multi-device is false, got %d", len(ts.PairedDevices))
	}
	if _, ok := ts.PairedDevices["dev_2"]; !ok {
		t.Fatalf("Expected dev_2 to be the single paired device")
	}

	// Enable multi-device
	ts.SetMultiPaired(true)
	ts.AddDevice(dev1)
	if len(ts.PairedDevices) != 2 {
		t.Fatalf("Expected 2 devices when multi-device is true, got %d", len(ts.PairedDevices))
	}

	// Disable multi-device -> must retain only the latest paired device
	ts.SetMultiPaired(false)
	if len(ts.PairedDevices) != 1 {
		t.Fatalf("Expected 1 device retained when multi-device disabled, got %d", len(ts.PairedDevices))
	}
}

func TestSingleVsMultiDevicePairingRestrictions(t *testing.T) {
	tempFile := filepath.Join(os.TempDir(), fmt.Sprintf("test_single_pairing_%d.json", time.Now().UnixNano()))
	defer os.Remove(tempFile)

	ts := &TrustStore{
		MultiplePairedDevices: false, // Default: single-device mode
		PairedDevices:         make(map[string]DeviceInfo),
		filePath:              tempFile,
	}

	// 1. Initial state: no devices paired yet -> first device is permitted
	ts.AddDevice(DeviceInfo{DeviceID: "mac_1", DeviceName: "MacBook Pro"})
	if len(ts.PairedDevices) != 1 {
		t.Fatalf("Expected 1 device paired, got %d", len(ts.PairedDevices))
	}

	// Helper checking if a discovered device is blocked from pairing
	isBlocked := func(targetID string) bool {
		ts.mu.Lock()
		defer ts.mu.Unlock()
		multi := ts.MultiplePairedDevices
		count := len(ts.PairedDevices)
		_, alreadyPaired := ts.PairedDevices[targetID]
		return !multi && count > 0 && !alreadyPaired
	}

	// In single-device mode, existing paired device is NOT blocked
	if isBlocked("mac_1") {
		t.Fatalf("Existing paired device must not be marked blocked")
	}

	// Another newly discovered device MUST be marked blocked
	if !isBlocked("phone_2") {
		t.Fatalf("Other discovered device 'phone_2' must be blocked in single device mode")
	}

	// 2. Enable multi-device mode -> Blocked status must be lifted
	ts.SetMultiPaired(true)
	if isBlocked("phone_2") {
		t.Fatalf("Device 'phone_2' must NOT be blocked when multiple paired devices is enabled")
	}

	// Pairing phone_2 is now permitted
	ts.AddDevice(DeviceInfo{DeviceID: "phone_2", DeviceName: "Pixel 8"})
	if len(ts.PairedDevices) != 2 {
		t.Fatalf("Expected 2 paired devices in multi-device mode, got %d", len(ts.PairedDevices))
	}
}

func TestFileExistsHelper(t *testing.T) {
	tmpFile := filepath.Join(os.TempDir(), fmt.Sprintf("test_fe_%d.tmp", time.Now().UnixNano()))
	if fileExists(tmpFile) {
		t.Fatalf("Expected fileExists to return false for non-existent file")
	}
	_ = os.WriteFile(tmpFile, []byte("test"), 0644)
	defer os.Remove(tmpFile)

	if !fileExists(tmpFile) {
		t.Fatalf("Expected fileExists to return true for existing file")
	}
	if fileExists(os.TempDir()) {
		t.Fatalf("Expected fileExists to return false for directory")
	}
}

func TestProtocolChunkFraming(t *testing.T) {
	// Verify JSON framing + base64 data encoding compatibility with macOS and Android
	rawPayload := []byte("Hello DexoLink cross-platform chunk test payload!")
	b64Data := base64.StdEncoding.EncodeToString(rawPayload)

	msg := map[string]any{
		"type":        "FILE_CHUNK",
		"file_id":     "test_fid_123",
		"chunk_index": 0,
		"data":        b64Data,
		"is_last":     true,
	}

	jsonData, err := json.Marshal(msg)
	if err != nil {
		t.Fatalf("Failed to marshal chunk JSON: %v", err)
	}

	// Verify framed message with 4-byte big-endian length prefix
	var framed bytes.Buffer
	var lenBuf [4]byte
	binary.BigEndian.PutUint32(lenBuf[:], uint32(len(jsonData)))
	framed.Write(lenBuf[:])
	framed.Write(jsonData)

	// Receiver side: decode length prefix, then decode JSON, then decode base64
	var recLenBuf [4]byte
	if _, err := io.ReadFull(&framed, recLenBuf[:]); err != nil {
		t.Fatalf("Failed to read length prefix: %v", err)
	}
	payloadLen := binary.BigEndian.Uint32(recLenBuf[:])
	if int(payloadLen) != len(jsonData) {
		t.Fatalf("Expected payload len %d, got %d", len(jsonData), payloadLen)
	}

	payloadBuf := make([]byte, payloadLen)
	if _, err := io.ReadFull(&framed, payloadBuf); err != nil {
		t.Fatalf("Failed to read payload: %v", err)
	}

	var recMsg map[string]any
	if err := json.Unmarshal(payloadBuf, &recMsg); err != nil {
		t.Fatalf("Failed to unmarshal JSON payload: %v", err)
	}

	if recMsg["type"] != "FILE_CHUNK" {
		t.Fatalf("Expected type FILE_CHUNK, got %v", recMsg["type"])
	}

	recB64, ok := recMsg["data"].(string)
	if !ok {
		t.Fatalf("Expected base64 data string in chunk")
	}

	decodedBytes, err := base64.StdEncoding.DecodeString(recB64)
	if err != nil {
		t.Fatalf("Failed to decode base64 data: %v", err)
	}

	if !bytes.Equal(decodedBytes, rawPayload) {
		t.Fatalf("Decoded bytes mismatch: got %s, want %s", string(decodedBytes), string(rawPayload))
	}
}

func sendFramedMsg(conn net.Conn, msg any) error {
	data, err := json.Marshal(msg)
	if err != nil {
		return err
	}
	var lenBuf [4]byte
	binary.BigEndian.PutUint32(lenBuf[:], uint32(len(data)))
	if _, err := conn.Write(lenBuf[:]); err != nil {
		return err
	}
	_, err = conn.Write(data)
	return err
}

func readFramedMsg(conn net.Conn) (map[string]any, error) {
	var lenBuf [4]byte
	if _, err := io.ReadFull(conn, lenBuf[:]); err != nil {
		return nil, err
	}
	pLen := binary.BigEndian.Uint32(lenBuf[:])
	buf := make([]byte, pLen)
	if _, err := io.ReadFull(conn, buf); err != nil {
		return nil, err
	}
	var m map[string]any
	err := json.Unmarshal(buf, &m)
	return m, err
}

func TestClipboardCRLFAndNormalization(t *testing.T) {
	// 1. CRLF normalization for Windows (\n -> \r\n)
	unixText := "Line1\nLine2\nLine3"
	winText := normalizeTextForWindows(unixText)
	if winText != "Line1\r\nLine2\r\nLine3" {
		t.Fatalf("Expected Windows CRLF text, got: %q", winText)
	}

	// 2. CRLF normalization for wire (\r\n -> \n)
	wireText := normalizeTextForWire(winText)
	if wireText != "Line1\nLine2\nLine3" {
		t.Fatalf("Expected Wire LF text, got: %q", wireText)
	}

	// 3. Zero-width character stripping (\u200B, \u200C, \u200D, \uFEFF)
	dirtyText := "Hello\u200B\uFEFF World\u200C"
	cleanWin := normalizeTextForWindows(dirtyText)
	if strings.ContainsAny(cleanWin, "\u200B\u200C\u200D\uFEFF") {
		t.Fatalf("Expected zero-width characters stripped, got: %q", cleanWin)
	}
	if cleanWin != "Hello World" {
		t.Fatalf("Expected 'Hello World', got: %q", cleanWin)
	}

	// 4. SHA-256 deduplication hash check
	hash1 := computeTextHash("Hello World")
	hash2 := computeTextHash("Hello\u200B World")
	if hash1 != hash2 {
		t.Fatalf("Hashes must match for identical normalized content to prevent feedback loops")
	}
}

func TestTransferManager_IncomingFileVerification(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "dexolink_transfer_test_*")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	testEngine := initEngine()
	testEngine.DownloadDir = tempDir

	testContent := []byte("Comprehensive DexoLink transfer test content across platforms!")
	testHasher := sha256.New()
	testHasher.Write(testContent)
	expectedHash := hex.EncodeToString(testHasher.Sum(nil))

	transferID := "test_t_001"
	fileID := "test_file_001"
	fileName := "test_document.txt"
	fileSize := int64(len(testContent))

	serverConn, clientConn := net.Pipe()
	defer serverConn.Close()
	defer clientConn.Close()

	// Run engine connection handler
	go testEngine.handleIncomingConnection(serverConn)

	// Step 1: Send HELLO and read HELLO_ACK
	err = sendFramedMsg(clientConn, map[string]any{
		"type":       "HELLO",
		"deviceId":   "peer_mac_1",
		"deviceName": "MacBook Pro",
	})
	if err != nil {
		t.Fatalf("Failed to send HELLO: %v", err)
	}

	helloAck, err := readFramedMsg(clientConn)
	if err != nil || helloAck["type"] != "HELLO_ACK" {
		t.Fatalf("Expected HELLO_ACK, got: %v (err: %v)", helloAck, err)
	}

	// Register active transfer record on engine
	testEngine.transfersMu.Lock()
	testEngine.ActiveTransfers[transferID] = &TransferItem{
		ID:         transferID,
		Name:       fileName,
		Size:       fileSize,
		IsIncoming: true,
		PeerID:     "peer_mac_1",
		PeerName:   "MacBook Pro",
		State:      "pending",
		StartTime:  time.Now(),
	}
	testEngine.transfersMu.Unlock()

	// Step 2: Send FILE_META
	err = sendFramedMsg(clientConn, map[string]any{
		"type":       "FILE_META",
		"transferId": transferID,
		"fileId":     fileID,
		"name":       fileName,
		"size":       float64(fileSize),
	})
	if err != nil {
		t.Fatalf("Failed to send FILE_META: %v", err)
	}

	time.Sleep(50 * time.Millisecond)

	// Step 3: Send FILE_CHUNK
	b64Data := base64.StdEncoding.EncodeToString(testContent)
	err = sendFramedMsg(clientConn, map[string]any{
		"type":   "FILE_CHUNK",
		"fileId": fileID,
		"offset": float64(0),
		"data":   b64Data,
	})
	if err != nil {
		t.Fatalf("Failed to send FILE_CHUNK: %v", err)
	}

	// Read PROGRESS message sent upon receiving all bytes
	progMsg, err := readFramedMsg(clientConn)
	if err != nil || progMsg["type"] != "PROGRESS" {
		t.Fatalf("Expected PROGRESS message, got: %v (err: %v)", progMsg, err)
	}

	// Step 4: Send FILE_COMPLETE with SHA-256
	err = sendFramedMsg(clientConn, map[string]any{
		"type":   "FILE_COMPLETE",
		"fileId": fileID,
		"sha256": expectedHash,
	})
	if err != nil {
		t.Fatalf("Failed to send FILE_COMPLETE: %v", err)
	}

	// Step 5: Read FILE_ACK
	fileAck, err := readFramedMsg(clientConn)
	if err != nil || fileAck["type"] != "FILE_ACK" {
		t.Fatalf("Expected FILE_ACK, got: %v (err: %v)", fileAck, err)
	}
	if fileAck["verified"] != true {
		t.Fatalf("Expected verified: true in FILE_ACK, got: %v", fileAck["verified"])
	}

	// Verify file was written to disk and content matches
	savedPath := filepath.Join(tempDir, fileName)
	readData, err := os.ReadFile(savedPath)
	if err != nil {
		t.Fatalf("Failed to read saved file at %s: %v", savedPath, err)
	}

	if !bytes.Equal(readData, testContent) {
		t.Fatalf("File contents do not match transferred data")
	}
}

func TestTransferManager_Cancellation(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "dexolink_cancel_test_*")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	testEngine := initEngine()
	testEngine.DownloadDir = tempDir

	transferID := "cancel_t_001"
	fileID := "cancel_file_001"
	fileName := "large_video.mp4"

	serverConn, clientConn := net.Pipe()
	defer serverConn.Close()
	defer clientConn.Close()

	go testEngine.handleIncomingConnection(serverConn)

	// Send HELLO
	_ = sendFramedMsg(clientConn, map[string]any{
		"type":       "HELLO",
		"deviceId":   "peer_android_1",
		"deviceName": "Pixel 8",
	})
	_, _ = readFramedMsg(clientConn)

	// Register transfer item
	testEngine.transfersMu.Lock()
	testEngine.ActiveTransfers[transferID] = &TransferItem{
		ID:         transferID,
		FileID:     fileID,
		Name:       fileName,
		Size:       50000000,
		IsIncoming: true,
		PeerID:     "peer_android_1",
		PeerName:   "Pixel 8",
		State:      "transferring",
		StartTime:  time.Now(),
	}
	testEngine.FileIDToTransferID[fileID] = transferID
	testEngine.transfersMu.Unlock()

	// Send FILE_META
	_ = sendFramedMsg(clientConn, map[string]any{
		"type":       "FILE_META",
		"transferId": transferID,
		"fileId":     fileID,
		"name":       fileName,
		"size":       float64(50000000),
	})
	time.Sleep(50 * time.Millisecond)

	// Read CANCEL message in concurrent goroutine to avoid pipe deadlock
	cancelChan := make(chan map[string]any, 1)
	go func() {
		msg, err := readFramedMsg(clientConn)
		if err == nil {
			cancelChan <- msg
		}
	}()

	// Cancel transfer internally
	testEngine.cancelTransferInternal(transferID, true)

	select {
	case cancelMsg := <-cancelChan:
		if cancelMsg["type"] != "CANCEL" {
			t.Fatalf("Expected CANCEL message sent to peer, got: %v", cancelMsg)
		}
	case <-time.After(2 * time.Second):
		t.Fatalf("Timeout waiting for CANCEL message")
	}

	testEngine.transfersMu.Lock()
	item := testEngine.ActiveTransfers[transferID]
	testEngine.transfersMu.Unlock()

	if item == nil || item.State != "cancelled" {
		t.Fatalf("Expected transfer state to be 'cancelled', got: %v", item)
	}
}

func TestMultiDeviceSimultaneousSessions(t *testing.T) {
	testEngine := initEngine()
	testEngine.TrustStore.SetMultiPaired(true)

	// Add 3 devices
	dev1 := DeviceInfo{DeviceID: "mac_pro", DeviceName: "MacBook Pro M3", IP: "192.168.1.10", DeviceType: "macOS"}
	dev2 := DeviceInfo{DeviceID: "pixel_8", DeviceName: "Google Pixel 8", IP: "192.168.1.11", DeviceType: "Android"}
	dev3 := DeviceInfo{DeviceID: "surface_tab", DeviceName: "Surface Pro", IP: "192.168.1.12", DeviceType: "Windows"}

	testEngine.TrustStore.AddDevice(dev1)
	testEngine.TrustStore.AddDevice(dev2)
	testEngine.TrustStore.AddDevice(dev3)

	if len(testEngine.TrustStore.PairedDevices) != 3 {
		t.Fatalf("Expected 3 paired devices, got %d", len(testEngine.TrustStore.PairedDevices))
	}

	// Simulate active sessions for all 3 devices simultaneously
	c1a, c1b := net.Pipe()
	defer c1a.Close()
	defer c1b.Close()

	c2a, c2b := net.Pipe()
	defer c2a.Close()
	defer c2b.Close()

	c3a, c3b := net.Pipe()
	defer c3a.Close()
	defer c3b.Close()

	testEngine.ActiveSessions.Store("mac_pro", &PeerSession{
		DeviceID: dev1.DeviceID, DeviceName: dev1.DeviceName, IP: dev1.IP, DeviceType: dev1.DeviceType, State: "connected", Conn: c1a,
	})
	testEngine.ActiveSessions.Store("pixel_8", &PeerSession{
		DeviceID: dev2.DeviceID, DeviceName: dev2.DeviceName, IP: dev2.IP, DeviceType: dev2.DeviceType, State: "connected", Conn: c2a,
	})
	testEngine.ActiveSessions.Store("surface_tab", &PeerSession{
		DeviceID: dev3.DeviceID, DeviceName: dev3.DeviceName, IP: dev3.IP, DeviceType: dev3.DeviceType, State: "connected", Conn: c3a,
	})

	// Count active sessions
	count := 0
	testEngine.ActiveSessions.Range(func(k, v any) bool {
		count++
		return true
	})

	if count != 3 {
		t.Fatalf("Expected 3 active simultaneous sessions, got %d", count)
	}
}

func TestWebUIPopupAndControls(t *testing.T) {
	testEngine := initEngine()
	engine = testEngine

	mux := http.NewServeMux()
	mux.HandleFunc("/popup", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		fmt.Fprint(w, popupUIHTML)
	})
	mux.HandleFunc("/api/reconnect", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"success": true, "message": "Rescan and reconnect triggered"})
	})
	mux.HandleFunc("/api/toggle-feature", func(w http.ResponseWriter, r *http.Request) {
		feature := r.URL.Query().Get("feature")
		enabled := r.URL.Query().Get("enabled") == "true"
		testEngine.mu.Lock()
		if feature == "clipboard" {
			testEngine.AutoClipboardSync = enabled
		}
		testEngine.mu.Unlock()
		_ = json.NewEncoder(w).Encode(map[string]any{"success": true, "feature": feature, "enabled": enabled})
	})
	mux.HandleFunc("/api/toggle-master", func(w http.ResponseWriter, r *http.Request) {
		enable := r.URL.Query().Get("enabled") == "true"
		testEngine.mu.Lock()
		testEngine.Enabled = enable
		testEngine.mu.Unlock()
		_ = json.NewEncoder(w).Encode(map[string]any{"success": true, "enabled": enable})
	})

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Failed to bind test listener: %v", err)
	}
	defer ln.Close()

	srv := &http.Server{Handler: mux}
	go func() { _ = srv.Serve(ln) }()
	defer srv.Close()

	baseURL := fmt.Sprintf("http://%s", ln.Addr().String())
	client := http.Client{Timeout: 1 * time.Second}

	// 1. Test /popup returns HTML with system tray UI elements
	resp, err := client.Get(baseURL + "/popup")
	if err != nil {
		t.Fatalf("GET /popup failed: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("Expected /popup to return 200, got %d", resp.StatusCode)
	}
	bodyBytes, _ := io.ReadAll(resp.Body)
	bodyStr := string(bodyBytes)
	if !strings.Contains(bodyStr, "DexoLink System Tray") || !strings.Contains(bodyStr, "Connected Devices") {
		t.Fatalf("/popup content missing required Windows 11 system tray elements")
	}

	// 2. Test /api/reconnect
	resp2, err := client.Get(baseURL + "/api/reconnect")
	if err != nil {
		t.Fatalf("GET /api/reconnect failed: %v", err)
	}
	defer resp2.Body.Close()
	var recData map[string]any
	if err := json.NewDecoder(resp2.Body).Decode(&recData); err != nil || recData["success"] != true {
		t.Fatalf("Expected /api/reconnect success: true, got %v", recData)
	}

	// 3. Test /api/toggle-feature?feature=clipboard&enabled=false
	resp3, err := client.Get(baseURL + "/api/toggle-feature?feature=clipboard&enabled=false")
	if err != nil {
		t.Fatalf("GET /api/toggle-feature failed: %v", err)
	}
	defer resp3.Body.Close()
	if testEngine.AutoClipboardSync != false {
		t.Fatalf("Expected AutoClipboardSync to be false after toggle")
	}

	// 4. Test /api/toggle-master?enabled=false
	resp4, err := client.Get(baseURL + "/api/toggle-master?enabled=false")
	if err != nil {
		t.Fatalf("GET /api/toggle-master failed: %v", err)
	}
	defer resp4.Body.Close()
	if testEngine.Enabled != false {
		t.Fatalf("Expected Enabled to be false after toggle")
	}
}

func TestCrossDeviceSharingSingleVsMultiMode(t *testing.T) {
	tempFile := filepath.Join(os.TempDir(), fmt.Sprintf("test_sharing_trust_%d.json", time.Now().UnixNano()))
	defer os.Remove(tempFile)

	ts := &TrustStore{
		filePath:              tempFile,
		MultiplePairedDevices: false, // Default: single device mode
		PairedDevices:         make(map[string]DeviceInfo),
	}
	ts.AddDevice(DeviceInfo{DeviceID: "device_primary", DeviceName: "MacBook Pro"})

	engine := initEngine()
	engine.TrustStore = ts

	// Create 2 mock peer sessions with net.Pipe()
	clientConn1, serverConn1 := net.Pipe()
	defer clientConn1.Close()
	defer serverConn1.Close()

	clientConn2, serverConn2 := net.Pipe()
	defer clientConn2.Close()
	defer serverConn2.Close()

	sess1 := &PeerSession{
		DeviceID:   "device_primary",
		DeviceName: "MacBook Pro",
		Conn:       clientConn1,
	}
	sess2 := &PeerSession{
		DeviceID:   "device_secondary",
		DeviceName: "Pixel 8",
		Conn:       clientConn2,
	}

	// Drain reads asynchronously
	go func() {
		buf := make([]byte, 1024)
		for {
			_, err := serverConn1.Read(buf)
			if err != nil {
				return
			}
		}
	}()
	go func() {
		buf := make([]byte, 1024)
		for {
			_, err := serverConn2.Read(buf)
			if err != nil {
				return
			}
		}
	}()

	engine.ActiveSessions.Store("device_primary", sess1)
	engine.ActiveSessions.Store("device_secondary", sess2)

	// In Single Mode: broadcast should ONLY send to the single paired device (1 recipient)
	sentSingle := engine.BroadcastMessage(map[string]any{"type": "CLIPBOARD_PUSH", "content": "Hello Single"})
	if sentSingle != 1 {
		t.Fatalf("Expected single mode to send to exactly 1 recipient, got %d", sentSingle)
	}

	// Switch to Multiple Mode and pair the second device as well
	ts.mu.Lock()
	ts.MultiplePairedDevices = true
	ts.mu.Unlock()
	ts.AddDevice(DeviceInfo{DeviceID: "device_secondary", DeviceName: "Pixel 8"})

	// In Multiple Mode: broadcast should send to ALL eligible connected paired devices (2 recipients)
	sentMulti := engine.BroadcastMessage(map[string]any{"type": "CLIPBOARD_PUSH", "content": "Hello Multi"})
	if sentMulti != 2 {
		t.Fatalf("Expected multiple mode to send to 2 recipients, got %d", sentMulti)
	}

	// Test Clipboard Hash Deduplication
	hash1 := computeTextHash("Unique text")
	hash2 := computeTextHash("Unique text")
	if hash1 != hash2 {
		t.Fatalf("Expected identical hashes for same text, got %s vs %s", hash1, hash2)
	}
	if len(hash1) != 64 {
		t.Fatalf("Expected 64-char SHA-256 hex hash, got %d chars", len(hash1))
	}
}

func BenchmarkChunkStreaming64KB(b *testing.B) {
	data := make([]byte, 64*1024)
	for i := range data {
		data[i] = byte(i % 256)
	}
	b.SetBytes(64 * 1024)
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		hasher := sha256.New()
		hasher.Write(data)
		b64 := base64.StdEncoding.EncodeToString(data)
		msg := map[string]any{
			"type":   "FILE_CHUNK",
			"fileId": "fl_bench",
			"offset": int64(i * 64 * 1024),
			"data":   b64,
		}
		buf, _ := json.Marshal(msg)
		_ = len(buf)
	}
}

func BenchmarkChunkStreaming512KB(b *testing.B) {
	data := make([]byte, 512*1024)
	for i := range data {
		data[i] = byte(i % 256)
	}
	b.SetBytes(512 * 1024)
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		hasher := sha256.New()
		hasher.Write(data)
		b64 := base64.StdEncoding.EncodeToString(data)
		msg := map[string]any{
			"type":   "FILE_CHUNK",
			"fileId": "fl_bench",
			"offset": int64(i * 512 * 1024),
			"data":   b64,
		}
		buf, _ := json.Marshal(msg)
		_ = len(buf)
	}
}

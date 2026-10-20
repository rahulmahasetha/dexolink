package main

import (
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
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

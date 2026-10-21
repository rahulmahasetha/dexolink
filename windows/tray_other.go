//go:build !windows

package main

func initSystemTray() {
	// No-op on non-Windows platforms
}

func updateTrayStatus(statusText string) {
	// No-op on non-Windows platforms
}

func removeTrayIcon() {
	// No-op on non-Windows platforms
}

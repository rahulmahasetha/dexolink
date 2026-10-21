//go:build !windows

package main

import (
	"bytes"
	"os/exec"
	"sync"
)

var (
	mockClipboardMu   sync.Mutex
	mockClipboardText string
)

// getSystemClipboard provides clipboard reading on non-Windows platforms (macOS / Linux / test environments).
func getSystemClipboard() (string, error) {
	mockClipboardMu.Lock()
	defer mockClipboardMu.Unlock()
	if mockClipboardText != "" {
		return mockClipboardText, nil
	}
	cmd := exec.Command("pbpaste")
	var out bytes.Buffer
	cmd.Stdout = &out
	if err := cmd.Run(); err == nil {
		return out.String(), nil
	}
	return mockClipboardText, nil
}

// setSystemClipboard provides clipboard setting on non-Windows platforms (macOS / Linux / test environments).
func setSystemClipboard(text string) bool {
	mockClipboardMu.Lock()
	defer mockClipboardMu.Unlock()
	mockClipboardText = text
	cmd := exec.Command("pbcopy")
	cmd.Stdin = bytes.NewReader([]byte(text))
	_ = cmd.Run()
	return true
}

// setMockClipboardForTesting explicitly sets the in-memory clipboard for unit testing.
func setMockClipboardForTesting(text string) {
	mockClipboardMu.Lock()
	defer mockClipboardMu.Unlock()
	mockClipboardText = text
}

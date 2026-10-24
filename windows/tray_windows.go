//go:build windows

package main

import (
	"fmt"
	"net"
	"runtime"
	"sync"
	"syscall"
	"unsafe"
)

var (
	modShell32              = syscall.NewLazyDLL("shell32.dll")
	procShellNotifyIconW    = modShell32.NewProc("Shell_NotifyIconW")

	modUser32Tray           = syscall.NewLazyDLL("user32.dll")
	procDefWindowProcW      = modUser32Tray.NewProc("DefWindowProcW")
	procRegisterClassExW    = modUser32Tray.NewProc("RegisterClassExW")
	procCreateWindowExW     = modUser32Tray.NewProc("CreateWindowExW")
	procDestroyWindow       = modUser32Tray.NewProc("DestroyWindow")
	procGetMessageW         = modUser32Tray.NewProc("GetMessageW")
	procTranslateMessage    = modUser32Tray.NewProc("TranslateMessage")
	procDispatchMessageW    = modUser32Tray.NewProc("DispatchMessageW")
	procPostQuitMessage     = modUser32Tray.NewProc("PostQuitMessage")
	procLoadIconW           = modUser32Tray.NewProc("LoadIconW")
	procCreatePopupMenu     = modUser32Tray.NewProc("CreatePopupMenu")
	procAppendMenuW         = modUser32Tray.NewProc("AppendMenuW")
	procTrackPopupMenu      = modUser32Tray.NewProc("TrackPopupMenu")
	procDestroyMenu         = modUser32Tray.NewProc("DestroyMenu")
	procGetCursorPos        = modUser32Tray.NewProc("GetCursorPos")
	procSetForegroundWindow = modUser32Tray.NewProc("SetForegroundWindow")
	procPostMessageW        = modUser32Tray.NewProc("PostMessageW")
	procSetMenuDefaultItem  = modUser32Tray.NewProc("SetMenuDefaultItem")
)

const (
	nimAdd        = 0x00000000
	nimModify     = 0x00000001
	nimDelete     = 0x00000002

	nifMessage = 0x00000001
	nifIcon    = 0x00000002
	nifTip     = 0x00000004

	wmUser         = 0x0400
	wmTrayIcon     = wmUser + 1
	wmCommand      = 0x0111
	wmLButtonUp    = 0x0202
	wmRButtonUp    = 0x0205
	wmLButtonDbl   = 0x0203
	wmContextMenu  = 0x007B
	wmDestroy      = 0x0002

	mfString    = 0x00000000
	mfSeparator = 0x00000800
	mfChecked   = 0x00000008
	mfUnchecked = 0x00000000
	mfGrayed    = 0x00000001
	mfDisabled  = 0x00000002

	tpmRightButton = 0x0002
	tpmReturnCmd   = 0x0100

	idiApplication = 32512
)

type wndClassExW struct {
	cbSize        uint32
	style         uint32
	lpfnWndProc   uintptr
	cbClsExtra    int32
	cbWndExtra    int32
	hInstance     uintptr
	hIcon         uintptr
	hCursor       uintptr
	hbrBackground uintptr
	lpszMenuName  *uint16
	lpszClassName *uint16
	hIconSm       uintptr
}

type notifyIconDataW struct {
	cbSize           uint32
	hWnd             uintptr
	uID              uint32
	uFlags           uint32
	uCallbackMessage uint32
	hIcon            uintptr
	szTip            [128]uint16
	dwState          uint32
	dwStateMask      uint32
	szInfo           [256]uint16
	uTimeoutOrVersion uint32
	szInfoTitle      [64]uint16
	dwInfoFlags      uint32
	guidItem         [16]byte
	hBalloonIcon     uintptr
}

type point struct {
	x int32
	y int32
}

type msg struct {
	hwnd    uintptr
	message uint32
	wParam  uintptr
	lParam  uintptr
	time    uint32
	pt      point
}

var (
	trayHwnd      uintptr
	trayIconData  notifyIconDataW
	trayMu        sync.Mutex
	trayInstalled bool
)

// wndProc handles incoming Windows messages for the tray icon
func wndProc(hwnd uintptr, uMsg uint32, wParam uintptr, lParam uintptr) uintptr {
	switch uMsg {
	case wmTrayIcon:
		switch lParam {
		case wmLButtonUp, wmRButtonUp, wmContextMenu:
			// Clicking (left or right) opens the compact Windows 11 popup menu
			showTrayContextMenu(hwnd)

		case wmLButtonDbl:
			// Double clicking opens the full DexoLink dashboard
			go openGUIWindow(fmt.Sprintf("http://localhost:%d", WebUIPort))
		}
		return 0

	case wmDestroy:
		procPostQuitMessage.Call(0)
		return 0
	}

	r, _, _ := procDefWindowProcW.Call(hwnd, uintptr(uMsg), wParam, lParam)
	return r
}

func showTrayContextMenu(hwnd uintptr) {
	hMenu, _, _ := procCreatePopupMenu.Call()
	if hMenu == 0 {
		return
	}
	defer procDestroyMenu.Call(hMenu)

	engine.mu.Lock()
	var sessions []*PeerSession
	engine.ActiveSessions.Range(func(k, v any) bool {
		if s, ok := v.(*PeerSession); ok && s != nil {
			sessions = append(sessions, s)
		}
		return true
	})
	connCount := len(sessions)
	autoClip := engine.AutoClipboardSync
	sharingEnabled := engine.Enabled
	engine.mu.Unlock()

	// 1. Status Header
	statusTitle := fmt.Sprintf("DexoLink v4.0.2  ●  %d Connected", connCount)
	if connCount == 0 {
		statusTitle = "DexoLink v4.0.2  ○  Ready (0 Connected)"
	}
	titlePtr, _ := syscall.UTF16PtrFromString(statusTitle)
	procAppendMenuW.Call(hMenu, uintptr(mfString|mfDisabled|mfGrayed), 100, uintptr(unsafe.Pointer(titlePtr)))

	// Separator
	procAppendMenuW.Call(hMenu, uintptr(mfSeparator), 0, 0)

	// 2. Connected Devices Header
	devHeaderPtr, _ := syscall.UTF16PtrFromString("CONNECTED DEVICES:")
	procAppendMenuW.Call(hMenu, uintptr(mfString|mfDisabled|mfGrayed), 0, uintptr(unsafe.Pointer(devHeaderPtr)))

	if connCount == 0 {
		noDevPtr, _ := syscall.UTF16PtrFromString("    (No devices currently connected)")
		procAppendMenuW.Call(hMenu, uintptr(mfString|mfDisabled|mfGrayed), 0, uintptr(unsafe.Pointer(noDevPtr)))
	} else {
		for i, s := range sessions {
			devType := s.DeviceType
			if devType == "" {
				devType = "Device"
			}
			label := fmt.Sprintf("    ✓ %s (%s) — %s", s.DeviceName, devType, s.IP)
			itemPtr, _ := syscall.UTF16PtrFromString(label)
			// Item ID 200 + i (clicking disconnects this device)
			procAppendMenuW.Call(hMenu, uintptr(mfString), uintptr(200+i), uintptr(unsafe.Pointer(itemPtr)))
		}
	}

	// Separator
	procAppendMenuW.Call(hMenu, uintptr(mfSeparator), 0, 0)

	// 3. Quick Toggles & Controls Header
	ctrlHeaderPtr, _ := syscall.UTF16PtrFromString("CONTROLS & SETTINGS:")
	procAppendMenuW.Call(hMenu, uintptr(mfString|mfDisabled|mfGrayed), 0, uintptr(unsafe.Pointer(ctrlHeaderPtr)))

	// Clipboard Sync Toggle
	clipFlags := mfString
	if autoClip {
		clipFlags |= mfChecked
	} else {
		clipFlags |= mfUnchecked
	}
	clipPtr, _ := syscall.UTF16PtrFromString("Clipboard Sync (Copy / Paste)")
	procAppendMenuW.Call(hMenu, uintptr(clipFlags), 103, uintptr(unsafe.Pointer(clipPtr)))

	// File Sharing Toggle
	shareFlags := mfString
	if sharingEnabled {
		shareFlags |= mfChecked
	} else {
		shareFlags |= mfUnchecked
	}
	sharePtr, _ := syscall.UTF16PtrFromString("File Sharing (Send / Receive)")
	procAppendMenuW.Call(hMenu, uintptr(shareFlags), 104, uintptr(unsafe.Pointer(sharePtr)))

	// Quick Reconnect
	recPtr, _ := syscall.UTF16PtrFromString("⚡ Quick Reconnect & Rescan")
	procAppendMenuW.Call(hMenu, uintptr(mfString), 102, uintptr(unsafe.Pointer(recPtr)))

	// Separator
	procAppendMenuW.Call(hMenu, uintptr(mfSeparator), 0, 0)

	// 4. Open DexoLink Dashboard (bold default action)
	openPtr, _ := syscall.UTF16PtrFromString("Open DexoLink Dashboard")
	procAppendMenuW.Call(hMenu, uintptr(mfString), 101, uintptr(unsafe.Pointer(openPtr)))
	if procSetMenuDefaultItem.Find() == nil {
		procSetMenuDefaultItem.Call(hMenu, 101, 0)
	}

	// Separator
	procAppendMenuW.Call(hMenu, uintptr(mfSeparator), 0, 0)

	// 5. Exit completely
	exitPtr, _ := syscall.UTF16PtrFromString("Exit DexoLink Completely")
	procAppendMenuW.Call(hMenu, uintptr(mfString), 105, uintptr(unsafe.Pointer(exitPtr)))

	var pt point
	procGetCursorPos.Call(uintptr(unsafe.Pointer(&pt)))

	procSetForegroundWindow.Call(hwnd)
	cmd, _, _ := procTrackPopupMenu.Call(
		hMenu,
		uintptr(tpmRightButton|tpmReturnCmd),
		uintptr(pt.x),
		uintptr(pt.y),
		0,
		hwnd,
		0,
	)
	procPostMessageW.Call(hwnd, 0, 0, 0)

	switch {
	case cmd == 101:
		go openGUIWindow(fmt.Sprintf("http://localhost:%d", WebUIPort))
	case cmd == 102:
		go func() {
			if engine != nil {
				engine.broadcastUDPBeacon()
				engine.reconnectPairedDevices()
			}
		}()
	case cmd == 103:
		engine.mu.Lock()
		engine.AutoClipboardSync = !engine.AutoClipboardSync
		newState := engine.AutoClipboardSync
		engine.mu.Unlock()
		if newState {
			updateTrayStatus("DexoLink - Clipboard Sync Enabled")
		} else {
			updateTrayStatus("DexoLink - Clipboard Sync Disabled")
		}
	case cmd == 104:
		engine.mu.Lock()
		engine.Enabled = !engine.Enabled
		newState := engine.Enabled
		engine.mu.Unlock()
		if newState {
			updateTrayStatus("DexoLink - File Sharing Enabled")
		} else {
			updateTrayStatus("DexoLink - File Sharing Disabled")
		}
	case cmd == 105:
		removeTrayIcon()
		syscall.Exit(0)
	case cmd >= 200 && int(cmd-200) < len(sessions):
		idx := int(cmd - 200)
		target := sessions[idx]
		if target != nil && target.Conn != nil {
			go func(devID, devName string, c net.Conn) {
				_ = c.Close()
				engine.ActiveSessions.Delete(devID)
				updateTrayStatus(fmt.Sprintf("DexoLink - Disconnected %s", devName))
			}(target.DeviceID, target.DeviceName, target.Conn)
		}
	}
}

// initSystemTray initializes the native Windows 11 system notification-area icon and message pump
func initSystemTray() {
	if runtime.GOOS != "windows" {
		return
	}

	go func() {
		className, _ := syscall.UTF16PtrFromString("DexoLinkTrayClass")
		windowTitle, _ := syscall.UTF16PtrFromString("DexoLinkTrayWindow")

		wndProcCallback := syscall.NewCallback(wndProc)

		var wc wndClassExW
		wc.cbSize = uint32(unsafe.Sizeof(wc))
		wc.lpfnWndProc = wndProcCallback
		wc.lpszClassName = className

		procRegisterClassExW.Call(uintptr(unsafe.Pointer(&wc)))

		hwnd, _, _ := procCreateWindowExW.Call(
			0,
			uintptr(unsafe.Pointer(className)),
			uintptr(unsafe.Pointer(windowTitle)),
			0,
			0, 0, 0, 0,
			0, 0, 0, 0,
		)
		if hwnd == 0 {
			return
		}

		trayHwnd = hwnd
		hIcon, _, _ := procLoadIconW.Call(0, uintptr(idiApplication))

		var nid notifyIconDataW
		nid.cbSize = uint32(unsafe.Sizeof(nid))
		nid.hWnd = hwnd
		nid.uID = 1
		nid.uFlags = nifMessage | nifIcon | nifTip
		nid.uCallbackMessage = wmTrayIcon
		nid.hIcon = hIcon

		tipChars, _ := syscall.UTF16FromString("DexoLink - Universal Synchronization")
		copy(nid.szTip[:], tipChars)

		trayMu.Lock()
		trayIconData = nid
		r, _, _ := procShellNotifyIconW.Call(uintptr(nimAdd), uintptr(unsafe.Pointer(&nid)))
		if r != 0 {
			trayInstalled = true
			fmt.Println("[DexoLink Windows] Native Windows 11 system tray icon active in notification area.")
		}
		trayMu.Unlock()

		// Windows Message Pump
		var m msg
		for {
			res, _, _ := procGetMessageW.Call(uintptr(unsafe.Pointer(&m)), 0, 0, 0)
			if res == 0 || int32(res) == -1 {
				break
			}
			procTranslateMessage.Call(uintptr(unsafe.Pointer(&m)))
			procDispatchMessageW.Call(uintptr(unsafe.Pointer(&m)))
		}
	}()
}

// updateTrayStatus updates the tray tooltip and status
func updateTrayStatus(statusText string) {
	if runtime.GOOS != "windows" {
		return
	}
	trayMu.Lock()
	defer trayMu.Unlock()
	if !trayInstalled {
		return
	}
	tipChars, _ := syscall.UTF16FromString(statusText)
	copy(trayIconData.szTip[:], tipChars)
	procShellNotifyIconW.Call(uintptr(nimModify), uintptr(unsafe.Pointer(&trayIconData)))
}

// removeTrayIcon cleans up the system tray icon on shutdown
func removeTrayIcon() {
	if runtime.GOOS != "windows" {
		return
	}
	trayMu.Lock()
	defer trayMu.Unlock()
	if trayInstalled {
		procShellNotifyIconW.Call(uintptr(nimDelete), uintptr(unsafe.Pointer(&trayIconData)))
		trayInstalled = false
	}
	if trayHwnd != 0 {
		procDestroyWindow.Call(trayHwnd)
		trayHwnd = 0
	}
}

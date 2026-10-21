//go:build windows

package main

import (
	"errors"
	"syscall"
	"unsafe"
)

var (
	modUser32                     = syscall.NewLazyDLL("user32.dll")
	procOpenClipboard             = modUser32.NewProc("OpenClipboard")
	procCloseClipboard            = modUser32.NewProc("CloseClipboard")
	procEmptyClipboard            = modUser32.NewProc("EmptyClipboard")
	procGetClipboardData          = modUser32.NewProc("GetClipboardData")
	procSetClipboardData          = modUser32.NewProc("SetClipboardData")
	procIsClipboardFormatAvailable = modUser32.NewProc("IsClipboardFormatAvailable")

	modKernel32      = syscall.NewLazyDLL("kernel32.dll")
	procGlobalAlloc  = modKernel32.NewProc("GlobalAlloc")
	procGlobalLock   = modKernel32.NewProc("GlobalLock")
	procGlobalUnlock = modKernel32.NewProc("GlobalUnlock")
)

const (
	cfUnicodeText = 13
	gmemMoveable  = 0x0002
)

// getSystemClipboard reads UTF-16 text directly from the Windows OS clipboard via Win32 user32.dll.
func getSystemClipboard() (string, error) {
	r, _, _ := procIsClipboardFormatAvailable.Call(uintptr(cfUnicodeText))
	if r == 0 {
		return "", nil
	}

	r, _, _ = procOpenClipboard.Call(0)
	if r == 0 {
		return "", errors.New("cannot open Win32 clipboard")
	}
	defer procCloseClipboard.Call()

	hClip, _, _ := procGetClipboardData.Call(uintptr(cfUnicodeText))
	if hClip == 0 {
		return "", nil
	}

	pClip, _, _ := procGlobalLock.Call(hClip)
	if pClip == 0 {
		return "", errors.New("cannot lock Win32 global memory")
	}
	defer procGlobalUnlock.Call(hClip)

	ptr := (*uint16)(unsafe.Pointer(pClip))
	var runes []uint16
	for {
		v := *ptr
		if v == 0 {
			break
		}
		runes = append(runes, v)
		ptr = (*uint16)(unsafe.Pointer(uintptr(unsafe.Pointer(ptr)) + 2))
	}
	return syscall.UTF16ToString(runes), nil
}

// setSystemClipboard writes UTF-16 text directly to the Windows OS clipboard via Win32 user32.dll.
func setSystemClipboard(text string) bool {
	r, _, _ := procOpenClipboard.Call(0)
	if r == 0 {
		return false
	}
	defer procCloseClipboard.Call()

	procEmptyClipboard.Call()

	utf16Chars, err := syscall.UTF16FromString(text)
	if err != nil {
		return false
	}
	size := len(utf16Chars) * 2

	hMem, _, _ := procGlobalAlloc.Call(uintptr(gmemMoveable), uintptr(size))
	if hMem == 0 {
		return false
	}

	pMem, _, _ := procGlobalLock.Call(hMem)
	if pMem == 0 {
		return false
	}

	copy((*[1 << 30]byte)(unsafe.Pointer(pMem))[:size], (*[1 << 30]byte)(unsafe.Pointer(&utf16Chars[0]))[:size])
	procGlobalUnlock.Call(hMem)

	r, _, _ = procSetClipboardData.Call(uintptr(cfUnicodeText), hMem)
	return r != 0
}

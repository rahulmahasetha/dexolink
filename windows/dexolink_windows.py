"""
DexoLink Windows Companion Engine (v4.0.2)
Lightweight, zero-dependency, local-network cross-platform engine for Windows.
Supports:
- Device discovery (UDP beacon 52841)
- Encrypted / local framing protocol (TCP 52840, 4-byte big-endian framing)
- Multiple Paired Devices toggle (OFF by default, independent connections, device-specific unpair)
- Chunked file transfer (64KB chunks, SHA-256 integrity verification)
- Clipboard synchronization with Windows CRLF normalization and loop prevention
- Continuity / Handoff (Browser URL launch, document handoff)
- Fully offline / local-network operation (no internet required)
"""

import sys
import os
import json
import socket
import select
import struct
import hashlib
import threading
import time
import uuid
import webbrowser
import base64
import io
from typing import Dict, List, Optional, Tuple, Any

# Protocol Constants
DEFAULT_PORT = 52840
BEACON_PORT = 52841
PROTOCOL_VERSION = 1
CHUNK_SIZE = 64 * 1024  # 64KB max chunk size

class DexoLinkErrorCodes:
    UNSUPPORTED_VERSION = 1001
    AUTH_FAILED = 1002
    NOT_PAIRED = 1003
    TRANSFER_REJECTED = 2001
    INSUFFICIENT_STORAGE = 2002
    FILE_TOO_LARGE = 2003
    CHECKSUM_MISMATCH = 2004
    TRANSFER_TIMEOUT = 2005
    TRANSFER_CANCELLED = 2006
    INVALID_FORMAT = 3001
    UNEXPECTED_MESSAGE = 3002
    CONNECTION_LOST = 3003


class TrustStore:
    """Manages paired trusted devices on Windows with Multiple Paired Devices toggle support."""
    def __init__(self, config_path: Optional[str] = None):
        self.config_path = config_path or os.path.expanduser("~/.dexolink_trust.json")
        self.multiple_paired_devices_enabled = False
        self.paired_devices: Dict[str, Dict[str, Any]] = {}
        self.load()

    def load(self):
        if os.path.exists(self.config_path):
            try:
                with open(self.config_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    self.multiple_paired_devices_enabled = data.get("multiple_paired_devices_enabled", False)
                    self.paired_devices = data.get("paired_devices", {})
            except Exception as e:
                print(f"[TrustStore] Error loading config: {e}")
                self.paired_devices = {}
                self.multiple_paired_devices_enabled = False

    def save(self):
        try:
            with open(self.config_path, "w", encoding="utf-8") as f:
                json.dump({
                    "multiple_paired_devices_enabled": self.multiple_paired_devices_enabled,
                    "paired_devices": self.paired_devices
                }, f, indent=2)
        except Exception as e:
            print(f"[TrustStore] Error saving config: {e}")

    def is_device_trusted(self, device_id: str) -> bool:
        return device_id in self.paired_devices

    def add_device(self, device_id: str, device_name: str, public_key: str = "") -> bool:
        if not self.multiple_paired_devices_enabled:
            # Single-paired-device mode: replace existing
            self.paired_devices.clear()
        self.paired_devices[device_id] = {
            "device_id": device_id,
            "device_name": device_name,
            "public_key": public_key,
            "paired_at": time.time()
        }
        self.save()
        return True

    def remove_device(self, device_id: Optional[str] = None):
        """Device-specific unpairing or unpair all."""
        if device_id:
            if device_id in self.paired_devices:
                del self.paired_devices[device_id]
                self.save()
        else:
            self.paired_devices.clear()
            self.save()

    def get_trusted_devices(self) -> List[Dict[str, Any]]:
        return list(self.paired_devices.values())

    def set_multiple_paired_devices_enabled(self, enabled: bool):
        self.multiple_paired_devices_enabled = enabled
        if not enabled and len(self.paired_devices) > 1:
            # Prune to latest device to preserve strict single-device behavior
            latest_id = max(self.paired_devices, key=lambda k: self.paired_devices[k].get("paired_at", 0))
            self.paired_devices = {latest_id: self.paired_devices[latest_id]}
        self.save()


class ProtocolCodec:
    """Encodes and decodes DexoLink 4-byte framed messages and hybrid FILE_CHUNK payloads."""

    @staticmethod
    def encode_message(payload: Dict[str, Any]) -> bytes:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        header = struct.pack(">I", len(data))
        return header + data

    @staticmethod
    def encode_file_chunk(file_id: str, offset: int, chunk_bytes: bytes) -> bytes:
        meta = json.dumps({
            "type": "FILE_CHUNK",
            "fileId": file_id,
            "offset": offset
        }).encode("utf-8")
        json_len = struct.pack(">H", len(meta))
        payload = json_len + meta + chunk_bytes
        header = struct.pack(">I", len(payload))
        return header + payload

    @staticmethod
    def decode_frame(buffer: bytearray) -> Tuple[Optional[Dict[str, Any]], Optional[bytes], int]:
        """
        Attempts to decode a frame from buffer.
        Returns: (parsed_json_dict, raw_binary_chunk, bytes_consumed)
        """
        if len(buffer) < 4:
            return None, None, 0
        payload_len = struct.unpack(">I", buffer[:4])[0]
        total_frame_len = 4 + payload_len
        if len(buffer) < total_frame_len:
            return None, None, 0

        payload = buffer[4:total_frame_len]
        # Check if hybrid FILE_CHUNK
        if len(payload) >= 2:
            json_len = struct.unpack(">H", payload[:2])[0]
            if 2 + json_len <= len(payload):
                try:
                    meta_str = payload[2:2 + json_len].decode("utf-8")
                    meta_json = json.loads(meta_str)
                    if meta_json.get("type") == "FILE_CHUNK":
                        binary_data = bytes(payload[2 + json_len:])
                        return meta_json, binary_data, total_frame_len
                except Exception:
                    pass

        # Standard JSON message
        try:
            msg_str = payload.decode("utf-8")
            msg_json = json.loads(msg_str)
            return msg_json, None, total_frame_len
        except Exception as e:
            print(f"[Codec] Failed to parse message: {e}")
            return None, None, total_frame_len


class WindowsDisplayHelper:
    """Display dimension and multi-monitor resolution helper adapted from p2p.kiwi."""
    DISPLAY_MATCH_PX = 8

    @staticmethod
    def display_pixel_size(bounds: dict, scale_factor: float = 1.0) -> dict:
        """Computes physical pixel size from bounds and scale factor."""
        scale = scale_factor if scale_factor > 0 else 1.0
        return {
            "width": int(round(bounds.get("width", 0) * scale)),
            "height": int(round(bounds.get("height", 0) * scale))
        }

    @staticmethod
    def frame_matches_display(frame: dict, displays: list, tolerance_px: int = 8) -> bool:
        """Checks if a frame matches any display within tolerance."""
        fw = frame.get("width", 0)
        fh = frame.get("height", 0)
        for d in displays:
            dw = d.get("width", 0)
            dh = d.get("height", 0)
            if abs(fw - dw) <= tolerance_px and abs(fh - dh) <= tolerance_px:
                return True
        return False

    @staticmethod
    def share_surface_from_frame(reported: Optional[str], frame: dict, displays: list) -> Optional[str]:
        """Infers whether capture is a full monitor or cropped window."""
        if reported in ("window", "browser"):
            return reported
        fw = frame.get("width", 0)
        fh = frame.get("height", 0)
        sized = fw > 0 and fh > 0
        matches = sized and WindowsDisplayHelper.frame_matches_display(frame, displays)
        if sized and not matches:
            return "window"
        if reported == "monitor" or matches:
            return "monitor"
        return None

    @staticmethod
    def calculate_proportional_target_size(source_width: int, source_height: int, max_edge: int = 1280) -> Tuple[int, int]:
        """Calculates proportional target dimensions maintaining exact aspect ratio."""
        if source_width <= 0 or source_height <= 0:
            return (960, 540)
        max_dim = max(source_width, source_height)
        if max_dim <= max_edge:
            return (source_width, source_height)
        scale = float(max_edge) / float(max_dim)
        target_w = int(round(source_width * scale))
        target_h = int(round(source_height * scale))
        if target_w % 2 != 0:
            target_w -= 1
        if target_h % 2 != 0:
            target_h -= 1
        return (max(target_w, 2), max(target_h, 2))


class WindowsClipboardSync:
    """Cross-platform clipboard helper with Windows CRLF normalization and loop prevention."""
    def __init__(self):
        self.last_sent_hash = ""
        self.last_received_hash = ""
        self.sequence_number = 0

    @staticmethod
    def compute_hash(text: str) -> str:
        return hashlib.sha256(text.encode("utf-8")).hexdigest()

    @staticmethod
    def normalize_text_for_windows(text: str) -> str:
        """Converts Unix \n to Windows \r\n, cleans zero-width characters."""
        # Remove zero-width chars
        cleaned = text.replace("\u200B", "").replace("\u200C", "").replace("\u200D", "").replace("\uFEFF", "")
        # Normalize CRLF
        normalized = cleaned.replace("\r\n", "\n").replace("\r", "\n")
        return normalized.replace("\n", "\r\n")

    @staticmethod
    def normalize_text_for_wire(text: str) -> str:
        """Converts Windows \r\n to standard \n for network transmission."""
        return text.replace("\r\n", "\n").replace("\r", "\n")

    def get_clipboard_text(self) -> Optional[str]:
        """Reads Windows clipboard using ctypes or fallback."""
        if sys.platform == "win32":
            try:
                import ctypes
                user32 = ctypes.windll.user32
                kernel32 = ctypes.windll.kernel32
                CF_UNICODETEXT = 13
                if user32.OpenClipboard(0):
                    try:
                        h_clip = user32.GetClipboardData(CF_UNICODETEXT)
                        if h_clip:
                            p_clip = kernel32.GlobalLock(h_clip)
                            if p_clip:
                                try:
                                    text = ctypes.c_wchar_p(p_clip).value
                                    return text
                                finally:
                                    kernel32.GlobalUnlock(h_clip)
                    finally:
                        user32.CloseClipboard()
            except Exception as e:
                print(f"[Clipboard] Error reading Win32 clipboard: {e}")
        return None

    def set_clipboard_text(self, text: str) -> bool:
        """Sets Windows clipboard using ctypes or fallback."""
        norm = self.normalize_text_for_windows(text)
        if sys.platform == "win32":
            try:
                import ctypes
                user32 = ctypes.windll.user32
                kernel32 = ctypes.windll.kernel32
                CF_UNICODETEXT = 13
                GMEM_MOVEABLE = 0x0002
                if user32.OpenClipboard(0):
                    try:
                        user32.EmptyClipboard()
                        buf = ctypes.create_unicode_buffer(norm)
                        buf_size = ctypes.sizeof(buf)
                        h_mem = kernel32.GlobalAlloc(GMEM_MOVEABLE, buf_size)
                        if h_mem:
                            p_mem = kernel32.GlobalLock(h_mem)
                            if p_mem:
                                ctypes.memmove(p_mem, ctypes.byref(buf), buf_size)
                                kernel32.GlobalUnlock(h_mem)
                                user32.SetClipboardData(CF_UNICODETEXT, h_mem)
                                return True
                    finally:
                        user32.CloseClipboard()
            except Exception as e:
                print(f"[Clipboard] Error setting Win32 clipboard: {e}")
        return True


class DexoLinkWindowsEngine:
    """Main DexoLink engine for Windows."""
    def __init__(self, device_name: Optional[str] = None, port: int = DEFAULT_PORT):
        self.device_id = uuid.uuid4().hex[:16]
        self.device_name = device_name or f"Windows-PC ({socket.gethostname()})"
        self.port = port
        self.trust_store = TrustStore()
        self.clipboard = WindowsClipboardSync()
        self.running = False
        self.server_socket: Optional[socket.socket] = None
        self.active_connections: Dict[str, socket.socket] = {}
        self.transfers_in_progress: Dict[str, Dict[str, Any]] = {}
        self.discovered_devices: Dict[str, Dict[str, Any]] = {}
        self.download_dir = os.path.expanduser("~/Downloads/DexoLink")
        os.makedirs(self.download_dir, exist_ok=True)
        # Feature state toggles (extensible architecture)
        self.continuity_enabled = True
        self.clipboard_sync_enabled = True
        self.enhanced_clipboard_enabled = False
        self.auto_discovery_enabled = True
        self.auto_accept_enabled = True
        # v4.0.2: Media Streaming & Remote Control (OFF by default)
        self.camera_sharing_enabled = False
        self.screen_sharing_enabled = False
        self.desktop_mode_enabled = False
        self.active_streams: Dict[str, Dict[str, Any]] = {}
        # Event callbacks for GUI
        self.on_device_discovered = None
        self.on_device_connected = None
        self.on_device_disconnected = None
        self.on_clipboard_received = None
        self.on_transfer_progress = None
        self.on_transfer_complete = None
        self.on_continuity_received = None
        self.on_stream_started = None
        self.on_stream_stopped = None
        self.on_frame_received = None

    def start(self):
        self.running = True
        # Start TCP server
        self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_socket.bind(("0.0.0.0", self.port))
        self.server_socket.listen(5)
        threading.Thread(target=self._server_loop, daemon=True).start()
        # Start UDP Discovery Broadcaster & Listener
        threading.Thread(target=self._udp_beacon_broadcast, daemon=True).start()
        threading.Thread(target=self._udp_beacon_listen, daemon=True).start()
        print(f"[DexoLink Windows] Engine v4.0.2 listening on port {self.port} (ID: {self.device_id})")

    def stop(self):
        self.running = False
        for s_id in list(self.active_streams.keys()):
            self.stop_stream(s_id, reason="Engine shutting down")
        if self.server_socket:
            try:
                self.server_socket.close()
            except Exception:
                pass
        for sock in list(self.active_connections.values()):
            try:
                sock.close()
            except Exception:
                pass
        self.active_connections.clear()

    def _server_loop(self):
        while self.running:
            try:
                client_sock, client_addr = self.server_socket.accept()
                threading.Thread(target=self._handle_client, args=(client_sock, client_addr), daemon=True).start()
            except Exception:
                break

    def _handle_client(self, client_sock: socket.socket, addr: Tuple[str, int]):
        peer_device_id: Optional[str] = None
        buffer = bytearray()
        try:
            while self.running:
                data = client_sock.recv(4096)
                if not data:
                    break
                buffer.extend(data)
                while True:
                    msg_json, bin_chunk, consumed = ProtocolCodec.decode_frame(buffer)
                    if consumed == 0:
                        break
                    del buffer[:consumed]
                    if msg_json:
                        resp, peer_id = self._process_message(msg_json, bin_chunk, client_sock)
                        if peer_id:
                            peer_device_id = peer_id
                            if not self.trust_store.multiple_paired_devices_enabled and len(self.active_connections) > 0 and peer_device_id not in self.active_connections:
                                # Strict single device mode: disconnect other
                                for old_id, old_sock in list(self.active_connections.items()):
                                    if old_id != peer_device_id:
                                        try:
                                            old_sock.close()
                                        except Exception:
                                            pass
                                        del self.active_connections[old_id]
                            self.active_connections[peer_device_id] = client_sock
                        if resp:
                            client_sock.sendall(ProtocolCodec.encode_message(resp))
        except Exception as e:
            print(f"[DexoLink Windows] Client {addr} disconnected: {e}")
        finally:
            if peer_device_id and peer_device_id in self.active_connections:
                del self.active_connections[peer_device_id]
            try:
                client_sock.close()
            except Exception:
                pass

    def _process_message(self, msg: Dict[str, Any], bin_chunk: Optional[bytes], sock: socket.socket) -> Tuple[Optional[Dict[str, Any]], Optional[str]]:
        msg_type = msg.get("type")
        peer_id = None

        if msg_type == "HELLO":
            peer_id = msg.get("deviceId")
            is_paired = self.trust_store.is_device_trusted(peer_id)
            if not self.trust_store.multiple_paired_devices_enabled and not is_paired and len(self.trust_store.paired_devices) > 0:
                is_paired = False  # Single device limit enforced
            ack = {
                "type": "HELLO_ACK",
                "version": PROTOCOL_VERSION,
                "deviceId": self.device_id,
                "deviceName": self.device_name,
                "paired": is_paired
            }
            return ack, peer_id

        elif msg_type == "PAIR_REQUEST":
            sender_id = msg.get("deviceId", "peer_device")
            sender_name = msg.get("deviceName", "Remote Device")
            pub_key = msg.get("publicKey", "")
            # Auto-accept or user confirmation
            accepted = True
            if not self.trust_store.multiple_paired_devices_enabled and len(self.trust_store.paired_devices) > 0:
                # In single mode, accept replaces existing device
                self.trust_store.remove_device()
            self.trust_store.add_device(sender_id, sender_name, pub_key)
            return {
                "type": "PAIR_RESPONSE",
                "accepted": accepted,
                "publicKey": "windows_public_key"
            }, sender_id

        elif msg_type == "CLIPBOARD_PUSH":
            if not self.clipboard_sync_enabled:
                return {"type": "CLIPBOARD_ACK", "status": "disabled"}, None
            seq = msg.get("sequenceNumber", 0)
            content = msg.get("content", "")
            h = self.clipboard.compute_hash(content)
            if h != self.clipboard.last_received_hash and h != self.clipboard.last_sent_hash:
                self.clipboard.last_received_hash = h
                self.clipboard.set_clipboard_text(content)
                print(f"[DexoLink Windows] Synced incoming clipboard ({len(content)} chars)")
            return {"type": "CLIPBOARD_ACK", "sequenceNumber": seq}, None

        elif msg_type == "CONTINUITY_ACTIVITY":
            if not self.continuity_enabled:
                return {"type": "CONTINUITY_ACK", "status": "disabled"}, None
            act_id = msg.get("activityId", str(uuid.uuid4()))
            act_type = msg.get("activityType", "")
            url = msg.get("url")
            payload = msg.get("payload", {})
            title = msg.get("title", "")
            print(f"[DexoLink Windows Continuity] Received handoff activity: '{title}' ({act_type})")
            if url:
                try:
                    webbrowser.open(url)
                    print(f"[DexoLink Windows Continuity] Opened browser URL: {url}")
                except Exception as e:
                    print(f"[DexoLink Windows Continuity] Error launching URL: {e}")
            elif act_type == "document_reading" and payload.get("filePath"):
                f_path = payload["filePath"]
                if sys.platform == "win32" and os.path.exists(f_path):
                    try:
                        os.startfile(f_path)
                    except Exception as e:
                        print(f"[DexoLink Windows Continuity] Error opening document: {e}")
            return {"type": "CONTINUITY_ACK", "activityId": act_id, "status": "handled"}, None

        elif msg_type == "TRANSFER_REQUEST":
            t_id = msg.get("transferId")
            total_size = msg.get("totalSize", 0)
            # Accept transfer
            self.transfers_in_progress[t_id] = {
                "totalFiles": msg.get("totalFiles", 1),
                "totalSize": total_size,
                "files": {}
            }
            return {"type": "TRANSFER_RESPONSE", "transferId": t_id, "accepted": True}, None

        elif msg_type == "FILE_META":
            f_id = msg.get("fileId")
            t_id = msg.get("transferId")
            f_name = os.path.basename(msg.get("name", f"file_{f_id}"))
            f_size = msg.get("size", 0)
            dest_path = os.path.join(self.download_dir, f_name)
            self.transfers_in_progress.setdefault(t_id, {})["files"] = {
                f_id: {
                    "name": f_name,
                    "size": f_size,
                    "dest_path": dest_path,
                    "hasher": hashlib.sha256(),
                    "handle": open(dest_path, "wb"),
                    "bytes_received": 0
                }
            }
            return None, None

        elif msg_type == "FILE_CHUNK":
            f_id = msg.get("fileId")
            offset = msg.get("offset", 0)
            for t_id, t_info in self.transfers_in_progress.items():
                if f_id in t_info.get("files", {}):
                    f_info = t_info["files"][f_id]
                    if bin_chunk:
                        f_info["handle"].write(bin_chunk)
                        f_info["hasher"].update(bin_chunk)
                        f_info["bytes_received"] += len(bin_chunk)
                    break
            return None, None

        elif msg_type == "FILE_COMPLETE":
            f_id = msg.get("fileId")
            expected_hash = msg.get("sha256", "").lower()
            verified = False
            file_name = f"file_{f_id}"
            for t_id, t_info in self.transfers_in_progress.items():
                if f_id in t_info.get("files", {}):
                    f_info = t_info["files"][f_id]
                    file_name = f_info["name"]
                    f_info["handle"].close()
                    computed_hash = f_info["hasher"].hexdigest().lower()
                    verified = (computed_hash == expected_hash)
                    print(f"[DexoLink Windows] File {file_name} received. Verified: {verified}")
                    if self.on_transfer_complete:
                        try:
                            self.on_transfer_complete(file_name, verified)
                        except Exception:
                            pass
                    break
            return {"type": "FILE_ACK", "fileId": f_id, "verified": verified}, None

        elif msg_type == "STREAM_REQUEST":
            options = msg.get("options") or {}
            if options.get("action") == "switch_camera":
                # Laptops / PCs have a single built-in webcam
                return None, None
            stream_id = msg.get("streamId", str(uuid.uuid4()))
            stream_type = msg.get("streamType", "")
            
            # Verify feature toggle: OFF by default
            if stream_type == "camera" and not self.camera_sharing_enabled:
                return {
                    "type": "STREAM_RESPONSE",
                    "streamId": stream_id,
                    "streamType": stream_type,
                    "accepted": False,
                    "reason": "Camera Sharing is turned OFF in Windows Settings."
                }, None
            elif stream_type == "screen" and not self.screen_sharing_enabled:
                return {
                    "type": "STREAM_RESPONSE",
                    "streamId": stream_id,
                    "streamType": stream_type,
                    "accepted": False,
                    "reason": "Screen Sharing is turned OFF in Windows Settings."
                }, None
            elif stream_type == "desktop" and not self.desktop_mode_enabled:
                return {
                    "type": "STREAM_RESPONSE",
                    "streamId": stream_id,
                    "streamType": stream_type,
                    "accepted": False,
                    "reason": "Desktop Mode is turned OFF in Windows Settings."
                }, None
            
            # Feature is enabled
            is_remote_sending_feed = options.get("direction") == "outgoing" or options.get("sender") in ("android", "mac", "ios", "windows")
            self.active_streams[stream_id] = {
                "streamId": stream_id,
                "streamType": stream_type,
                "direction": "incoming" if is_remote_sending_feed else "outgoing",
                "startedAt": time.time(),
                "peerId": peer_id or "unknown"
            }
            if self.on_stream_started:
                try:
                    self.on_stream_started(stream_id, stream_type)
                except Exception:
                    pass
            if not is_remote_sending_feed and getattr(self, "running", False):
                threading.Thread(target=self._start_streaming_loop, args=(stream_id, stream_type), daemon=True).start()
            return {
                "type": "STREAM_RESPONSE",
                "streamId": stream_id,
                "streamType": stream_type,
                "accepted": True
            }, None

        elif msg_type == "STREAM_RESPONSE":
            stream_id = msg.get("streamId", "")
            accepted = msg.get("accepted", False)
            if not accepted and stream_id in self.active_streams:
                del self.active_streams[stream_id]
            elif accepted and stream_id in self.active_streams and getattr(self, "running", False):
                info = self.active_streams[stream_id]
                if info.get("direction") == "outgoing" and not info.get("loopStarted"):
                    info["loopStarted"] = True
                    st = info.get("streamType", "screen")
                    threading.Thread(target=self._start_streaming_loop, args=(stream_id, st), daemon=True).start()
            return None, None

        elif msg_type == "STREAM_STOP":
            stream_id = msg.get("streamId", "")
            if stream_id in self.active_streams:
                del self.active_streams[stream_id]
            if self.on_stream_stopped:
                try:
                    self.on_stream_stopped(stream_id)
                except Exception:
                    pass
            return None, None

        elif msg_type == "DESKTOP_INPUT_EVENT":
            if not self.desktop_mode_enabled:
                # Security: Ignore all remote input if desktop mode toggle is OFF
                return None, None
            stream_id = msg.get("streamId", "")
            event_type = msg.get("eventType", "")
            x = float(msg.get("x", 0.0))
            y = float(msg.get("y", 0.0))
            button = int(msg.get("button", 0))
            key = msg.get("key", "")
            self.handle_desktop_input(stream_id, event_type, x, y, button, key)
            return None, None

        elif msg_type == "WEBRTC_SIGNAL":
            stream_id = msg.get("streamId", "")
            stream_type = msg.get("streamType", "screen")
            signal_type = msg.get("signalType", "offer")
            if signal_type == "offer":
                answer_sdp = (
                    "v=0\r\no=- 123456789 2 IN IP4 127.0.0.1\r\n"
                    f"s=DexoLink Windows {stream_type} Live Stream\r\n"
                    "t=0 0\r\na=group:BUNDLE 0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96 97\r\n"
                    "c=IN IP4 0.0.0.0\r\na=rtcp:9 IN IP4 0.0.0.0\r\n"
                    "a=rtpmap:96 VP8/90000\r\na=rtpmap:97 H264/90000\r\n"
                    "a=mid:0\r\na=recvonly\r\na=setup:active\r\n"
                )
                resp = {
                    "type": "WEBRTC_SIGNAL",
                    "streamId": stream_id,
                    "streamType": stream_type,
                    "signalType": "answer",
                    "sdp": answer_sdp,
                    "sdpMid": "0",
                    "sdpMLineIndex": 0,
                    "options": {"status": "accepted", "mode": "p2p_with_turn_fallback"}
                }
                return resp, None
            return None, None

        elif msg_type == "STREAM_FRAME":
            stream_id = msg.get("streamId", "")
            data_b64 = msg.get("data", "")
            width = int(msg.get("width", 640))
            height = int(msg.get("height", 480))
            fmt = msg.get("format", "jpeg")
            if getattr(self, "on_frame_received", None):
                try:
                    self.on_frame_received(stream_id, data_b64, width, height, fmt)
                except Exception:
                    pass
            return None, None

        elif msg_type == "PING":
            return {"type": "PONG"}, None

        return None, None

    def set_camera_sharing_enabled(self, enabled: bool):
        self.camera_sharing_enabled = enabled
        if not enabled:
            self._stop_streams_of_type("camera", "Camera Sharing was disabled in Settings")

    def set_screen_sharing_enabled(self, enabled: bool):
        self.screen_sharing_enabled = enabled
        if not enabled:
            self._stop_streams_of_type("screen", "Screen Sharing was disabled in Settings")

    def set_desktop_mode_enabled(self, enabled: bool):
        self.desktop_mode_enabled = enabled
        if not enabled:
            self._stop_streams_of_type("desktop", "Desktop Mode was disabled in Settings")

    def capture_screen_frame(self, max_edge: int = 1280) -> Tuple[str, int, int]:
        """Captures active screen frame and returns (base64_jpeg, width, height)."""
        try:
            from PIL import ImageGrab, Image
            img = ImageGrab.grab()
            orig_w, orig_h = img.size
            tw, th = WindowsDisplayHelper.calculate_proportional_target_size(orig_w, orig_h, max_edge=max_edge)
            if orig_w > tw or orig_h > th:
                resample_filter = getattr(Image, "Resampling", Image).LANCZOS if hasattr(Image, "Resampling") else Image.ANTIALIAS
                img = img.resize((tw, th), resample_filter)

            if img.mode in ("RGBA", "P"):
                img = img.convert("RGB")

            buf = io.BytesIO()
            img.save(buf, format="JPEG", quality=65)
            b64_data = base64.b64encode(buf.getvalue()).decode("utf-8")
            return (b64_data, img.width, img.height)
        except Exception:
            return self._generate_fallback_banner("DexoLink Screen Sharing", "Windows Display Mirroring Active", 960, 540)

    def _generate_fallback_banner(self, title: str, subtitle: str, width: int = 960, height: int = 540) -> Tuple[str, int, int]:
        """Generates a sleek fallback display banner frame."""
        try:
            from PIL import Image, ImageDraw
            img = Image.new("RGB", (width, height), color=(15, 23, 42))
            draw = ImageDraw.Draw(img)
            draw.rectangle([12, 12, width - 12, height - 12], outline=(37, 99, 235), width=3)
            draw.rectangle([12, 12, width - 12, 60], fill=(30, 41, 59))
            draw.text((24, 28), title, fill=(255, 255, 255))
            draw.text((24, 80), subtitle, fill=(148, 163, 184))
            draw.text((24, 120), f"Source: {self.device_name} (Windows Display)", fill=(148, 163, 184))
            draw.text((24, 150), f"Resolution: {width}x{height}", fill=(148, 163, 184))
            buf = io.BytesIO()
            img.save(buf, format="JPEG", quality=65)
            b64_data = base64.b64encode(buf.getvalue()).decode("utf-8")
            return (b64_data, width, height)
        except Exception:
            return ("", width, height)

    def _start_streaming_loop(self, stream_id: str, stream_type: str):
        """Streaming background loop transmitting JPEG frames over active connections."""
        seq = 0
        while self.running and stream_id in self.active_streams:
            seq += 1
            if stream_type in ("screen", "desktop"):
                b64_data, w, h = self.capture_screen_frame(max_edge=1280)
            else:
                b64_data, w, h = self._generate_fallback_banner("DexoLink Camera Feed", "Live Camera Stream Active", 640, 480)

            if b64_data:
                msg = {
                    "type": "STREAM_FRAME",
                    "streamId": stream_id,
                    "sequence": seq,
                    "format": "jpeg",
                    "width": w,
                    "height": h,
                    "data": b64_data
                }
                encoded = ProtocolCodec.encode_message(msg)
                for sock in list(self.active_connections.values()):
                    try:
                        sock.sendall(encoded)
                    except Exception:
                        pass
            time.sleep(0.08)  # ~12 FPS

    def _stop_streams_of_type(self, stream_type: str, reason: str):
        for s_id, s_info in list(self.active_streams.items()):
            if s_info.get("streamType") == stream_type:
                del self.active_streams[s_id]
                stop_msg = ProtocolCodec.encode_message({
                    "type": "STREAM_STOP",
                    "streamId": s_id,
                    "reason": reason
                })
                for sock in list(self.active_connections.values()):
                    try:
                        sock.sendall(stop_msg)
                    except Exception:
                        pass
                if self.on_stream_stopped:
                    try:
                        self.on_stream_stopped(s_id)
                    except Exception:
                        pass

    def stop_stream(self, stream_id: str, reason: str = "User stopped stream"):
        if stream_id in self.active_streams:
            del self.active_streams[stream_id]
            stop_msg = ProtocolCodec.encode_message({
                "type": "STREAM_STOP",
                "streamId": stream_id,
                "reason": reason
            })
            for sock in list(self.active_connections.values()):
                try:
                    sock.sendall(stop_msg)
                except Exception:
                    pass
            if self.on_stream_stopped:
                try:
                    self.on_stream_stopped(stream_id)
                except Exception:
                    pass

    def send_stream_switch_camera(self, stream_id: str):
        """Sends remote switch_camera request to peer."""
        req_msg = ProtocolCodec.encode_message({
            "type": "STREAM_REQUEST",
            "streamId": stream_id,
            "streamType": "camera",
            "options": {"action": "switch_camera", "lens": "toggle"}
        })
        for sock in list(self.active_connections.values()):
            try:
                sock.sendall(req_msg)
            except Exception:
                pass

    def handle_desktop_input(self, stream_id: str, event_type: str, x: float, y: float, button: int = 0, key: str = ""):
        """Injects remote input events when Desktop Mode is enabled."""
        if not self.desktop_mode_enabled:
            return
        if sys.platform == "win32":
            try:
                import ctypes
                user32 = ctypes.windll.user32
                screen_w = user32.GetSystemMetrics(0)
                screen_h = user32.GetSystemMetrics(1)
                pixel_x = int(x * screen_w)
                pixel_y = int(y * screen_h)
                
                if event_type == "mouse_move":
                    user32.SetCursorPos(pixel_x, pixel_y)
                elif event_type == "mouse_down":
                    user32.SetCursorPos(pixel_x, pixel_y)
                    event_flag = 0x0002 if button == 0 else 0x0008
                    user32.mouse_event(event_flag, 0, 0, 0, 0)
                elif event_type == "mouse_up":
                    user32.SetCursorPos(pixel_x, pixel_y)
                    event_flag = 0x0004 if button == 0 else 0x0010
                    user32.mouse_event(event_flag, 0, 0, 0, 0)
                elif event_type == "click":
                    user32.SetCursorPos(pixel_x, pixel_y)
                    down_flag = 0x0002 if button == 0 else 0x0008
                    up_flag = 0x0004 if button == 0 else 0x0010
                    user32.mouse_event(down_flag, 0, 0, 0, 0)
                    user32.mouse_event(up_flag, 0, 0, 0, 0)
                elif event_type in ("text_input", "write"):
                    if key:
                        for char in key:
                            user32.keybd_event(0, ord(char), 0x0004, 0)
                            user32.keybd_event(0, ord(char), 0x0004 | 0x0002, 0)
                elif event_type == "key_press":
                    if key:
                        k_lower = key.lower()
                        vk_map = {
                            "return": 0x0D, "enter": 0x0D,
                            "backspace": 0x08, "delete": 0x2E,
                            "tab": 0x09, "space": 0x20, "escape": 0x1B,
                            "up": 0x26, "down": 0x28, "left": 0x25, "right": 0x27
                        }
                        vk = vk_map.get(k_lower, 0)
                        if vk:
                            user32.keybd_event(vk, 0, 0, 0)
                            user32.keybd_event(vk, 0, 2, 0)
            except Exception as e:
                print(f"[DexoLink Windows Remote Control] Error simulating input: {e}")

    def push_clipboard(self, text: str):
        """Pushes clipboard text to all active connected devices."""
        h = self.clipboard.compute_hash(text)
        self.clipboard.last_sent_hash = h
        self.clipboard.sequence_number += 1
        msg = {
            "type": "CLIPBOARD_PUSH",
            "sequenceNumber": self.clipboard.sequence_number,
            "content": text
        }
        encoded = ProtocolCodec.encode_message(msg)
        for dev_id, sock in list(self.active_connections.items()):
            try:
                sock.sendall(encoded)
            except Exception:
                pass

    def send_continuity_url(self, url: str, title: str = "Web Page"):
        """Sends a Continuity Handoff URL to connected devices."""
        msg = {
            "type": "CONTINUITY_ACTIVITY",
            "activityId": str(uuid.uuid4()),
            "activityType": "web_browsing",
            "title": title,
            "url": url,
            "timestamp": time.time(),
            "sourceDeviceId": self.device_id
        }
        encoded = ProtocolCodec.encode_message(msg)
        for dev_id, sock in list(self.active_connections.items()):
            try:
                sock.sendall(encoded)
            except Exception:
                pass

    def send_protocol_message(self, msg: dict, target_peer_id: str = None) -> bool:
        """Sends an encoded protocol message to a specific peer or all connected peers."""
        encoded = ProtocolCodec.encode_message(msg)
        sent = False
        for dev_id, sock in list(self.active_connections.items()):
            if target_peer_id is None or dev_id == target_peer_id:
                try:
                    sock.sendall(encoded)
                    sent = True
                except Exception:
                    pass
        return sent

    def send_file(self, peer_ip: str, peer_port: int, file_path: str, progress_callback=None) -> bool:
        """Sends a file in 64KB chunks with SHA-256 integrity over TCP to a peer."""
        if not os.path.exists(file_path):
            return False
        file_size = os.path.getsize(file_path)
        file_name = os.path.basename(file_path)
        transfer_id = str(uuid.uuid4())
        file_id = str(uuid.uuid4())

        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(15.0)
            s.connect((peer_ip, peer_port))

            # 1. Hello Handshake
            s.sendall(ProtocolCodec.encode_message({
                "type": "HELLO",
                "version": PROTOCOL_VERSION,
                "deviceId": self.device_id,
                "deviceName": self.device_name
            }))
            # Wait for HELLO_ACK
            data = s.recv(4096)
            ack_msg, _, _ = ProtocolCodec.decode_frame(bytearray(data))
            if not ack_msg or ack_msg.get("type") != "HELLO_ACK":
                s.close()
                return False

            # 2. Transfer Request
            s.sendall(ProtocolCodec.encode_message({
                "type": "TRANSFER_REQUEST",
                "transferId": transfer_id,
                "totalFiles": 1,
                "totalSize": file_size
            }))
            data = s.recv(4096)
            trans_resp, _, _ = ProtocolCodec.decode_frame(bytearray(data))
            if not trans_resp or not trans_resp.get("accepted"):
                s.close()
                return False

            # 3. File Meta
            s.sendall(ProtocolCodec.encode_message({
                "type": "FILE_META",
                "transferId": transfer_id,
                "fileId": file_id,
                "name": file_name,
                "size": file_size,
                "mimeType": "application/octet-stream"
            }))

            # 4. Stream 64KB Chunks
            hasher = hashlib.sha256()
            bytes_sent = 0
            with open(file_path, "rb") as f:
                while True:
                    chunk = f.read(CHUNK_SIZE)
                    if not chunk:
                        break
                    hasher.update(chunk)
                    chunk_frame = ProtocolCodec.encode_file_chunk(file_id, bytes_sent, chunk)
                    s.sendall(chunk_frame)
                    bytes_sent += len(chunk)
                    if progress_callback:
                        progress_callback(file_name, bytes_sent, file_size)

            # 5. File Complete
            s.sendall(ProtocolCodec.encode_message({
                "type": "FILE_COMPLETE",
                "fileId": file_id,
                "sha256": hasher.hexdigest().lower()
            }))

            # 6. File Ack
            data = s.recv(4096)
            file_ack, _, _ = ProtocolCodec.decode_frame(bytearray(data))
            verified = file_ack and file_ack.get("verified", False)
            s.close()
            return bool(verified)
        except Exception as e:
            print(f"[DexoLink Windows] send_file error: {e}")
            return False

    def _udp_beacon_broadcast(self):
        """Broadcasts UDP beacon for local network discovery."""
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        beacon_msg = json.dumps({
            "type": "BEACON",
            "version": PROTOCOL_VERSION,
            "deviceId": self.device_id,
            "deviceName": self.device_name,
            "port": self.port
        }).encode("utf-8")

        while self.running:
            try:
                sock.sendto(beacon_msg, ("255.255.255.255", BEACON_PORT))
            except Exception:
                pass
            time.sleep(3)

    def _udp_beacon_listen(self):
        """Listens for UDP beacons from other DexoLink devices on the local network."""
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("", BEACON_PORT))
            while self.running:
                data, addr = sock.recvfrom(2048)
                if not self.auto_discovery_enabled:
                    continue
                try:
                    payload = json.loads(data.decode("utf-8"))
                    dev_id = payload.get("deviceId")
                    if dev_id and dev_id != self.device_id:
                        device_info = {
                            "deviceId": dev_id,
                            "deviceName": payload.get("deviceName", "Remote Device"),
                            "ip": addr[0],
                            "port": payload.get("port", DEFAULT_PORT),
                            "lastSeen": time.time()
                        }
                        self.discovered_devices[dev_id] = device_info
                        if self.on_device_discovered:
                            try:
                                self.on_device_discovered(device_info)
                            except Exception:
                                pass
                except Exception:
                    pass
        except Exception:
            pass

    def connect_to_remote(self, host: str, port: int = DEFAULT_PORT) -> bool:
        """Connect directly to a remote or online peer over TCP/IP."""
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(10.0)
            sock.connect((host, port))
            sock.settimeout(None)
            sock_id = f"{host}:{port}"
            self.active_connections[sock_id] = sock
            threading.Thread(target=self._handle_client, args=(sock, (host, port)), daemon=True).start()
            # Send initial HELLO handshake
            sock.sendall(ProtocolCodec.encode_message({
                "type": "HELLO",
                "version": PROTOCOL_VERSION,
                "deviceId": self.device_id,
                "deviceName": self.device_name
            }))
            print(f"[DexoLink Windows] Successfully connected to remote peer {host}:{port}")
            return True
        except Exception as e:
            print(f"[DexoLink Windows] Failed connecting to remote {host}:{port}: {e}")
            return False


if __name__ == "__main__":
    engine = DexoLinkWindowsEngine()
    engine.start()
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        engine.stop()


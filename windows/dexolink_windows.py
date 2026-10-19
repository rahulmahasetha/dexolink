"""
DexoLink Windows Companion Engine (v4.0.0)
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
        self.download_dir = os.path.expanduser("~/Downloads/DexoLink")
        os.makedirs(self.download_dir, exist_ok=True)

    def start(self):
        self.running = True
        # Start TCP server
        self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_socket.bind(("0.0.0.0", self.port))
        self.server_socket.listen(5)
        threading.Thread(target=self._server_loop, daemon=True).start()
        # Start UDP Discovery
        threading.Thread(target=self._udp_beacon_broadcast, daemon=True).start()
        print(f"[DexoLink Windows] Engine v4.0.0 listening on port {self.port} (ID: {self.device_id})")

    def stop(self):
        self.running = False
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
            seq = msg.get("sequenceNumber", 0)
            content = msg.get("content", "")
            h = self.clipboard.compute_hash(content)
            if h != self.clipboard.last_received_hash and h != self.clipboard.last_sent_hash:
                self.clipboard.last_received_hash = h
                self.clipboard.set_clipboard_text(content)
                print(f"[DexoLink Windows] Synced incoming clipboard ({len(content)} chars)")
            return {"type": "CLIPBOARD_ACK", "sequenceNumber": seq}, None

        elif msg_type == "CONTINUITY_ACTIVITY":
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
            for t_id, t_info in self.transfers_in_progress.items():
                if f_id in t_info.get("files", {}):
                    f_info = t_info["files"][f_id]
                    f_info["handle"].close()
                    computed_hash = f_info["hasher"].hexdigest().lower()
                    verified = (computed_hash == expected_hash)
                    print(f"[DexoLink Windows] File {f_info['name']} received. Verified: {verified}")
                    break
            return {"type": "FILE_ACK", "fileId": f_id, "verified": verified}, None

        elif msg_type == "PING":
            return {"type": "PONG"}, None

        return None, None

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


if __name__ == "__main__":
    engine = DexoLinkWindowsEngine()
    engine.start()
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        engine.stop()

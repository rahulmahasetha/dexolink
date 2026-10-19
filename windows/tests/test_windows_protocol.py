import unittest
import os
import json
import struct
import hashlib
import tempfile
import shutil
import sys

# Ensure windows directory is in path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from dexolink_windows import ProtocolCodec, TrustStore, WindowsClipboardSync, DexoLinkWindowsEngine

class TestWindowsProtocol(unittest.TestCase):

    def setUp(self):
        self.test_dir = tempfile.mkdtemp()
        self.trust_file = os.path.join(self.test_dir, "trust.json")

    def tearDown(self):
        shutil.rmtree(self.test_dir)

    def test_protocol_framing_encode_decode(self):
        payload = {"type": "HELLO", "version": 1, "deviceId": "win_dev_1", "deviceName": "Test Windows"}
        framed = ProtocolCodec.encode_message(payload)
        
        # Check 4-byte header length
        length = struct.unpack(">I", framed[:4])[0]
        self.assertEqual(length, len(framed) - 4)

        buf = bytearray(framed)
        decoded, binary, consumed = ProtocolCodec.decode_frame(buf)
        self.assertEqual(consumed, len(framed))
        self.assertIsNone(binary)
        self.assertEqual(decoded["type"], "HELLO")
        self.assertEqual(decoded["deviceId"], "win_dev_1")

    def test_file_chunk_hybrid_framing(self):
        file_id = "test_file_123"
        offset = 1024
        raw_binary = b"DexoLinkBinaryContentPayload" * 10
        framed = ProtocolCodec.encode_file_chunk(file_id, offset, raw_binary)

        buf = bytearray(framed)
        decoded_meta, bin_data, consumed = ProtocolCodec.decode_frame(buf)
        self.assertEqual(consumed, len(framed))
        self.assertEqual(decoded_meta["type"], "FILE_CHUNK")
        self.assertEqual(decoded_meta["fileId"], file_id)
        self.assertEqual(decoded_meta["offset"], offset)
        self.assertEqual(bin_data, raw_binary)

    def test_shared_test_vectors(self):
        test_vectors_path = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../shared/protocol/test_vectors.json"))
        if os.path.exists(test_vectors_path):
            with open(test_vectors_path, "r", encoding="utf-8") as f:
                vectors = json.load(f)
            
            for key, msg in vectors.items():
                encoded = ProtocolCodec.encode_message(msg)
                decoded, _, consumed = ProtocolCodec.decode_frame(bytearray(encoded))
                self.assertEqual(consumed, len(encoded))
                self.assertEqual(decoded["type"], msg["type"])

    def test_multiple_paired_devices_toggle_off_by_default(self):
        store = TrustStore(config_path=self.trust_file)
        self.assertFalse(store.multiple_paired_devices_enabled, "Multiple Paired Devices must be OFF by default")

    def test_single_paired_device_mode(self):
        store = TrustStore(config_path=self.trust_file)
        self.assertFalse(store.multiple_paired_devices_enabled)

        # In single mode, adding a new device replaces previous device
        store.add_device("dev_1", "MacBook Pro")
        self.assertEqual(len(store.get_trusted_devices()), 1)
        self.assertTrue(store.is_device_trusted("dev_1"))

        store.add_device("dev_2", "Android Phone")
        self.assertEqual(len(store.get_trusted_devices()), 1)
        self.assertFalse(store.is_device_trusted("dev_1"))
        self.assertTrue(store.is_device_trusted("dev_2"))

    def test_multiple_paired_devices_mode_and_specific_unpair(self):
        store = TrustStore(config_path=self.trust_file)
        store.set_multiple_paired_devices_enabled(True)
        self.assertTrue(store.multiple_paired_devices_enabled)

        store.add_device("dev_1", "MacBook Pro")
        store.add_device("dev_2", "Android Phone")
        store.add_device("dev_3", "iPhone 15")

        self.assertEqual(len(store.get_trusted_devices()), 3)
        self.assertTrue(store.is_device_trusted("dev_1"))
        self.assertTrue(store.is_device_trusted("dev_2"))
        self.assertTrue(store.is_device_trusted("dev_3"))

        # Device-specific unpair
        store.remove_device("dev_2")
        self.assertEqual(len(store.get_trusted_devices()), 2)
        self.assertTrue(store.is_device_trusted("dev_1"))
        self.assertFalse(store.is_device_trusted("dev_2"))
        self.assertTrue(store.is_device_trusted("dev_3"))

        # Switching toggle OFF prunes to a single device
        store.set_multiple_paired_devices_enabled(False)
        self.assertEqual(len(store.get_trusted_devices()), 1)

    def test_clipboard_normalization_and_hash(self):
        clip = WindowsClipboardSync()
        raw = "Hello\r\nWorld\nFrom\rDexoLink\u200B"
        win_norm = clip.normalize_text_for_windows(raw)
        self.assertNotIn("\u200B", win_norm)
        self.assertIn("\r\n", win_norm)

        wire_norm = clip.normalize_text_for_wire(win_norm)
        self.assertNotIn("\r\n", wire_norm)
        self.assertIn("\n", wire_norm)

        h1 = clip.compute_hash(raw)
        h2 = clip.compute_hash(raw)
        self.assertEqual(h1, h2)

    def test_continuity_message_ack(self):
        engine = DexoLinkWindowsEngine(port=52999)
        engine.trust_store.config_path = self.trust_file
        
        continuity_msg = {
            "type": "CONTINUITY_ACTIVITY",
            "activityId": "act-101",
            "activityType": "web_browsing",
            "title": "GitHub",
            "url": "https://github.com",
            "timestamp": 123456789.0
        }
        
        # Test processing Continuity activity
        resp, _ = engine._process_message(continuity_msg, None, None)
        self.assertIsNotNone(resp)
        self.assertEqual(resp["type"], "CONTINUITY_ACK")
        self.assertEqual(resp["activityId"], "act-101")
        self.assertEqual(resp["status"], "handled")

    def test_file_integrity_sha256(self):
        engine = DexoLinkWindowsEngine(port=52998)
        engine.trust_store.config_path = self.trust_file
        engine.download_dir = self.test_dir

        t_id = "trans-1"
        f_id = "f-1"
        data = b"DexoLink cross-platform test binary file contents"
        sha256 = hashlib.sha256(data).hexdigest()

        # Step 1: Transfer Request
        req_msg = {"type": "TRANSFER_REQUEST", "transferId": t_id, "totalFiles": 1, "totalSize": len(data)}
        resp, _ = engine._process_message(req_msg, None, None)
        self.assertTrue(resp["accepted"])

        # Step 2: File Meta
        meta_msg = {"type": "FILE_META", "transferId": t_id, "fileId": f_id, "name": "sample.txt", "size": len(data)}
        engine._process_message(meta_msg, None, None)

        # Step 3: File Chunk
        chunk_msg = {"type": "FILE_CHUNK", "fileId": f_id, "offset": 0}
        engine._process_message(chunk_msg, data, None)

        # Step 4: File Complete
        comp_msg = {"type": "FILE_COMPLETE", "fileId": f_id, "sha256": sha256}
        ack, _ = engine._process_message(comp_msg, None, None)
        self.assertIsNotNone(ack)
        self.assertEqual(ack["type"], "FILE_ACK")
        self.assertTrue(ack["verified"])

        # Check file content on disk
        saved_file = os.path.join(self.test_dir, "sample.txt")
        self.assertTrue(os.path.exists(saved_file))
        with open(saved_file, "rb") as f:
            self.assertEqual(f.read(), data)


if __name__ == "__main__":
    unittest.main()

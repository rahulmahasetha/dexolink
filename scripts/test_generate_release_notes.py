import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from generate_release_notes import (
    Commit,
    generate_release_notes,
    detect_repo_slug,
    SCOPE_DISPLAY_MAP,
)


class TestReleaseNotesGenerator(unittest.TestCase):
    def test_conventional_feature(self):
        c = Commit(
            full_hash="a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2",
            short_hash="a1b2c3d",
            author="Developer",
            subject="feat(android): add dark mode support",
            body="",
        )
        self.assertEqual(c.category, "features")
        self.assertEqual(c.scope, "Android")
        self.assertEqual(c.description, "Add dark mode support")
        self.assertFalse(c.is_breaking)

    def test_conventional_fix(self):
        c = Commit(
            full_hash="1234567890abcdef1234567890abcdef12345678",
            short_hash="1234567",
            author="Developer",
            subject="fix(macos): resolve crash on menu bar click",
            body="",
        )
        self.assertEqual(c.category, "fixes")
        self.assertEqual(c.scope, "macOS")
        self.assertEqual(c.description, "Resolve crash on menu bar click")
        self.assertFalse(c.is_breaking)

    def test_conventional_perf(self):
        c = Commit(
            full_hash="abcdef1234567890abcdef1234567890abcdef12",
            short_hash="abcdef1",
            author="Developer",
            subject="perf(ui): optimize screen scrolling with stable keys",
            body="",
        )
        self.assertEqual(c.category, "perf")
        self.assertEqual(c.scope, "UI")
        self.assertEqual(c.description, "Optimize screen scrolling with stable keys")
        self.assertFalse(c.is_breaking)

    def test_conventional_ui_ux(self):
        c = Commit(
            full_hash="fedcba0987654321fedcba0987654321fedcba09",
            short_hash="fedcba0",
            author="Developer",
            subject="ui(ios): revamp connection status banner",
            body="",
        )
        self.assertEqual(c.category, "ui")
        self.assertEqual(c.scope, "iOS")
        self.assertEqual(c.description, "Revamp connection status banner")
        self.assertFalse(c.is_breaking)

    def test_conventional_security(self):
        c = Commit(
            full_hash="1122334455667788990011223344556677889900",
            short_hash="1122334",
            author="Developer",
            subject="sec(core): sanitize remote input buffer to prevent buffer overflow",
            body="",
        )
        self.assertEqual(c.category, "security")
        self.assertEqual(c.scope, "Core")
        self.assertEqual(c.description, "Sanitize remote input buffer to prevent buffer overflow")
        self.assertFalse(c.is_breaking)

    def test_breaking_change_bang(self):
        c = Commit(
            full_hash="9988776655443322110099887766554433221100",
            short_hash="9988776",
            author="Developer",
            subject="feat(api)!: require token authentication on websocket connections",
            body="",
        )
        self.assertTrue(c.is_breaking)
        self.assertEqual(c.category, "features")
        self.assertEqual(c.scope, "API")

    def test_breaking_change_body(self):
        c = Commit(
            full_hash="3344556677889900112233445566778899001122",
            short_hash="3344556",
            author="Developer",
            subject="refactor(protocol): change packet header format",
            body="BREAKING CHANGE: Legacy protocol v1 packets will be rejected.",
        )
        self.assertTrue(c.is_breaking)
        self.assertEqual(c.breaking_note, "Legacy protocol v1 packets will be rejected.")

    def test_platform_prefixed_commit(self):
        c = Commit(
            full_hash="4455667788990011223344556677889900112233",
            short_hash="4455667",
            author="Developer",
            subject="windows: replace Home UI with macOS MenuBar-style UI",
            body="",
        )
        self.assertEqual(c.category, "ui")
        self.assertEqual(c.scope, "Windows")
        self.assertEqual(c.description, "Replace Home UI with macOS MenuBar-style UI")

    def test_platform_scope_subscope(self):
        c = Commit(
            full_hash="5566778899001122334455667788990011223344",
            short_hash="5566778",
            author="Developer",
            subject="Android(core): implement NSD discovery and socket manager",
            body="",
        )
        self.assertEqual(c.category, "features")
        self.assertEqual(c.scope, "Android (Core)")
        self.assertEqual(c.description, "Implement NSD discovery and socket manager")

    def test_build_commit_categorized_as_other(self):
        c = Commit(
            full_hash="6677889900112233445566778899001122334455",
            short_hash="6677889",
            author="Developer",
            subject="windows: build DexoLink.exe v4.0.1 with macOS MenuBar UI",
            body="",
        )
        self.assertEqual(c.category, "other")
        self.assertEqual(c.scope, "Windows")

    def test_generate_release_notes_structure(self):
        c_feat = Commit("111", "111", "dev", "feat(windows): add clipboard sync", "")
        c_fix = Commit("222", "222", "dev", "fix(ios): prevent background freeze", "")
        c_breaking = Commit("333", "333", "dev", "feat(core)!: drop unencrypted tcp", "BREAKING CHANGE: TLS is required.")
        
        notes = generate_release_notes("v4.0.2", "v4.0.1", [c_feat, c_fix, c_breaking], "rahulmahasetha/dexolink")
        
        self.assertIn("## What's Changed in DexoLink v4.0.2", notes)
        self.assertIn("### ⚠️ Breaking Changes", notes)
        self.assertIn("TLS is required.", notes)
        self.assertIn("### 🚀 Features", notes)
        self.assertIn("- **Windows**: Add clipboard sync", notes)
        self.assertIn("### 🐛 Bug Fixes", notes)
        self.assertIn("- **iOS**: Prevent background freeze", notes)
        self.assertNotIn("### ⚡ Performance", notes)  # Empty section omitted
        self.assertNotIn("### 🔒 Security", notes)     # Empty section omitted
        self.assertIn("### 📦 Verified Platform Packages", notes)
        self.assertIn("https://github.com/rahulmahasetha/dexolink/compare/v4.0.1...v4.0.2", notes)


if __name__ == "__main__":
    unittest.main()

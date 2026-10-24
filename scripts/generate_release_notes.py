#!/usr/bin/env python3
"""
generate_release_notes.py

Analyzes git commits between a current tag and its previous tag to generate
clean, categorized release notes for DexoLink GitHub Releases.

Categories:
  - ⚠️ Breaking Changes (when applicable)
  - 🚀 Features
  - 🐛 Bug Fixes
  - ⚡ Performance
  - 🎨 UI/UX
  - 🔒 Security
  - 🛠️ Other Changes

Usage:
  python3 scripts/generate_release_notes.py [--tag TAG] [--prev-tag PREV_TAG] [--output FILE] [--repo OWNER/REPO]
"""

import argparse
import os
import re
import subprocess
import sys
from typing import Dict, List, Optional, Tuple


CONVENTIONAL_TYPES = {
    "feat", "feature",
    "fix", "bugfix", "hotfix", "patch",
    "perf", "performance",
    "ui", "ux", "style",
    "sec", "security",
    "chore", "docs", "doc", "refactor", "test", "tests", "build", "ci", "revert",
}

SCOPE_DISPLAY_MAP = {
    "macos": "macOS",
    "mac": "macOS",
    "ios": "iOS",
    "iphone": "iOS",
    "android": "Android",
    "windows": "Windows",
    "win": "Windows",
    "core": "Core",
    "streaming": "Streaming",
    "stream": "Streaming",
    "mobile": "Mobile",
    "desktop": "Desktop",
    "ui": "UI",
    "ux": "UI/UX",
    "style": "UI/UX",
    "ci": "CI/CD",
    "cicd": "CI/CD",
    "release": "Release",
    "security": "Security",
    "sec": "Security",
    "perf": "Performance",
    "camera": "Camera",
    "clipboard": "Clipboard",
    "webrtc": "WebRTC",
    "api": "API",
    "cli": "CLI",
    "p2p": "P2P",
    "lan": "LAN",
}

SECTION_HEADERS = [
    ("breaking", "### ⚠️ Breaking Changes"),
    ("features", "### 🚀 Features"),
    ("fixes", "### 🐛 Bug Fixes"),
    ("perf", "### ⚡ Performance"),
    ("ui", "### 🎨 UI/UX"),
    ("security", "### 🔒 Security"),
    ("other", "### 🛠️ Other Changes"),
]


class Commit:
    def __init__(self, full_hash: str, short_hash: str, author: str, subject: str, body: str):
        self.full_hash = full_hash.strip()
        self.short_hash = short_hash.strip()
        self.author = author.strip()
        self.subject = subject.strip()
        self.body = body.strip()

        self.category: str = "other"
        self.scope: Optional[str] = None
        self.description: str = ""
        self.is_breaking: bool = False
        self.breaking_note: Optional[str] = None

        self._parse()

    def _parse(self):
        # 1. Check for breaking change markers in commit body
        breaking_regex = re.compile(r"BREAKING[- ]CHANGE:\s*(.+)", re.IGNORECASE)
        body_breaking_match = breaking_regex.search(self.body)
        if body_breaking_match:
            self.is_breaking = True
            self.breaking_note = body_breaking_match.group(1).strip()

        # 2. Check conventional commit structure: type(scope)!: description
        conv_regex = re.compile(
            r"^(?P<type>[a-zA-Z0-9_-]+)(?:\((?P<scope>[^\)]+)\))?(?P<breaking>!)?:\s*(?P<desc>.+)$"
        )
        match = conv_regex.match(self.subject)

        if match and match.group("type").lower() in CONVENTIONAL_TYPES:
            c_type = match.group("type").lower()
            raw_scope = match.group("scope")
            is_bang_breaking = bool(match.group("breaking"))
            raw_desc = match.group("desc").strip()

            if is_bang_breaking:
                self.is_breaking = True

            self.scope = self._format_scope(raw_scope) if raw_scope else None
            self.description = self._clean_description(raw_desc)
            self.category = self._categorize_conventional(c_type, raw_scope, self.description)
        elif match and match.group("type").lower() in SCOPE_DISPLAY_MAP:
            # Matches formats like "Android(core): ...", "Macos(app): ...", or "windows: ..."
            plat = SCOPE_DISPLAY_MAP[match.group("type").lower()]
            raw_scope = match.group("scope")
            is_bang_breaking = bool(match.group("breaking"))
            raw_desc = match.group("desc").strip()

            if is_bang_breaking:
                self.is_breaking = True

            if raw_scope:
                self.scope = f"{plat} ({self._format_scope(raw_scope)})"
            else:
                self.scope = plat

            self.description = self._clean_description(raw_desc)
            self.category = self._categorize_freeform(self.description, self.body)
            if raw_scope and raw_scope.lower() in ["ui", "ux", "style"]:
                self.category = "ui"
            elif raw_scope and raw_scope.lower() in ["perf", "performance"]:
                self.category = "perf"
            elif raw_scope and raw_scope.lower() in ["sec", "security"]:
                self.category = "security"
        else:
            # Check for non-conventional prefix like "windows: description" or "ios: description"
            prefix_scope_match = re.match(r"^(?P<scope>[a-zA-Z0-9_-]+):\s*(?P<desc>.+)$", self.subject)
            if prefix_scope_match and prefix_scope_match.group("scope").lower() in SCOPE_DISPLAY_MAP:
                raw_scope = prefix_scope_match.group("scope")
                self.scope = self._format_scope(raw_scope)
                raw_desc = prefix_scope_match.group("desc").strip()
                self.description = self._clean_description(raw_desc)
            else:
                self.scope = None
                self.description = self._clean_description(self.subject)

            # Categorize free-form / scope-prefixed commits
            self.category = self._categorize_freeform(self.description, self.body)

        # Fallback breaking check in subject
        if not self.is_breaking and re.search(r"\bbreaking\b", self.subject, re.IGNORECASE):
            self.is_breaking = True

        if self.is_breaking and not self.breaking_note:
            self.breaking_note = self.description

    def _format_scope(self, raw_scope: str) -> str:
        s = raw_scope.strip().lower()
        if s in SCOPE_DISPLAY_MAP:
            return SCOPE_DISPLAY_MAP[s]
        parts = re.split(r"[-_]", s)
        return " ".join(p.capitalize() for p in parts)

    def _clean_description(self, desc: str) -> str:
        d = desc.strip()
        if not d:
            return ""
        # Capitalize first letter
        d = d[0].upper() + d[1:]
        # Remove trailing period if present
        if d.endswith(".") and not d.endswith(".."):
            d = d[:-1]
        return d

    def _categorize_conventional(self, c_type: str, raw_scope: Optional[str], desc: str) -> str:
        scope_str = (raw_scope or "").lower()
        desc_lower = desc.lower()

        # 1. Security (highest priority)
        if c_type in ["sec", "security"] or any(
            k in desc_lower for k in ["vulnerability", "cve", "auth bypass", "sanitiz", "security patch"]
        ):
            return "security"

        # 2. Performance (takes precedence over scope)
        if c_type in ["perf", "performance"]:
            return "perf"

        # 3. Bug Fixes (bug fixes to UI remain in Bug Fixes with scope UI)
        if c_type in ["fix", "bugfix", "hotfix", "patch"]:
            return "fixes"

        # 4. UI/UX
        if c_type in ["ui", "style", "ux"]:
            return "ui"
        if scope_str in ["ui", "ux", "style", "design", "theme"]:
            return "ui"

        # 5. Features
        if c_type in ["feat", "feature"]:
            return "features"

        # 6. Other (chore, docs, refactor, test, build, ci, revert, etc.)
        return "other"

    def _categorize_freeform(self, desc: str, body: str) -> str:
        s_lower = desc.lower()
        b_lower = body.lower()

        # 1. Security
        if any(k in s_lower or k in b_lower for k in ["security", "vulnerability", "cve", "auth bypass"]):
            return "security"

        # 2. Performance
        if re.search(r"\b(optimize|optimizing|performance|speed up|reduce latency|reduce memory)\b", s_lower):
            return "perf"

        # 3. Bug Fixes (prioritized if starts with fix/resolve)
        if re.match(r"^(fix|fixed|resolve|resolved|patch|prevent|prevented|correct|corrected)\b", s_lower):
            return "fixes"

        # 4. Build, maintenance, or test commits
        if re.match(r"^(build|package|bump|sync|chore|docs|doc|ci|test|tests)\b", s_lower):
            return "other"

        # 5. UI/UX (use word boundary to avoid false matches like 'build' or 'unit')
        if re.search(r"\b(ui|ux|menubar|menu bar|theme|dark mode|screen layout|styling|visual|dialog)\b", s_lower):
            return "ui"

        # 6. Features
        if re.match(r"^(add|added|implement|implemented|integrate|integrated|support|supported|introduce)\b", s_lower):
            # If adding tests/CI/build scripts, categorize as other
            if re.search(r"\b(test|tests|ci|cd|workflow|script|build)\b", s_lower):
                return "other"
            return "features"

        return "other"


def run_git_cmd(args: List[str], cwd: Optional[str] = None) -> Tuple[int, str, str]:
    res = subprocess.run(
        ["git"] + args,
        cwd=cwd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    return res.returncode, res.stdout.strip(), res.stderr.strip()


def detect_repo_slug(provided_repo: Optional[str] = None) -> Optional[str]:
    if provided_repo:
        return provided_repo.strip()
    if os.environ.get("GITHUB_REPOSITORY"):
        return os.environ["GITHUB_REPOSITORY"].strip()

    rc, stdout, _ = run_git_cmd(["remote", "get-url", "origin"])
    if rc == 0 and stdout:
        m = re.search(r"github\.com[:/](?P<owner>[^/]+)/(?P<repo>[^/\.]+)(?:\.git)?", stdout)
        if m:
            return f"{m.group('owner')}/{m.group('repo')}"
    return None


def parse_semver(tag_str: str) -> Optional[Tuple[int, int, int]]:
    m = re.match(r"^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?", tag_str.strip())
    if m:
        return tuple(int(x or 0) for x in m.groups())  # type: ignore
    return None


def resolve_previous_tag(
    current_tag: str, target_ref: Optional[str] = None, explicit_prev: Optional[str] = None
) -> Optional[str]:
    if explicit_prev:
        return explicit_prev.strip()

    commit_ref = target_ref or (current_tag if current_tag != "HEAD" else "HEAD")

    # 1. Semantic versioning resolution: find highest existing tag < current_tag that is an ancestor
    c_ver = parse_semver(current_tag)
    rc, stdout, _ = run_git_cmd(["tag", "--sort=-v:refname"])
    if rc == 0 and stdout and c_ver:
        tags = [t.strip() for t in stdout.splitlines() if t.strip()]
        lower_candidates = []
        for candidate in tags:
            if candidate == current_tag:
                continue
            t_ver = parse_semver(candidate)
            if t_ver and t_ver < c_ver:
                # Check if candidate is reachable / an ancestor of commit_ref
                rc_anc, _, _ = run_git_cmd(["merge-base", "--is-ancestor", candidate, commit_ref])
                if rc_anc == 0:
                    lower_candidates.append((t_ver, candidate))
        if lower_candidates:
            lower_candidates.sort(key=lambda x: x[0], reverse=True)
            return lower_candidates[0][1]

    # 2. Try git describe on parent of commit_ref
    rc, stdout, _ = run_git_cmd(["describe", "--tags", "--abbrev=0", f"{commit_ref}^"])
    if rc == 0 and stdout:
        return stdout.strip()

    # 3. Fallback: find the most recent ancestor tag by creator date
    rc, stdout, _ = run_git_cmd(["tag", "--sort=-creatordate"])
    if rc == 0 and stdout:
        tags = [t.strip() for t in stdout.splitlines() if t.strip()]
        for candidate in tags:
            if candidate == current_tag:
                continue
            rc_anc, _, _ = run_git_cmd(["merge-base", "--is-ancestor", candidate, commit_ref])
            if rc_anc == 0:
                return candidate

    return None


def fetch_commits(current_tag: str, prev_tag: Optional[str]) -> List[Commit]:
    if prev_tag:
        git_range = f"{prev_tag}..{current_tag}"
    else:
        git_range = current_tag

    format_str = "%H%x1f%h%x1f%an%x1f%s%x1f%b%x1e"
    rc, stdout, stderr = run_git_cmd(["log", git_range, "--no-merges", f"--pretty=format:{format_str}"])
    if rc != 0:
        print(f"Warning: git log failed for range '{git_range}': {stderr}", file=sys.stderr)
        return []

    if not stdout:
        return []

    commits: List[Commit] = []
    seen_messages = set()

    records = stdout.split("\x1e")
    for rec in records:
        rec = rec.strip()
        if not rec:
            continue
        parts = rec.split("\x1f")
        if len(parts) >= 4:
            full_hash = parts[0]
            short_hash = parts[1]
            author = parts[2]
            subject = parts[3]
            body = parts[4] if len(parts) > 4 else ""

            # Deduplicate exact duplicate commit subjects
            clean_subj = subject.strip().lower()
            if clean_subj in seen_messages:
                continue
            seen_messages.add(clean_subj)

            commit = Commit(full_hash, short_hash, author, subject, body)
            commits.append(commit)

    return commits


def format_bullet_point(commit: Commit, repo_slug: Optional[str]) -> str:
    # Format PR references #123 -> [#123](...)
    desc = commit.description
    if repo_slug:
        desc = re.sub(
            r"#(\d+)\b",
            rf"[#\1](https://github.com/{repo_slug}/pull/\1)",
            desc,
        )

    # Format commit hash link
    if repo_slug and commit.short_hash:
        commit_link = f"([`{commit.short_hash}`](https://github.com/{repo_slug}/commit/{commit.full_hash}))"
    elif commit.short_hash:
        commit_link = f"(`{commit.short_hash}`)"
    else:
        commit_link = ""

    if commit.scope:
        return f"- **{commit.scope}**: {desc} {commit_link}".strip()
    return f"- {desc} {commit_link}".strip()


def generate_release_notes(
    current_tag: str,
    prev_tag: Optional[str],
    commits: List[Commit],
    repo_slug: Optional[str] = None,
) -> str:
    lines: List[str] = []

    display_tag = current_tag if current_tag != "HEAD" else "Latest Changes"
    lines.append(f"## What's Changed in DexoLink {display_tag}")
    lines.append("")

    # Breaking changes check
    breaking_commits = [c for c in commits if c.is_breaking]
    if breaking_commits:
        lines.append("### ⚠️ Breaking Changes")
        for c in breaking_commits:
            note = c.breaking_note or c.description
            if repo_slug and c.short_hash:
                link = f"([`{c.short_hash}`](https://github.com/{repo_slug}/commit/{c.full_hash}))"
            elif c.short_hash:
                link = f"(`{c.short_hash}`)"
            else:
                link = ""
            scope_prefix = f"**{c.scope}**: " if c.scope else ""
            lines.append(f"- {scope_prefix}{note} {link}".strip())
        lines.append("")

    # Group commits by category
    categorized: Dict[str, List[Commit]] = {
        "features": [],
        "fixes": [],
        "perf": [],
        "ui": [],
        "security": [],
        "other": [],
    }

    for c in commits:
        if c.category in categorized:
            categorized[c.category].append(c)
        else:
            categorized["other"].append(c)

    has_changes = False
    for cat_key, header in SECTION_HEADERS:
        if cat_key == "breaking":
            continue
        items = categorized.get(cat_key, [])
        if items:
            has_changes = True
            lines.append(header)
            for commit in items:
                lines.append(format_bullet_point(commit, repo_slug))
            lines.append("")

    if not has_changes and not breaking_commits:
        lines.append("_No major code changes detected in this release._")
        lines.append("")

    # Standard Platform Artifacts Table
    lines.append("### 📦 Verified Platform Packages")
    lines.append("| Platform | Package / Asset | Target Specification |")
    lines.append("|:---|:---|:---|")
    lines.append("| **macOS** | `DexoLink.dmg` | Universal Binary (Apple Silicon M1–M4 & Intel x64), macOS 12+ |")
    lines.append("| **iOS** | `DexoLink.ipa` | iPhoneOS 16.0+, arm64 deployment package |")
    lines.append("| **Android** | `DexoLink-Android.apk` | Universal APK, Android 8.0+ (API 26+) |")
    lines.append("| **Windows** | `DexoLink.exe` | Standalone executable, Windows 10/11 x64 |")
    lines.append("")

    # Comparison / Full Diff link
    if prev_tag and repo_slug:
        lines.append("---")
        lines.append(
            f"**Full Changelog**: https://github.com/{repo_slug}/compare/{prev_tag}...{current_tag}"
        )
        lines.append("")
    elif prev_tag:
        lines.append("---")
        lines.append(f"**Comparing against previous version**: `{prev_tag}`")
        lines.append("")

    return "\n".join(lines)


def check_ref_exists(ref: str) -> bool:
    rc, _, _ = run_git_cmd(["rev-parse", "--verify", f"{ref}^{{commit}}"])
    return rc == 0


def main():
    parser = argparse.ArgumentParser(
        description="Generate clean, categorized release notes for DexoLink GitHub Releases."
    )
    parser.add_argument("--tag", default="HEAD", help="Current release tag (defaults to HEAD)")
    parser.add_argument("--prev-tag", default=None, help="Previous release tag to compare from")
    parser.add_argument("--output", "-o", default=None, help="Output file path (e.g., release_notes.md)")
    parser.add_argument("--repo", default=None, help="GitHub repository slug (e.g., owner/repo)")

    args = parser.parse_args()

    current_tag = args.tag.strip()
    target_ref = current_tag
    if not check_ref_exists(current_tag):
        print(
            f"Note: Git ref '{current_tag}' not found in local repository; using 'HEAD' as target commit ref.",
            file=sys.stderr,
        )
        target_ref = "HEAD"

    repo_slug = detect_repo_slug(args.repo)
    prev_tag = resolve_previous_tag(current_tag, target_ref, args.prev_tag)

    print(f"Generating release notes for: {current_tag}", file=sys.stderr)
    if prev_tag:
        print(f"Comparing against previous tag: {prev_tag}", file=sys.stderr)
    else:
        print("No previous tag found. Generating changelog for all commits up to target ref.", file=sys.stderr)
    if repo_slug:
        print(f"GitHub repository: {repo_slug}", file=sys.stderr)

    commits = fetch_commits(target_ref, prev_tag)
    print(f"Found {len(commits)} commits to process.", file=sys.stderr)

    notes = generate_release_notes(current_tag, prev_tag, commits, repo_slug)

    if args.output:
        out_path = os.path.abspath(args.output)
        with open(out_path, "w", encoding="utf-8") as f:
            f.write(notes)
        print(f"Release notes written successfully to: {out_path}", file=sys.stderr)
    else:
        print(notes)


if __name__ == "__main__":
    main()

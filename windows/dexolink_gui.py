"""
DexoLink Windows Desktop App (v0.2)
Modern, lightweight, easy-to-use GUI for Windows using Python standard library (Tkinter/ttk).
Zero external dependencies required.
"""

import sys
import os
import time
import threading
import uuid
import base64
import io
import tkinter as tk
from tkinter import ttk, filedialog, messagebox
from typing import Optional, Dict, Any

try:
    from PIL import Image, ImageTk
    HAS_PIL = True
except ImportError:
    HAS_PIL = False

from dexolink_windows import DexoLinkWindowsEngine, TrustStore

# Modern Dark Theme Palette
COLOR_BG = "#0F172A"         # Deep Slate Dark
COLOR_CARD = "#1E293B"       # Card Surface
COLOR_CARD_HOVER = "#334155" # Card Highlight
COLOR_ACCENT = "#0066FF"     # DexoLink Primary Blue
COLOR_ACCENT_HOVER = "#1D4ED8"
COLOR_CYAN = "#38BDF8"       # Secondary Cyan
COLOR_TEXT = "#F8FAFC"       # Light Text
COLOR_MUTED = "#94A3B8"      # Gray/Muted Text
COLOR_SUCCESS = "#22C55E"    # Green
COLOR_DANGER = "#EF4444"     # Red
COLOR_BORDER = "#334155"

class ScreenViewerWindow(tk.Toplevel):
    """Full-featured live screen and camera viewing window for Windows."""
    def __init__(self, master, stream_id: str, engine, device_name: str = "Remote Device"):
        super().__init__(master)
        self.stream_id = stream_id
        self.engine = engine
        self.device_name = device_name
        self.is_camera = ("camera" in stream_id.lower() or "cam" in stream_id.lower())
        title_prefix = "DexoLink Live Camera" if self.is_camera else "DexoLink Screen Mirroring"
        self.title(f"{title_prefix} - {device_name}")
        self.geometry("960x600")
        self.minsize(540, 380)
        self.configure(bg="#000000")

        self.rotation_angle = 0
        self.frame_count = 0
        self.last_fps_time = time.time()
        self.current_fps = 0
        self.current_photo = None

        self._build_ui()
        self.protocol("WM_DELETE_WINDOW", self._on_close)

    def _build_ui(self):
        # Top status bar
        self.header_frame = tk.Frame(self, bg="#0F172A", height=42, padx=12, pady=6)
        self.header_frame.pack(side=tk.TOP, fill=tk.X)

        badge_text = "LIVE CAMERA" if self.is_camera else "LIVE MIRROR"
        badge_fg = "#EF4444" if self.is_camera else "#22C55E"
        lbl_live = tk.Label(self.header_frame, text=badge_text, font=("Segoe UI", 9, "bold"), fg=badge_fg, bg="#0F172A")
        lbl_live.pack(side=tk.LEFT, padx=(4, 10))

        self.lbl_title = tk.Label(self.header_frame, text=self.device_name, font=("Segoe UI", 10, "bold"), fg="#F8FAFC", bg="#0F172A")
        self.lbl_title.pack(side=tk.LEFT, padx=4)

        btn_stop = tk.Button(self.header_frame, text="Stop Feed", font=("Segoe UI", 9, "bold"), fg="#FFFFFF", bg="#DC2626", activebackground="#B91C1C", activeforeground="#FFFFFF", relief=tk.FLAT, padx=10, pady=2, command=self._on_close)
        btn_stop.pack(side=tk.RIGHT, padx=4)

        # Route Orientation Cycle Button
        self.btn_route = tk.Button(self.header_frame, text="Route: Up (0 deg)", font=("Segoe UI", 9, "bold"), fg="#38BDF8", bg="#0F172A", activebackground="#1E293B", activeforeground="#38BDF8", relief=tk.FLAT, padx=8, pady=2, command=self._route_orientation)
        self.btn_route.pack(side=tk.RIGHT, padx=4)

        # Flip Camera button: only for mobile devices
        is_laptop = any(k in self.device_name.lower() for k in ["mac", "windows", "pc", "laptop", "desktop"])
        if not is_laptop:
            btn_flip = tk.Button(self.header_frame, text="Flip Camera", font=("Segoe UI", 9, "bold"), fg="#A855F7", bg="#0F172A", activebackground="#1E293B", activeforeground="#A855F7", relief=tk.FLAT, padx=8, pady=2, command=self._flip_camera)
            btn_flip.pack(side=tk.RIGHT, padx=4)

        self.lbl_fps = tk.Label(self.header_frame, text="0 FPS", font=("Segoe UI", 9, "bold"), fg="#22C55E", bg="#0F172A")
        self.lbl_fps.pack(side=tk.RIGHT, padx=10)

        self.lbl_res = tk.Label(self.header_frame, text="640 x 480", font=("Segoe UI", 9), fg="#94A3B8", bg="#0F172A")
        self.lbl_res.pack(side=tk.RIGHT, padx=4)

        # Bottom Directional Route Dock (Up, Right, Down, Left)
        dock = tk.Frame(self, bg="#0F172A", height=32, padx=12, pady=4)
        dock.pack(side=tk.BOTTOM, fill=tk.X)
        tk.Label(dock, text="Route Orientation:", font=("Segoe UI", 8, "bold"), fg="#94A3B8", bg="#0F172A").pack(side=tk.LEFT, padx=(4, 8))
        for deg, label in [(0, "Up (0 deg)"), (90, "Right (90 deg)"), (180, "Down (180 deg)"), (270, "Left (270 deg)")]:
            b = tk.Button(dock, text=label, font=("Segoe UI", 8), fg="#38BDF8", bg="#1E293B",
                          activebackground="#334155", activeforeground="#F8FAFC", relief=tk.FLAT, padx=6, pady=2,
                          command=lambda d=deg: self.set_route_direction(d))
            b.pack(side=tk.LEFT, padx=3)

        # Canvas for live frame
        self.canvas = tk.Canvas(self, bg="#000000", highlightthickness=0)
        self.canvas.pack(fill=tk.BOTH, expand=True)

    def _route_orientation(self):
        self.rotation_angle = (self.rotation_angle + 90) % 360
        labels = {0: "Up (0 deg)", 90: "Right (90 deg)", 180: "Down (180 deg)", 270: "Left (270 deg)"}
        self.btn_route.config(text=f"Route: {labels.get(self.rotation_angle, '0 deg')}")

    def set_route_direction(self, degrees: int):
        self.rotation_angle = degrees % 360
        labels = {0: "Up (0 deg)", 90: "Right (90 deg)", 180: "Down (180 deg)", 270: "Left (270 deg)"}
        self.btn_route.config(text=f"Route: {labels.get(self.rotation_angle, '0 deg')}")

    def _flip_camera(self):
        try:
            self.engine.send_stream_switch_camera(self.stream_id)
        except Exception:
            pass

    def update_frame(self, data_b64: str, width: int, height: int):
        self.frame_count += 1
        now = time.time()
        elapsed = now - self.last_fps_time
        if elapsed >= 1.0:
            self.current_fps = int(self.frame_count / elapsed)
            self.frame_count = 0
            self.last_fps_time = now
            self.lbl_fps.config(text=f"{self.current_fps} FPS")

        self.lbl_res.config(text=f"{width} x {height}")

        if not HAS_PIL:
            return

        try:
            raw_bytes = base64.b64decode(data_b64)
            img = Image.open(io.BytesIO(raw_bytes))

            if self.rotation_angle != 0:
                img = img.rotate(-self.rotation_angle, expand=True)

            cw = self.canvas.winfo_width()
            ch = self.canvas.winfo_height()
            if cw > 10 and ch > 10:
                scale = min(cw / img.width, ch / img.height)
                new_w = max(int(img.width * scale), 1)
                new_h = max(int(img.height * scale), 1)
                resample_filter = getattr(Image, "Resampling", Image).BILINEAR if hasattr(Image, "Resampling") else Image.BILINEAR
                img = img.resize((new_w, new_h), resample_filter)
                self.current_photo = ImageTk.PhotoImage(img)
                self.canvas.delete("all")
                self.canvas.create_image(cw // 2, ch // 2, anchor=tk.CENTER, image=self.current_photo)
        except Exception:
            pass

    def _on_close(self):
        self.engine.stop_stream(self.stream_id, reason="User closed viewer")
        self.destroy()

class DexoLinkGUI:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title("DexoLink v0.2 — Seamless Cross-Device Sync")
        self.root.geometry("780x620")
        self.root.minsize(700, 560)
        self.root.configure(bg=COLOR_BG)

        # Initialize background engine
        self.engine = DexoLinkWindowsEngine()
        self.engine.on_device_discovered = self._on_device_discovered
        self.engine.on_transfer_complete = self._on_transfer_complete
        self.active_viewers = {}
        self.engine.on_frame_received = self._on_stream_frame_received
        self.engine.on_stream_stopped = self._on_stream_stopped
        self.engine.start()

        # UI State
        self.selected_target_id = tk.StringVar(value="")
        self.multi_paired_var = tk.BooleanVar(value=self.engine.trust_store.multiple_paired_devices_enabled)
        self.auto_sync_clip_var = tk.BooleanVar(value=self.engine.clipboard_sync_enabled)
        self.enhanced_clip_var = tk.BooleanVar(value=self.engine.enhanced_clipboard_enabled)
        self.continuity_var = tk.BooleanVar(value=self.engine.continuity_enabled)
        self.auto_discovery_var = tk.BooleanVar(value=self.engine.auto_discovery_enabled)
        self.auto_accept_var = tk.BooleanVar(value=self.engine.auto_accept_enabled)
        # v4.0.2: Media Streaming & Remote Control
        self.camera_sharing_var = tk.BooleanVar(value=self.engine.camera_sharing_enabled)
        self.screen_sharing_var = tk.BooleanVar(value=self.engine.screen_sharing_enabled)
        self.desktop_mode_var = tk.BooleanVar(value=self.engine.desktop_mode_enabled)
        self.clipboard_history = []
        self.transfers_history = []

        self._configure_styles()
        self._build_header()
        self._build_notebook()
        self._build_statusbar()

        # Start background UI update loop
        self.root.after(1000, self._periodic_ui_refresh)
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

    def _configure_styles(self):
        style = ttk.Style()
        style.theme_use("clam")
        style.configure(".", background=COLOR_BG, foreground=COLOR_TEXT, font=("Segoe UI", 10))
        style.configure("TNotebook", background=COLOR_BG, borderwidth=0)
        style.configure("TNotebook.Tab", background=COLOR_CARD, foreground=COLOR_MUTED, padding=[16, 8], font=("Segoe UI", 10, "bold"))
        style.map("TNotebook.Tab", background=[("selected", COLOR_ACCENT)], foreground=[("selected", COLOR_TEXT)])
        style.configure("TFrame", background=COLOR_BG)
        style.configure("Card.TFrame", background=COLOR_CARD, relief="flat")
        style.configure("TLabel", background=COLOR_BG, foreground=COLOR_TEXT)
        style.configure("Card.TLabel", background=COLOR_CARD, foreground=COLOR_TEXT)
        style.configure("Muted.TLabel", background=COLOR_CARD, foreground=COLOR_MUTED, font=("Segoe UI", 9))
        style.configure("TProgressbar", thickness=10, troughcolor=COLOR_CARD, background=COLOR_ACCENT)

    def _build_header(self):
        header = tk.Frame(self.root, bg=COLOR_BG, pady=12, padx=16)
        header.pack(fill=tk.X)

        # Logo / Title
        title_box = tk.Frame(header, bg=COLOR_BG)
        title_box.pack(side=tk.LEFT)
        
        lbl_brand = tk.Label(title_box, text="DexoLink", font=("Segoe UI", 16, "bold"), fg=COLOR_CYAN, bg=COLOR_BG)
        lbl_brand.pack(side=tk.LEFT)
        
        lbl_ver = tk.Label(title_box, text="v0.2", font=("Segoe UI", 9, "bold"), fg=COLOR_MUTED, bg=COLOR_BG, padx=8)
        lbl_ver.pack(side=tk.LEFT, pady=(4, 0))

        # Status badge & compact Settings button
        header_actions = tk.Frame(header, bg=COLOR_BG)
        header_actions.pack(side=tk.RIGHT)

        self.lbl_status = tk.Label(header_actions, text="Local Network Active", font=("Segoe UI", 10, "bold"), fg=COLOR_SUCCESS, bg=COLOR_BG)
        self.lbl_status.pack(side=tk.LEFT, padx=(0, 10))

        btn_settings = tk.Button(header_actions, text="Settings", bg=COLOR_CARD, fg=COLOR_TEXT,
                                 activebackground=COLOR_BORDER, activeforeground=COLOR_TEXT,
                                 font=("Segoe UI", 9, "bold"), relief="flat", padx=10, pady=4, cursor="hand2",
                                 highlightbackground=COLOR_BORDER, highlightthickness=1,
                                 command=self._open_settings_modal)
        btn_settings.pack(side=tk.LEFT)

    def _build_notebook(self):
        self.notebook = ttk.Notebook(self.root)
        self.notebook.pack(fill=tk.BOTH, expand=True, padx=14, pady=4)

        # Tabs
        self.tab_transfer = ttk.Frame(self.notebook)
        self.tab_clipboard = ttk.Frame(self.notebook)
        self.tab_continuity = ttk.Frame(self.notebook)
        self.tab_settings = ttk.Frame(self.notebook)

        self.notebook.add(self.tab_transfer, text="Send Files")
        self.notebook.add(self.tab_clipboard, text="Clipboard")
        self.notebook.add(self.tab_continuity, text="Continuity")
        self.notebook.add(self.tab_settings, text="Devices & Settings")

        self._init_transfer_tab()
        self._init_clipboard_tab()
        self._init_continuity_tab()
        self._init_settings_tab()

    # --- TAB 1: FILE TRANSFER ---
    def _init_transfer_tab(self):
        container = tk.Frame(self.tab_transfer, bg=COLOR_BG, padx=16, pady=16)
        container.pack(fill=tk.BOTH, expand=True)

        # Destination card
        dest_card = tk.Frame(container, bg=COLOR_CARD, padx=14, pady=12, highlightbackground=COLOR_BORDER, highlightthickness=1)
        dest_card.pack(fill=tk.X, pady=(0, 14))

        tk.Label(dest_card, text="Target Device", font=("Segoe UI", 11, "bold"), fg=COLOR_TEXT, bg=COLOR_CARD).pack(anchor=tk.W)
        self.device_combo = ttk.Combobox(dest_card, state="readonly", font=("Segoe UI", 10))
        self.device_combo.pack(fill=tk.X, pady=(6, 4))
        self.device_combo.set("Scanning for local devices...")

        # Quick Media Streaming Actions (v4.0.2)
        media_btn_box = tk.Frame(dest_card, bg=COLOR_CARD)
        media_btn_box.pack(fill=tk.X, pady=(6, 2))

        btn_cam = tk.Button(media_btn_box, text="Camera Stream", bg=COLOR_BG, fg=COLOR_TEXT,
                            font=("Segoe UI", 9, "bold"), relief="flat", padx=8, pady=4, cursor="hand2",
                            highlightbackground=COLOR_BORDER, highlightthickness=1, command=self._request_camera_stream)
        btn_cam.pack(side=tk.LEFT, padx=(0, 6))

        btn_screen = tk.Button(media_btn_box, text="Screen Mirror", bg=COLOR_BG, fg=COLOR_TEXT,
                               font=("Segoe UI", 9, "bold"), relief="flat", padx=8, pady=4, cursor="hand2",
                               highlightbackground=COLOR_BORDER, highlightthickness=1, command=self._request_screen_stream)
        btn_screen.pack(side=tk.LEFT, padx=(0, 6))

        btn_desk = tk.Button(media_btn_box, text="Desktop Mode", bg=COLOR_BG, fg=COLOR_TEXT,
                             font=("Segoe UI", 9, "bold"), relief="flat", padx=8, pady=4, cursor="hand2",
                             highlightbackground=COLOR_BORDER, highlightthickness=1, command=self._request_desktop_stream)
        btn_desk.pack(side=tk.LEFT)

        # Action send button
        btn_box = tk.Frame(container, bg=COLOR_BG)
        btn_box.pack(fill=tk.X, pady=(0, 10))

        btn_send = tk.Button(btn_box, text="Select File to Send", bg=COLOR_ACCENT, fg=COLOR_TEXT, activebackground=COLOR_ACCENT_HOVER,
                             font=("Segoe UI", 11, "bold"), relief="flat", padx=16, pady=10, cursor="hand2", command=self._choose_and_send_file)
        btn_send.pack(fill=tk.X)

        # Progress bar
        self.progress_var = tk.DoubleVar(value=0.0)
        self.progress_bar = ttk.Progressbar(container, variable=self.progress_var, maximum=100)
        self.progress_bar.pack(fill=tk.X, pady=(4, 6))

        self.lbl_progress = tk.Label(container, text="Ready to send or receive files over local WiFi.", fg=COLOR_MUTED, bg=COLOR_BG, font=("Segoe UI", 9))
        self.lbl_progress.pack(anchor=tk.W)

        # Transfer History
        lbl_hist = tk.Label(container, text="Recent Transfers", font=("Segoe UI", 11, "bold"), fg=COLOR_TEXT, bg=COLOR_BG)
        lbl_hist.pack(anchor=tk.W, pady=(16, 6))

        hist_frame = tk.Frame(container, bg=COLOR_CARD, highlightbackground=COLOR_BORDER, highlightthickness=1)
        hist_frame.pack(fill=tk.BOTH, expand=True)

        self.transfer_listbox = tk.Listbox(hist_frame, bg=COLOR_CARD, fg=COLOR_TEXT, borderwidth=0, highlightthickness=0, font=("Segoe UI", 9))
        self.transfer_listbox.pack(fill=tk.BOTH, expand=True, padx=8, pady=8)

        # Open downloads folder button
        btn_open_folder = tk.Button(container, text="Open Received Downloads Folder", bg=COLOR_CARD, fg=COLOR_CYAN,
                                    font=("Segoe UI", 9, "bold"), relief="flat", padx=10, pady=6, cursor="hand2", command=self._open_downloads)
        btn_open_folder.pack(anchor=tk.E, pady=(8, 0))

    def _choose_and_send_file(self):
        fpath = filedialog.askopenfilename(title="Select file to send with DexoLink")
        if not fpath:
            return
        
        target_name = self.device_combo.get()
        target_dev = None
        for dev_id, dev in self.engine.discovered_devices.items():
            if f"{dev['deviceName']} ({dev['ip']})" == target_name:
                target_dev = dev
                break
        
        if not target_dev:
            messagebox.showwarning("No Target", "Please select a discovered target device from the dropdown first.")
            return

        def _do_send():
            self.lbl_progress.config(text=f"Sending {os.path.basename(fpath)} to {target_dev['deviceName']}...")
            self.progress_var.set(10)
            def _prog(name, sent, total):
                if total > 0:
                    pct = (sent / total) * 100
                    self.root.after(0, lambda: self.progress_var.set(pct))
                    self.root.after(0, lambda: self.lbl_progress.config(text=f"Sending {name}: {int(pct)}% ({sent // 1024} KB / {total // 1024} KB)"))

            success = self.engine.send_file(target_dev["ip"], target_dev["port"], fpath, progress_callback=_prog)
            if success:
                self.root.after(0, lambda: self.lbl_progress.config(text=f"Successfully sent {os.path.basename(fpath)}!"))
                self.root.after(0, lambda: self.transfer_listbox.insert(0, f"Sent: {os.path.basename(fpath)} -> {target_dev['deviceName']} (Verified)"))
                self.root.after(0, lambda: self.progress_var.set(100))
            else:
                self.root.after(0, lambda: self.lbl_progress.config(text=f"Transfer failed to {target_dev['deviceName']}."))
                self.root.after(0, lambda: self.progress_var.set(0))

        threading.Thread(target=_do_send, daemon=True).start()

    def _open_downloads(self):
        if sys.platform == "win32":
            os.startfile(self.engine.download_dir)
        else:
            import subprocess
            subprocess.Popen(["open", self.engine.download_dir])

    # --- TAB 2: CLIPBOARD ---
    def _init_clipboard_tab(self):
        container = tk.Frame(self.tab_clipboard, bg=COLOR_BG, padx=16, pady=16)
        container.pack(fill=tk.BOTH, expand=True)

        # Quick push box
        card_push = tk.Frame(container, bg=COLOR_CARD, padx=14, pady=12, highlightbackground=COLOR_BORDER, highlightthickness=1)
        card_push.pack(fill=tk.X, pady=(0, 14))

        tk.Label(card_push, text="Push Text to Connected Devices", font=("Segoe UI", 11, "bold"), fg=COLOR_TEXT, bg=COLOR_CARD).pack(anchor=tk.W)
        self.txt_push = tk.Entry(card_push, font=("Segoe UI", 10), bg=COLOR_BG, fg=COLOR_TEXT, insertbackground=COLOR_TEXT)
        self.txt_push.pack(fill=tk.X, pady=(8, 8))

        btn_push = tk.Button(card_push, text="Send to All Paired Devices", bg=COLOR_ACCENT, fg=COLOR_TEXT,
                             font=("Segoe UI", 9, "bold"), relief="flat", padx=12, pady=6, cursor="hand2", command=self._push_text_clipboard)
        btn_push.pack(anchor=tk.E)

        # Options
        opt_box = tk.Frame(container, bg=COLOR_BG)
        opt_box.pack(fill=tk.X, pady=(0, 10))

        chk_auto = tk.Checkbutton(opt_box, text="Auto Clipboard Sync (Bidirectional)", variable=self.auto_sync_clip_var,
                                  fg=COLOR_TEXT, bg=COLOR_BG, selectcolor=COLOR_CARD, activebackground=COLOR_BG, font=("Segoe UI", 10))
        chk_auto.pack(side=tk.LEFT)

        chk_enhanced = tk.Checkbutton(opt_box, text="Enhanced Clipboard (Sanitize & HTML)", variable=self.enhanced_clip_var,
                                      fg=COLOR_TEXT, bg=COLOR_BG, selectcolor=COLOR_CARD, activebackground=COLOR_BG, font=("Segoe UI", 10))
        chk_enhanced.pack(side=tk.RIGHT)

        # Clipboard History
        lbl_clip_hist = tk.Label(container, text="Recent Clipboard Items", font=("Segoe UI", 11, "bold"), fg=COLOR_TEXT, bg=COLOR_BG)
        lbl_clip_hist.pack(anchor=tk.W, pady=(12, 6))

        clip_frame = tk.Frame(container, bg=COLOR_CARD, highlightbackground=COLOR_BORDER, highlightthickness=1)
        clip_frame.pack(fill=tk.BOTH, expand=True)

        self.clip_listbox = tk.Listbox(clip_frame, bg=COLOR_CARD, fg=COLOR_TEXT, borderwidth=0, highlightthickness=0, font=("Segoe UI", 9))
        self.clip_listbox.pack(fill=tk.BOTH, expand=True, padx=8, pady=8)

        # Copy selected button
        btn_copy = tk.Button(container, text="Copy Selected to Clipboard", bg=COLOR_CARD, fg=COLOR_CYAN,
                             font=("Segoe UI", 9, "bold"), relief="flat", padx=10, pady=6, cursor="hand2", command=self._copy_selected_clipboard)
        btn_copy.pack(anchor=tk.E, pady=(8, 0))

    def _push_text_clipboard(self):
        text = self.txt_push.get().strip()
        if not text:
            return
        self.engine.push_clipboard(text)
        self.clip_listbox.insert(0, f"Sent: {text[:50]}")
        self.txt_push.delete(0, tk.END)

    def _copy_selected_clipboard(self):
        sel = self.clip_listbox.curselection()
        if sel:
            item = self.clip_listbox.get(sel[0])
            content = item.split(": ", 1)[-1]
            self.root.clipboard_clear()
            self.root.clipboard_append(content)
            messagebox.showinfo("Copied", "Text copied to your Windows clipboard!")

    # --- TAB 3: CONTINUITY ---
    def _init_continuity_tab(self):
        container = tk.Frame(self.tab_continuity, bg=COLOR_BG, padx=16, pady=16)
        container.pack(fill=tk.BOTH, expand=True)

        card_cont = tk.Frame(container, bg=COLOR_CARD, padx=14, pady=14, highlightbackground=COLOR_BORDER, highlightthickness=1)
        card_cont.pack(fill=tk.X, pady=(0, 14))

        tk.Label(card_cont, text="Send Web Page to Paired Phone / Mac", font=("Segoe UI", 11, "bold"), fg=COLOR_TEXT, bg=COLOR_CARD).pack(anchor=tk.W)
        tk.Label(card_cont, text="Instant Continuity / Handoff across devices without cables or cloud accounts.", font=("Segoe UI", 9), fg=COLOR_MUTED, bg=COLOR_CARD).pack(anchor=tk.W, pady=(2, 8))

        self.txt_url = tk.Entry(card_cont, font=("Segoe UI", 10), bg=COLOR_BG, fg=COLOR_TEXT, insertbackground=COLOR_TEXT)
        self.txt_url.insert(0, "https://")
        self.txt_url.pack(fill=tk.X, pady=(0, 10))

        btn_cont = tk.Button(card_cont, text="Launch on Device", bg=COLOR_ACCENT, fg=COLOR_TEXT,
                             font=("Segoe UI", 10, "bold"), relief="flat", padx=14, pady=8, cursor="hand2", command=self._send_continuity)
        btn_cont.pack(anchor=tk.E)

        # Incoming Activity Log
        lbl_act = tk.Label(container, text="Handoff Activity Log", font=("Segoe UI", 11, "bold"), fg=COLOR_TEXT, bg=COLOR_BG)
        lbl_act.pack(anchor=tk.W, pady=(16, 6))

        act_frame = tk.Frame(container, bg=COLOR_CARD, highlightbackground=COLOR_BORDER, highlightthickness=1)
        act_frame.pack(fill=tk.BOTH, expand=True)

        self.act_listbox = tk.Listbox(act_frame, bg=COLOR_CARD, fg=COLOR_TEXT, borderwidth=0, highlightthickness=0, font=("Segoe UI", 9))
        self.act_listbox.pack(fill=tk.BOTH, expand=True, padx=8, pady=8)

    def _send_continuity(self):
        url = self.txt_url.get().strip()
        if not url or url == "https://":
            return
        self.engine.send_continuity_url(url)
        self.act_listbox.insert(0, f"Handoff Sent: {url}")
        messagebox.showinfo("Continuity Sent", f"Sent URL to connected devices:\n{url}")

    def get_active_features_count(self) -> int:
        count = 0
        if self.continuity_var.get(): count += 1
        if self.auto_sync_clip_var.get(): count += 1
        if self.enhanced_clip_var.get(): count += 1
        if self.multi_paired_var.get(): count += 1
        if self.camera_sharing_var.get(): count += 1
        if self.screen_sharing_var.get(): count += 1
        if self.desktop_mode_var.get(): count += 1
        return count

    # --- TAB 4: SETTINGS & DEVICES ---
    def _init_settings_tab(self):
        container = tk.Frame(self.tab_settings, bg=COLOR_BG, padx=16, pady=16)
        container.pack(fill=tk.BOTH, expand=True)

        # Feature Controls Card (Matching Reference Design: Icon, Title, Active count badge, Configure button)
        card_fc = tk.Frame(container, bg=COLOR_CARD, padx=14, pady=10, highlightbackground=COLOR_BORDER, highlightthickness=1)
        card_fc.pack(fill=tk.X, pady=(0, 14))

        fc_left = tk.Frame(card_fc, bg=COLOR_CARD)
        fc_left.pack(side=tk.LEFT, fill=tk.Y)

        # Blue squircle icon container
        fc_icon_box = tk.Frame(fc_left, bg="#1E3A8A", padx=6, pady=4)
        fc_icon_box.pack(side=tk.LEFT, padx=(0, 10))
        tk.Label(fc_icon_box, text="FC", font=("Segoe UI", 9, "bold"), bg="#1E3A8A", fg="#60A5FA").pack()

        # Title and dynamic Active count badge (no feature names)
        tk.Label(fc_left, text="Feature Controls", font=("Segoe UI", 11, "bold"), fg=COLOR_TEXT, bg=COLOR_CARD).pack(side=tk.LEFT, padx=(0, 8))

        self.lbl_fc_badge = tk.Label(fc_left, text=f"{self.get_active_features_count()} ACTIVE", font=("Segoe UI", 8, "bold"),
                                     fg="#4ADE80", bg="#14532D", padx=6, pady=2)
        self.lbl_fc_badge.pack(side=tk.LEFT)

        # Configure button on the right (Solid Blue matching Image 1)
        btn_fc_config = tk.Button(card_fc, text="Configure", bg=COLOR_ACCENT, fg=COLOR_TEXT,
                                  activebackground=COLOR_ACCENT_HOVER, activeforeground=COLOR_TEXT,
                                  font=("Segoe UI", 9, "bold"), relief="flat", padx=14, pady=5,
                                  cursor="hand2", command=self._open_settings_modal)
        btn_fc_config.pack(side=tk.RIGHT)

        # Multiple Paired Devices Card
        card_multi = tk.Frame(container, bg=COLOR_CARD, padx=14, pady=12, highlightbackground=COLOR_BORDER, highlightthickness=1)
        card_multi.pack(fill=tk.X, pady=(0, 14))

        tk.Label(card_multi, text="Multiple Paired Devices", font=("Segoe UI", 11, "bold"), fg=COLOR_TEXT, bg=COLOR_CARD).pack(anchor=tk.W)
        tk.Label(card_multi, text="OFF by default (strict single device mode). When ON, allows multiple trusted devices to connect simultaneously.",
                 font=("Segoe UI", 9), fg=COLOR_MUTED, bg=COLOR_CARD, wraplength=650, justify=tk.LEFT).pack(anchor=tk.W, pady=(2, 8))

        chk_multi = tk.Checkbutton(card_multi, text="Enable Multiple Paired Devices (OFF by default)", variable=self.multi_paired_var,
                                   command=self._on_toggle_multi_paired, fg=COLOR_TEXT, bg=COLOR_CARD, selectcolor=COLOR_BG,
                                   activebackground=COLOR_CARD, font=("Segoe UI", 10, "bold"))
        chk_multi.pack(anchor=tk.W)

        # Paired Devices List
        lbl_paired = tk.Label(container, text="Paired Trusted Devices", font=("Segoe UI", 11, "bold"), fg=COLOR_TEXT, bg=COLOR_BG)
        lbl_paired.pack(anchor=tk.W, pady=(8, 4))

        paired_box = tk.Frame(container, bg=COLOR_CARD, padx=10, pady=8, highlightbackground=COLOR_BORDER, highlightthickness=1)
        paired_box.pack(fill=tk.BOTH, expand=True, pady=(0, 10))

        self.paired_listbox = tk.Listbox(paired_box, bg=COLOR_CARD, fg=COLOR_TEXT, borderwidth=0, highlightthickness=0, font=("Segoe UI", 9))
        self.paired_listbox.pack(fill=tk.BOTH, expand=True)
        btn_row = tk.Frame(container, bg=COLOR_BG)
        btn_row.pack(fill=tk.X)

        btn_remote = tk.Button(btn_row, text="Connect Remote Online Device...", bg=COLOR_PRIMARY, fg="#FFFFFF",
                               font=("Segoe UI", 9, "bold"), relief="flat", padx=10, pady=6, cursor="hand2", command=self._open_connect_remote_modal)
        btn_remote.pack(side=tk.LEFT)

        btn_unpair = tk.Button(btn_row, text="Unpair Selected Device", bg=COLOR_CARD, fg=COLOR_DANGER,
                               font=("Segoe UI", 9, "bold"), relief="flat", padx=10, pady=6, cursor="hand2", command=self._unpair_selected_device)
        btn_unpair.pack(side=tk.RIGHT)

    def _open_connect_remote_modal(self):
        modal = tk.Toplevel(self.root)
        modal.title("Connect Remote Device / Online IP")
        modal.geometry("380x260")
        modal.configure(bg=COLOR_BG)
        modal.transient(self.root)
        modal.grab_set()

        tk.Label(modal, text="Remote Online Connection", font=("Segoe UI", 12, "bold"), fg=COLOR_TEXT, bg=COLOR_BG).pack(anchor=tk.W, padx=20, pady=(16, 4))
        tk.Label(modal, text="Connect across local Wi-Fi, VPN, or over the Internet with WebRTC STUN/TURN live streaming.", font=("Segoe UI", 8), fg=COLOR_MUTED, bg=COLOR_BG, wraplength=340).pack(anchor=tk.W, padx=20, pady=(0, 12))

        tk.Label(modal, text="Host or IP Address:", font=("Segoe UI", 9, "bold"), fg=COLOR_TEXT, bg=COLOR_BG).pack(anchor=tk.W, padx=20)
        ent_host = tk.Entry(modal, font=("Segoe UI", 10), bg=COLOR_CARD, fg=COLOR_TEXT, highlightthickness=1)
        ent_host.pack(fill=tk.X, padx=20, pady=(2, 8))
        ent_host.focus_set()

        tk.Label(modal, text="Port (Default: 52840):", font=("Segoe UI", 9, "bold"), fg=COLOR_TEXT, bg=COLOR_BG).pack(anchor=tk.W, padx=20)
        ent_port = tk.Entry(modal, font=("Segoe UI", 10), bg=COLOR_CARD, fg=COLOR_TEXT, highlightthickness=1)
        ent_port.insert(0, "52840")
        ent_port.pack(fill=tk.X, padx=20, pady=(2, 12))

        def do_connect():
            h = ent_host.get().strip()
            p = int(ent_port.get().strip() or "52840")
            if h:
                success = self.engine.connect_to_remote(h, p)
                if success:
                    messagebox.showinfo("Connected", f"Connected to {h}:{p}")
                    modal.destroy()
                else:
                    messagebox.showerror("Connection Failed", f"Could not connect to {h}:{p}")

        btn_conn = tk.Button(modal, text="Connect", bg=COLOR_PRIMARY, fg="#FFFFFF", font=("Segoe UI", 9, "bold"), relief="flat", padx=16, pady=6, cursor="hand2", command=do_connect)
        btn_conn.pack(side=tk.RIGHT, padx=20, pady=10)

        btn_cancel = tk.Button(modal, text="Cancel", bg=COLOR_CARD, fg=COLOR_MUTED, font=("Segoe UI", 9), relief="flat", padx=12, pady=6, cursor="hand2", command=modal.destroy)
        btn_cancel.pack(side=tk.RIGHT, pady=10)

    def _open_settings_modal(self):
        modal = tk.Toplevel(self.root)
        modal.title("DexoLink — Settings & Feature Controls")
        modal.geometry("560x620")
        modal.minsize(500, 520)
        modal.configure(bg=COLOR_BG)
        modal.transient(self.root)
        modal.grab_set()

        # Modal Header
        m_head = tk.Frame(modal, bg=COLOR_BG, padx=20, pady=16)
        m_head.pack(fill=tk.X)

        m_title_box = tk.Frame(m_head, bg=COLOR_BG)
        m_title_box.pack(side=tk.LEFT)
        tk.Label(m_title_box, text="Settings & Feature Controls", font=("Segoe UI", 14, "bold"), fg=COLOR_TEXT, bg=COLOR_BG).pack(anchor=tk.W)
        tk.Label(m_title_box, text="Manage all sync features, security policies, and network preferences", font=("Segoe UI", 9), fg=COLOR_MUTED, bg=COLOR_BG).pack(anchor=tk.W, pady=(2, 0))

        # Scrollable Canvas / Card Container
        canvas = tk.Canvas(modal, bg=COLOR_BG, highlightthickness=0)
        scrollbar = ttk.Scrollbar(modal, orient="vertical", command=canvas.yview)
        scrollable_frame = tk.Frame(canvas, bg=COLOR_BG, padx=20, pady=4)

        scrollable_frame.bind(
            "<Configure>",
            lambda e: canvas.configure(scrollregion=canvas.bbox("all"))
        )

        canvas_window = canvas.create_window((0, 0), window=scrollable_frame, anchor="nw")

        def _on_canvas_configure(event):
            canvas.itemconfig(canvas_window, width=event.width)

        canvas.bind("<Configure>", _on_canvas_configure)
        canvas.configure(yscrollcommand=scrollbar.set)

        canvas.pack(side=tk.TOP, fill=tk.BOTH, expand=True)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)

        # 1. CORE FEATURE CONTROLS
        sec_core = tk.Frame(scrollable_frame, bg=COLOR_CARD, padx=14, pady=12, highlightbackground=COLOR_BORDER, highlightthickness=1)
        sec_core.pack(fill=tk.X, pady=(0, 12))

        tk.Label(sec_core, text="CORE FEATURES", font=("Segoe UI", 9, "bold"), fg=COLOR_CYAN, bg=COLOR_CARD).pack(anchor=tk.W, pady=(0, 6))

        # Continuity
        chk_cont = tk.Checkbutton(sec_core, text="Continuity / Handoff", variable=self.continuity_var,
                                  command=self._on_toggle_continuity, fg=COLOR_TEXT, bg=COLOR_CARD, selectcolor=COLOR_BG,
                                  activebackground=COLOR_CARD, font=("Segoe UI", 10, "bold"))
        chk_cont.pack(anchor=tk.W)
        tk.Label(sec_core, text="Seamlessly open web pages and documents across your Mac, iPhone, and Android.",
                 font=("Segoe UI", 8), fg=COLOR_MUTED, bg=COLOR_CARD, padx=24).pack(anchor=tk.W, pady=(0, 8))

        # Auto Clipboard Sync
        chk_clip = tk.Checkbutton(sec_core, text="Auto Clipboard Sync", variable=self.auto_sync_clip_var,
                                  command=self._on_toggle_auto_sync_clip, fg=COLOR_TEXT, bg=COLOR_CARD, selectcolor=COLOR_BG,
                                  activebackground=COLOR_CARD, font=("Segoe UI", 10, "bold"))
        chk_clip.pack(anchor=tk.W)
        tk.Label(sec_core, text="Instantly mirror copied text to all paired devices over secure local WiFi.",
                 font=("Segoe UI", 8), fg=COLOR_MUTED, bg=COLOR_CARD, padx=24).pack(anchor=tk.W, pady=(0, 8))

        # Enhanced Clipboard
        chk_enh = tk.Checkbutton(sec_core, text="Enhanced Clipboard (BETA — Rich Text & Images)", variable=self.enhanced_clip_var,
                                 command=self._on_toggle_enhanced_clip, fg=COLOR_TEXT, bg=COLOR_CARD, selectcolor=COLOR_BG,
                                 activebackground=COLOR_CARD, font=("Segoe UI", 10, "bold"))
        chk_enh.pack(anchor=tk.W)
        tk.Label(sec_core, text="Supports rich formatted text, HTML, and image synchronization across platforms.",
                 font=("Segoe UI", 8), fg=COLOR_MUTED, bg=COLOR_CARD, padx=24).pack(anchor=tk.W, pady=(0, 8))

        # Multiple Paired Devices
        chk_multi = tk.Checkbutton(sec_core, text="Multiple Paired Devices (OFF by default)", variable=self.multi_paired_var,
                                   command=self._on_toggle_multi_paired, fg=COLOR_TEXT, bg=COLOR_CARD, selectcolor=COLOR_BG,
                                   activebackground=COLOR_CARD, font=("Segoe UI", 10, "bold"))
        chk_multi.pack(anchor=tk.W)
        tk.Label(sec_core, text="OFF by default (strict single-device mode). When ON, allows multiple trusted devices to connect simultaneously.",
                 font=("Segoe UI", 8), fg=COLOR_MUTED, bg=COLOR_CARD, padx=24).pack(anchor=tk.W)

        # 2. DISCOVERY & NETWORK
        sec_net = tk.Frame(scrollable_frame, bg=COLOR_CARD, padx=14, pady=12, highlightbackground=COLOR_BORDER, highlightthickness=1)
        sec_net.pack(fill=tk.X, pady=(0, 12))

        tk.Label(sec_net, text="DISCOVERY & NETWORK", font=("Segoe UI", 9, "bold"), fg=COLOR_CYAN, bg=COLOR_CARD).pack(anchor=tk.W, pady=(0, 6))

        chk_disc = tk.Checkbutton(sec_net, text="Auto Device Discovery (UDP Beacons)", variable=self.auto_discovery_var,
                                  command=self._on_toggle_auto_discovery, fg=COLOR_TEXT, bg=COLOR_CARD, selectcolor=COLOR_BG,
                                  activebackground=COLOR_CARD, font=("Segoe UI", 10, "bold"))
        chk_disc.pack(anchor=tk.W)
        tk.Label(sec_net, text="Broadcast and listen on port 52841 for instant Zero-Config peer detection.",
                 font=("Segoe UI", 8), fg=COLOR_MUTED, bg=COLOR_CARD, padx=24).pack(anchor=tk.W, pady=(0, 8))

        chk_acc = tk.Checkbutton(sec_net, text="Auto-Accept from Paired Devices", variable=self.auto_accept_var,
                                 command=self._on_toggle_auto_accept, fg=COLOR_TEXT, bg=COLOR_CARD, selectcolor=COLOR_BG,
                                 activebackground=COLOR_CARD, font=("Segoe UI", 10, "bold"))
        chk_acc.pack(anchor=tk.W)
        tk.Label(sec_net, text="Automatically receive transfers from trusted paired devices without prompting.",
                 font=("Segoe UI", 8), fg=COLOR_MUTED, bg=COLOR_CARD, padx=24).pack(anchor=tk.W)

        # 3. STORAGE & DOWNLOADS
        sec_storage = tk.Frame(scrollable_frame, bg=COLOR_CARD, padx=14, pady=12, highlightbackground=COLOR_BORDER, highlightthickness=1)
        sec_storage.pack(fill=tk.X, pady=(0, 12))

        tk.Label(sec_storage, text="STORAGE & RECEIVED FILES", font=("Segoe UI", 9, "bold"), fg=COLOR_CYAN, bg=COLOR_CARD).pack(anchor=tk.W, pady=(0, 6))
        
        self.lbl_down_path = tk.Label(sec_storage, text=f"Downloads: {self.engine.download_dir}", font=("Segoe UI", 9), fg=COLOR_TEXT, bg=COLOR_CARD)
        self.lbl_down_path.pack(anchor=tk.W, pady=(0, 6))

        storage_btns = tk.Frame(sec_storage, bg=COLOR_CARD)
        storage_btns.pack(fill=tk.X)

        btn_open = tk.Button(storage_btns, text="Open Folder", bg=COLOR_BG, fg=COLOR_CYAN,
                             font=("Segoe UI", 9, "bold"), relief="flat", padx=10, pady=4, cursor="hand2", command=self._open_downloads)
        btn_open.pack(side=tk.LEFT, padx=(0, 8))

        btn_change = tk.Button(storage_btns, text="Change Folder...", bg=COLOR_BG, fg=COLOR_TEXT,
                              font=("Segoe UI", 9), relief="flat", padx=10, pady=4, cursor="hand2", command=self._change_downloads)
        btn_change.pack(side=tk.LEFT)

        # 4. MEDIA STREAMING & REMOTE CONTROL (v4.0.2)
        sec_stream = tk.Frame(scrollable_frame, bg=COLOR_CARD, padx=14, pady=12, highlightbackground=COLOR_BORDER, highlightthickness=1)
        sec_stream.pack(fill=tk.X, pady=(0, 12))

        tk.Label(sec_stream, text="MEDIA STREAMING & REMOTE CONTROL", font=("Segoe UI", 9, "bold"), fg=COLOR_CYAN, bg=COLOR_CARD).pack(anchor=tk.W, pady=(0, 6))

        # Camera Sharing Toggle
        chk_cam = tk.Checkbutton(sec_stream, text="Camera Sharing", variable=self.camera_sharing_var,
                                 command=self._on_toggle_camera_sharing, fg=COLOR_TEXT, bg=COLOR_CARD, selectcolor=COLOR_BG,
                                 activebackground=COLOR_CARD, font=("Segoe UI", 10, "bold"))
        chk_cam.pack(anchor=tk.W)
        tk.Label(sec_stream, text="Stream this device's camera to another supported device (OFF by default).",
                 font=("Segoe UI", 8), fg=COLOR_MUTED, bg=COLOR_CARD, padx=24).pack(anchor=tk.W, pady=(0, 8))

        # Screen Sharing Toggle
        chk_screen = tk.Checkbutton(sec_stream, text="Screen Sharing", variable=self.screen_sharing_var,
                                    command=self._on_toggle_screen_sharing, fg=COLOR_TEXT, bg=COLOR_CARD, selectcolor=COLOR_BG,
                                    activebackground=COLOR_CARD, font=("Segoe UI", 10, "bold"))
        chk_screen.pack(anchor=tk.W)
        tk.Label(sec_stream, text="Mirror this device's screen to another supported device (OFF by default).",
                 font=("Segoe UI", 8), fg=COLOR_MUTED, bg=COLOR_CARD, padx=24).pack(anchor=tk.W, pady=(0, 8))

        # Desktop Mode (beta) Toggle
        chk_desk = tk.Checkbutton(sec_stream, text="Desktop Mode (BETA)", variable=self.desktop_mode_var,
                                  command=self._on_toggle_desktop_mode, fg=COLOR_TEXT, bg=COLOR_CARD, selectcolor=COLOR_BG,
                                  activebackground=COLOR_CARD, font=("Segoe UI", 10, "bold"))
        chk_desk.pack(anchor=tk.W)
        tk.Label(sec_stream, text="Remote desktop viewing and input control where supported (OFF by default).",
                 font=("Segoe UI", 8), fg=COLOR_MUTED, bg=COLOR_CARD, padx=24).pack(anchor=tk.W)

        # 5. FUTURE FEATURE CONTROLS
        sec_future = tk.Frame(scrollable_frame, bg=COLOR_CARD, padx=14, pady=12, highlightbackground=COLOR_BORDER, highlightthickness=1)
        sec_future.pack(fill=tk.X, pady=(0, 12))

        tk.Label(sec_future, text="EXTENSIBLE FEATURE ARCHITECTURE", font=("Segoe UI", 9, "bold"), fg=COLOR_MUTED, bg=COLOR_CARD).pack(anchor=tk.W, pady=(0, 4))
        tk.Label(sec_future, text="New feature toggles and experimental protocols automatically register here cleanly.",
                 font=("Segoe UI", 9), fg=COLOR_MUTED, bg=COLOR_CARD).pack(anchor=tk.W)

        # 6. FOOTER / DONE
        footer = tk.Frame(modal, bg=COLOR_BG, padx=20, pady=14)
        footer.pack(fill=tk.X, side=tk.BOTTOM)

        lbl_info = tk.Label(footer, text="DexoLink v0.2 • Native Engine Active", font=("Segoe UI", 9), fg=COLOR_MUTED, bg=COLOR_BG)
        lbl_info.pack(side=tk.LEFT)

        btn_done = tk.Button(footer, text="Done", bg=COLOR_ACCENT, fg=COLOR_TEXT, activebackground=COLOR_ACCENT_HOVER,
                             font=("Segoe UI", 10, "bold"), relief="flat", padx=20, pady=6, cursor="hand2", command=modal.destroy)
        btn_done.pack(side=tk.RIGHT)

    def _on_toggle_continuity(self):
        self.engine.continuity_enabled = self.continuity_var.get()

    def _on_toggle_auto_sync_clip(self):
        self.engine.clipboard_sync_enabled = self.auto_sync_clip_var.get()

    def _on_toggle_enhanced_clip(self):
        self.engine.enhanced_clipboard_enabled = self.enhanced_clip_var.get()

    def _on_toggle_auto_discovery(self):
        self.engine.auto_discovery_enabled = self.auto_discovery_var.get()

    def _on_toggle_auto_accept(self):
        self.engine.auto_accept_enabled = self.auto_accept_var.get()

    def _on_toggle_camera_sharing(self):
        val = self.camera_sharing_var.get()
        self.engine.set_camera_sharing_enabled(val)
        if val:
            messagebox.showinfo("Camera Sharing", "Camera Sharing is now active.\nNote: Verify camera permission is allowed in Windows Settings > Privacy & security > Camera.")

    def _on_toggle_screen_sharing(self):
        val = self.screen_sharing_var.get()
        self.engine.set_screen_sharing_enabled(val)
        if val:
            messagebox.showinfo("Screen Sharing", "Screen Sharing is now active.\nDisplay mirroring can now stream to connected DexoLink devices.")

    def _on_toggle_desktop_mode(self):
        val = self.desktop_mode_var.get()
        self.engine.set_desktop_mode_enabled(val)
        if val:
            messagebox.showinfo("Desktop Mode (Beta)", "Desktop Mode is now active.\nRemote devices can view and control your desktop with mouse and keyboard inputs via Win32.")

    def _request_camera_stream(self):
        target_name = self.device_combo.get()
        if not target_name or "Scanning" in target_name:
            messagebox.showwarning("No Target", "Please select a discovered target device first.")
            return
        if not self.engine.active_connections:
            messagebox.showwarning("Unverified Device", "No verified connected device. Please connect and pair with a device over Wi-Fi first.")
            return
        if not self.engine.camera_sharing_enabled:
            messagebox.showinfo("Enable Feature", "Please turn ON Camera Sharing in Settings first.")
            return
        stream_id = str(uuid.uuid4())
        self.engine.send_protocol_message({
            "type": "STREAM_REQUEST",
            "streamId": stream_id,
            "streamType": "camera",
            "options": {"direction": "outgoing", "sender": "windows", "senderName": self.engine.device_name, "deviceType": "laptop"}
        })
        messagebox.showinfo("Camera Stream", f"Camera stream requested with {target_name}. Local camera feed is ready.")

    def _request_screen_stream(self):
        target_name = self.device_combo.get()
        if not target_name or "Scanning" in target_name:
            messagebox.showwarning("No Target", "Please select a discovered target device first.")
            return
        if not self.engine.active_connections:
            messagebox.showwarning("Unverified Device", "No verified connected device. Please connect and pair with a device over Wi-Fi first.")
            return
        if not self.engine.screen_sharing_enabled:
            messagebox.showinfo("Enable Feature", "Please turn ON Screen Sharing in Settings first.")
            return
        stream_id = str(uuid.uuid4())
        self.engine.active_streams[stream_id] = {
            "streamId": stream_id,
            "streamType": "screen",
            "direction": "outgoing",
            "startedAt": time.time(),
            "peerId": "remote"
        }
        self.engine.send_protocol_message({
            "type": "STREAM_REQUEST",
            "streamId": stream_id,
            "streamType": "screen",
            "options": {"direction": "outgoing", "sender": "windows", "senderName": self.engine.device_name, "deviceType": "laptop"}
        })
        messagebox.showinfo("Screen Mirroring", f"Screen mirroring started with {target_name}.")

    def _request_desktop_stream(self):
        target_name = self.device_combo.get()
        if not target_name or "Scanning" in target_name:
            messagebox.showwarning("No Target", "Please select a discovered target device first.")
            return
        if not self.engine.active_connections:
            messagebox.showwarning("Unverified Device", "No verified connected device. Please connect and pair with a device over Wi-Fi first.")
            return
        if not self.engine.desktop_mode_enabled:
            messagebox.showinfo("Enable Feature", "Please turn ON Desktop Mode in Settings first.")
            return
        messagebox.showinfo("Desktop Mode (Beta)", f"Desktop remote control session active with {target_name}.")

    def _change_downloads(self):
        new_dir = filedialog.askdirectory(title="Select DexoLink Downloads Directory")
        if new_dir:
            self.engine.download_dir = new_dir
            if hasattr(self, "lbl_down_path"):
                self.lbl_down_path.config(text=f"Downloads: {new_dir}")

    def _on_toggle_multi_paired(self):
        val = self.multi_paired_var.get()
        self.engine.trust_store.set_multiple_paired_devices_enabled(val)
        self._refresh_paired_list()

    def _unpair_selected_device(self):
        sel = self.paired_listbox.curselection()
        if sel:
            item = self.paired_listbox.get(sel[0])
            # Parse dev id
            if "[" in item and "]" in item:
                dev_id = item.split("[")[-1].replace("]", "").strip()
                self.engine.trust_store.remove_device(dev_id)
                self._refresh_paired_list()
                messagebox.showinfo("Unpaired", f"Device {dev_id} unpaired successfully.")

    def _refresh_paired_list(self):
        self.paired_listbox.delete(0, tk.END)
        devices = self.engine.trust_store.get_trusted_devices()
        if not devices:
            self.paired_listbox.insert(tk.END, "No paired devices yet.")
        else:
            for d in devices:
                self.paired_listbox.insert(tk.END, f"{d.get('device_name', 'Device')} [{d.get('device_id', '')}]")

    def _build_statusbar(self):
        status_bar = tk.Frame(self.root, bg=COLOR_CARD, height=28, padx=14)
        status_bar.pack(fill=tk.X, side=tk.BOTTOM)

        lbl_port = tk.Label(status_bar, text=f"Port: {self.engine.port} (TCP/UDP) | Engine: v0.2", font=("Segoe UI", 9), fg=COLOR_MUTED, bg=COLOR_CARD)
        lbl_port.pack(side=tk.LEFT, pady=4)

        self.lbl_connections = tk.Label(status_bar, text="Connected: 0 devices", font=("Segoe UI", 9, "bold"), fg=COLOR_CYAN, bg=COLOR_CARD)
        self.lbl_connections.pack(side=tk.RIGHT, pady=4)

    def _on_device_discovered(self, dev_info):
        self.root.after(0, self._update_discovered_devices)

    def _on_transfer_complete(self, file_name, verified):
        status = "Verified" if verified else "Failed"
        self.root.after(0, lambda: self.transfer_listbox.insert(0, f"Received: {file_name} ({status})"))

    def _update_discovered_devices(self):
        devices = list(self.engine.discovered_devices.values())
        items = [f"{d['deviceName']} ({d['ip']})" for d in devices]
        if items:
            self.device_combo["values"] = items
            if not self.device_combo.get() or self.device_combo.get() == "Scanning for local devices...":
                self.device_combo.current(0)

    def _periodic_ui_refresh(self):
        # Update connections count
        conn_count = len(self.engine.active_connections)
        self.lbl_connections.config(text=f"Connected: {conn_count} device{'s' if conn_count != 1 else ''}")
        self._refresh_paired_list()
        if hasattr(self, 'lbl_fc_badge'):
            self.lbl_fc_badge.config(text=f"{self.get_active_features_count()} ACTIVE")
        self.root.after(2000, self._periodic_ui_refresh)

    def _on_stream_frame_received(self, stream_id: str, data_b64: str, width: int, height: int, fmt: str):
        def _update():
            if stream_id not in self.active_viewers:
                target_name = self.device_combo.get() or "Remote Device"
                viewer = ScreenViewerWindow(self.root, stream_id, self.engine, device_name=target_name)
                self.active_viewers[stream_id] = viewer
            self.active_viewers[stream_id].update_frame(data_b64, width, height)
        self.root.after(0, _update)

    def _on_stream_stopped(self, stream_id: str):
        def _stop():
            viewer = self.active_viewers.pop(stream_id, None)
            if viewer and viewer.winfo_exists():
                viewer.destroy()
        self.root.after(0, _stop)

    def _on_close(self):
        self.engine.stop()
        self.root.destroy()


def main():
    root = tk.Tk()
    app = DexoLinkGUI(root)
    root.mainloop()

if __name__ == "__main__":
    main()

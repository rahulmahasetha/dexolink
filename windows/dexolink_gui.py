"""
DexoLink Windows Desktop App (v4.0.0)
Modern, lightweight, easy-to-use GUI for Windows using Python standard library (Tkinter/ttk).
Zero external dependencies required.
"""

import sys
import os
import time
import threading
import tkinter as tk
from tkinter import ttk, filedialog, messagebox
from typing import Optional, Dict, Any

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

class DexoLinkGUI:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title("DexoLink v4.0.0 — Seamless Cross-Device Sync")
        self.root.geometry("780x620")
        self.root.minsize(700, 560)
        self.root.configure(bg=COLOR_BG)

        # Initialize background engine
        self.engine = DexoLinkWindowsEngine()
        self.engine.on_device_discovered = self._on_device_discovered
        self.engine.on_transfer_complete = self._on_transfer_complete
        self.engine.start()

        # UI State
        self.selected_target_id = tk.StringVar(value="")
        self.multi_paired_var = tk.BooleanVar(value=self.engine.trust_store.multiple_paired_devices_enabled)
        self.auto_sync_clip_var = tk.BooleanVar(value=self.engine.clipboard_sync_enabled)
        self.enhanced_clip_var = tk.BooleanVar(value=self.engine.enhanced_clipboard_enabled)
        self.continuity_var = tk.BooleanVar(value=self.engine.continuity_enabled)
        self.auto_discovery_var = tk.BooleanVar(value=self.engine.auto_discovery_enabled)
        self.auto_accept_var = tk.BooleanVar(value=self.engine.auto_accept_enabled)
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
        
        lbl_brand = tk.Label(title_box, text="⚡ DexoLink", font=("Segoe UI", 16, "bold"), fg=COLOR_CYAN, bg=COLOR_BG)
        lbl_brand.pack(side=tk.LEFT)
        
        lbl_ver = tk.Label(title_box, text="v4.0.0", font=("Segoe UI", 9, "bold"), fg=COLOR_MUTED, bg=COLOR_BG, padx=8)
        lbl_ver.pack(side=tk.LEFT, pady=(4, 0))

        # Status badge & compact Settings button
        header_actions = tk.Frame(header, bg=COLOR_BG)
        header_actions.pack(side=tk.RIGHT)

        self.lbl_status = tk.Label(header_actions, text="● Local Network Active", font=("Segoe UI", 10, "bold"), fg=COLOR_SUCCESS, bg=COLOR_BG)
        self.lbl_status.pack(side=tk.LEFT, padx=(0, 10))

        btn_settings = tk.Button(header_actions, text="⚙️ Settings", bg=COLOR_CARD, fg=COLOR_TEXT,
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

        self.notebook.add(self.tab_transfer, text="📁 Send Files")
        self.notebook.add(self.tab_clipboard, text="📋 Clipboard")
        self.notebook.add(self.tab_continuity, text="🌐 Continuity")
        self.notebook.add(self.tab_settings, text="⚙️ Devices & Settings")

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

        # Action send button
        btn_box = tk.Frame(container, bg=COLOR_BG)
        btn_box.pack(fill=tk.X, pady=(0, 10))

        btn_send = tk.Button(btn_box, text="📤 Select File to Send", bg=COLOR_ACCENT, fg=COLOR_TEXT, activebackground=COLOR_ACCENT_HOVER,
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
        btn_open_folder = tk.Button(container, text="📂 Open Received Downloads Folder", bg=COLOR_CARD, fg=COLOR_CYAN,
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
                self.root.after(0, lambda: self.lbl_progress.config(text=f"✅ Successfully sent {os.path.basename(fpath)}!"))
                self.root.after(0, lambda: self.transfer_listbox.insert(0, f"📤 Sent: {os.path.basename(fpath)} → {target_dev['deviceName']} (Verified)"))
                self.root.after(0, lambda: self.progress_var.set(100))
            else:
                self.root.after(0, lambda: self.lbl_progress.config(text=f"❌ Transfer failed to {target_dev['deviceName']}."))
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

        btn_push = tk.Button(card_push, text="🚀 Send to All Paired Devices", bg=COLOR_ACCENT, fg=COLOR_TEXT,
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
        btn_copy = tk.Button(container, text="📋 Copy Selected to Clipboard", bg=COLOR_CARD, fg=COLOR_CYAN,
                             font=("Segoe UI", 9, "bold"), relief="flat", padx=10, pady=6, cursor="hand2", command=self._copy_selected_clipboard)
        btn_copy.pack(anchor=tk.E, pady=(8, 0))

    def _push_text_clipboard(self):
        text = self.txt_push.get().strip()
        if not text:
            return
        self.engine.push_clipboard(text)
        self.clip_listbox.insert(0, f"📤 Sent: {text[:50]}")
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

        btn_cont = tk.Button(card_cont, text="🌐 Launch on Device", bg=COLOR_ACCENT, fg=COLOR_TEXT,
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
        self.act_listbox.insert(0, f"🌐 Handoff Sent: {url}")
        messagebox.showinfo("Continuity Sent", f"Sent URL to connected devices:\n{url}")

    # --- TAB 4: SETTINGS & DEVICES ---
    def _init_settings_tab(self):
        container = tk.Frame(self.tab_settings, bg=COLOR_BG, padx=16, pady=16)
        container.pack(fill=tk.BOTH, expand=True)

        # Quick Dedicated Settings Launcher
        btn_open_all = tk.Button(container, text="⚙️ Open Dedicated Settings & All Feature Controls", bg=COLOR_CARD, fg=COLOR_CYAN,
                                 activebackground=COLOR_BORDER, font=("Segoe UI", 10, "bold"), relief="flat", padx=12, pady=10,
                                 cursor="hand2", highlightbackground=COLOR_BORDER, highlightthickness=1, command=self._open_settings_modal)
        btn_open_all.pack(fill=tk.X, pady=(0, 14))

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

        btn_unpair = tk.Button(container, text="❌ Unpair Selected Device", bg=COLOR_CARD, fg=COLOR_DANGER,
                               font=("Segoe UI", 9, "bold"), relief="flat", padx=10, pady=6, cursor="hand2", command=self._unpair_selected_device)
        btn_unpair.pack(anchor=tk.E)

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
        tk.Label(m_title_box, text="⚙️ Settings & Feature Controls", font=("Segoe UI", 14, "bold"), fg=COLOR_TEXT, bg=COLOR_BG).pack(anchor=tk.W)
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

        btn_open = tk.Button(storage_btns, text="📂 Open Folder", bg=COLOR_BG, fg=COLOR_CYAN,
                             font=("Segoe UI", 9, "bold"), relief="flat", padx=10, pady=4, cursor="hand2", command=self._open_downloads)
        btn_open.pack(side=tk.LEFT, padx=(0, 8))

        btn_change = tk.Button(storage_btns, text="Change Folder...", bg=COLOR_BG, fg=COLOR_TEXT,
                              font=("Segoe UI", 9), relief="flat", padx=10, pady=4, cursor="hand2", command=self._change_downloads)
        btn_change.pack(side=tk.LEFT)

        # 4. FUTURE FEATURE CONTROLS
        sec_future = tk.Frame(scrollable_frame, bg=COLOR_CARD, padx=14, pady=12, highlightbackground=COLOR_BORDER, highlightthickness=1)
        sec_future.pack(fill=tk.X, pady=(0, 12))

        tk.Label(sec_future, text="EXTENSIBLE FEATURE ARCHITECTURE", font=("Segoe UI", 9, "bold"), fg=COLOR_MUTED, bg=COLOR_CARD).pack(anchor=tk.W, pady=(0, 4))
        tk.Label(sec_future, text="✦ New feature toggles and experimental protocols automatically register here cleanly.",
                 font=("Segoe UI", 9), fg=COLOR_MUTED, bg=COLOR_CARD).pack(anchor=tk.W)

        # 5. FOOTER / DONE
        footer = tk.Frame(modal, bg=COLOR_BG, padx=20, pady=14)
        footer.pack(fill=tk.X, side=tk.BOTTOM)

        lbl_info = tk.Label(footer, text="DexoLink v4.0.0 • Native Engine Active", font=("Segoe UI", 9), fg=COLOR_MUTED, bg=COLOR_BG)
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
                self.paired_listbox.insert(tk.END, f"📱 {d.get('device_name', 'Device')} [{d.get('device_id', '')}]")

    def _build_statusbar(self):
        status_bar = tk.Frame(self.root, bg=COLOR_CARD, height=28, padx=14)
        status_bar.pack(fill=tk.X, side=tk.BOTTOM)

        lbl_port = tk.Label(status_bar, text=f"Port: {self.engine.port} (TCP/UDP) | Engine: v4.0.0", font=("Segoe UI", 9), fg=COLOR_MUTED, bg=COLOR_CARD)
        lbl_port.pack(side=tk.LEFT, pady=4)

        self.lbl_connections = tk.Label(status_bar, text="Connected: 0 devices", font=("Segoe UI", 9, "bold"), fg=COLOR_CYAN, bg=COLOR_CARD)
        self.lbl_connections.pack(side=tk.RIGHT, pady=4)

    def _on_device_discovered(self, dev_info):
        self.root.after(0, self._update_discovered_devices)

    def _on_transfer_complete(self, file_name, verified):
        status = "Verified ✅" if verified else "Failed ❌"
        self.root.after(0, lambda: self.transfer_listbox.insert(0, f"📥 Received: {file_name} ({status})"))

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
        self.root.after(2000, self._periodic_ui_refresh)

    def _on_close(self):
        self.engine.stop()
        self.root.destroy()


def main():
    root = tk.Tk()
    app = DexoLinkGUI(root)
    root.mainloop()

if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Iris for PC — windowed launcher (no terminal required)."""
from __future__ import annotations

import subprocess
import sys
import threading
import tkinter as tk
from tkinter import messagebox, scrolledtext
from pathlib import Path

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


def ensure_deps() -> str | None:
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM  # noqa: F401
        return None
    except ImportError:
        try:
            subprocess.check_call([sys.executable, "-m", "pip", "install", "-r", str(ROOT / "requirements.txt")])
            from cryptography.hazmat.primitives.ciphers.aead import AESGCM  # noqa: F401
            return None
        except Exception as e:
            return str(e)


class App:
    def __init__(self) -> None:
        self.root = tk.Tk()
        self.root.title("Iris for PC")
        self.root.geometry("560x420")
        self.root.minsize(480, 360)

        self.status = tk.StringVar(value="Starting…")
        tk.Label(self.root, text="Iris for PC", font=("Segoe UI", 16, "bold")).pack(pady=(16, 4))
        tk.Label(self.root, textvariable=self.status, font=("Segoe UI", 10)).pack()

        tk.Label(self.root, text="Pair link (copy → phone Settings → PC → Pair PC)", font=("Segoe UI", 9)).pack(pady=(12, 4))
        self.link_box = scrolledtext.ScrolledText(self.root, height=3, wrap=tk.WORD, font=("Consolas", 10))
        self.link_box.pack(fill=tk.X, padx=16)

        btn_row = tk.Frame(self.root)
        btn_row.pack(pady=8)
        tk.Button(btn_row, text="Copy link", command=self.copy_link).pack(side=tk.LEFT, padx=4)
        tk.Button(btn_row, text="Refresh link", command=self.refresh_link).pack(side=tk.LEFT, padx=4)

        tk.Label(self.root, text="Log", font=("Segoe UI", 9)).pack(anchor="w", padx=16)
        self.log = scrolledtext.ScrolledText(self.root, height=10, wrap=tk.WORD, font=("Consolas", 9))
        self.log.pack(fill=tk.BOTH, expand=True, padx=16, pady=(0, 12))

        self.server = None
        self.httpd = None
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)
        self.root.after(100, self.boot)

    def write(self, msg: str) -> None:
        def _():
            self.log.insert(tk.END, msg + "\n")
            self.log.see(tk.END)
        self.root.after(0, _)

    def set_link(self, link: str) -> None:
        def _():
            self.link_box.delete("1.0", tk.END)
            self.link_box.insert(tk.END, link)
            self.status.set("Running — pair from your phone (same Wi‑Fi)")
        self.root.after(0, _)

    def copy_link(self) -> None:
        text = self.link_box.get("1.0", tk.END).strip()
        if not text:
            return
        self.root.clipboard_clear()
        self.root.clipboard_append(text)
        self.status.set("Link copied")

    def refresh_link(self) -> None:
        if self.server:
            self.set_link(self.server.pair_link())

    def boot(self) -> None:
        err = ensure_deps()
        if err:
            self.status.set("Missing dependency")
            messagebox.showerror("Iris for PC", f"Could not install cryptography:\n{err}\n\nInstall Python 3 from python.org and try again.")
            return
        threading.Thread(target=self.run_server, daemon=True).start()

    def run_server(self) -> None:
        try:
            import iris_pc
            app = iris_pc.IrisPC()
            self.server = app
            port = int(app.config.get("port") or iris_pc.DEFAULT_PORT)
            httpd = iris_pc.ThreadingHTTPServer(("0.0.0.0", port), iris_pc.Handler)
            httpd.app = app
            self.httpd = httpd
            link = app.pair_link()
            self.set_link(link)
            self.write(f"Listening on port {port}")
            self.write(f"Config: {iris_pc.CONFIG_FILE}")
            self.write("Keys arrive automatically when the phone pairs.")
            httpd.serve_forever()
        except Exception as e:
            self.write(f"Error: {e}")
            self.root.after(0, lambda: self.status.set(f"Error: {e}"))

    def on_close(self) -> None:
        try:
            if self.httpd:
                self.httpd.shutdown()
        except Exception:
            pass
        self.root.destroy()

    def run(self) -> None:
        self.root.mainloop()


if __name__ == "__main__":
    App().run()

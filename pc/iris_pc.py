#!/usr/bin/env python3
"""Iris for PC — companion server. Keys arrive from the phone on pair."""

from __future__ import annotations

import base64
import hashlib
import json
import os
import platform
import secrets
import socket
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError:
    print("Install: pip install cryptography", file=sys.stderr)
    sys.exit(1)

AAD_PAIR = b"iris-pair"
AAD_API = b"iris-api"
DEFAULT_PORT = 8765
CONFIG_DIR = Path.home() / ".iris-pc"
CONFIG_FILE = CONFIG_DIR / "config.json"
STATE_FILE = CONFIG_DIR / "state.json"


def b64e(b: bytes) -> str:
    return base64.b64encode(b).decode("ascii")


def b64d(s: str) -> bytes:
    return base64.b64decode(s)


def sha256(s: str) -> bytes:
    return hashlib.sha256(s.encode("utf-8")).digest()


def seal(key: bytes, obj: dict, aad: bytes) -> dict:
    iv = secrets.token_bytes(12)
    ct = AESGCM(key).encrypt(iv, json.dumps(obj, ensure_ascii=False).encode("utf-8"), aad)
    return {"n": b64e(iv), "c": b64e(ct)}


def unseal(key: bytes, env: dict, aad: bytes) -> dict:
    pt = AESGCM(key).decrypt(b64d(env["n"]), b64d(env["c"]), aad)
    return json.loads(pt.decode("utf-8"))


def load_json(path: Path, default: Any) -> Any:
    if not path.exists():
        return default
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return default


def save_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")


def local_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


class IrisPC:
    def __init__(self) -> None:
        self.config = load_json(CONFIG_FILE, {
            "name": platform.node() or "PC",
            "port": DEFAULT_PORT,
            "api_key": "",
            "model": "gemini-2.0-flash",
            "provider": "gemini",
            "openai_key": "",
            "openai_base": "https://api.openai.com/v1",
            "openai_model": "gpt-4o-mini",
        })
        self.state = load_json(STATE_FILE, {
            "pair_token": secrets.token_urlsafe(24),
            "devices": {},
            "tasks": {},
            "inbox": [],
            "next_task": 1,
        })
        self._lock = threading.Lock()
        self._task_threads: dict[int, threading.Thread] = {}

    def save_config(self) -> None:
        save_json(CONFIG_FILE, self.config)

    def save_state(self) -> None:
        save_json(STATE_FILE, self.state)

    def pair_link(self) -> str:
        host = local_ip()
        port = int(self.config.get("port", DEFAULT_PORT))
        token = self.state["pair_token"]
        name = self.config.get("name", "PC")
        return f"iris://pair?h={host}&p={port}&t={token}&n={name}"

    def handle_pair(self, env: dict) -> dict:
        pair_key = sha256(self.state["pair_token"])
        try:
            plain = unseal(pair_key, env, AAD_PAIR)
        except Exception:
            return seal(pair_key, {"error": "bad pair ciphertext"}, AAD_PAIR)

        phone_name = plain.get("name") or "Phone"
        key_b64 = plain.get("key")
        if not key_b64:
            return seal(pair_key, {"error": "missing key"}, AAD_PAIR)
        phone_key = b64d(key_b64)
        if len(phone_key) != 32:
            return seal(pair_key, {"error": "key must be 32 bytes"}, AAD_PAIR)

        device_id = f"phone-{secrets.token_hex(4)}"
        with self._lock:
            self.state["devices"][device_id] = {"name": phone_name, "key_b64": key_b64}
            self.state["pair_token"] = secrets.token_urlsafe(24)
            self.save_state()

        settings = plain.get("settings") or {}
        if isinstance(settings, dict) and settings:
            for k in ("api_key", "model", "provider", "openai_key", "openai_base", "openai_model"):
                v = settings.get(k)
                if isinstance(v, str) and v.strip():
                    self.config[k] = v.strip()
            self.save_config()
            print("[pair] received API settings from phone")

        resp: dict[str, Any] = {"device": device_id}
        if plain.get("importSettings"):
            resp["settings"] = {
                "api_key": self.config.get("api_key", ""),
                "model": self.config.get("model", "gemini-2.0-flash"),
                "provider": self.config.get("provider", "gemini"),
                "openai_key": self.config.get("openai_key", ""),
                "openai_base": self.config.get("openai_base", ""),
                "openai_model": self.config.get("openai_model", ""),
            }
        print(f"[pair] {phone_name} paired as {device_id}")
        return seal(pair_key, resp, AAD_PAIR)

    def _device_key(self, env: dict) -> tuple[str | None, bytes | None]:
        for did, info in list(self.state.get("devices", {}).items()):
            try:
                key = b64d(info["key_b64"])
                unseal(key, env, AAD_API)
                return did, key
            except Exception:
                continue
        return None, None

    def handle_api(self, env: dict) -> dict:
        did, key = self._device_key(env)
        if not key:
            keys = list(self.state.get("devices", {}).values())
            if not keys:
                return {"error": "no paired devices"}
            key = b64d(keys[0]["key_b64"])
            return seal(key, {"error": "auth failed"}, AAD_API)
        plain = unseal(key, env, AAD_API)
        op = plain.get("op", "")
        try:
            if op == "task":
                out = self.op_task(plain)
            elif op == "poll":
                out = self.op_poll(plain)
            elif op == "stop":
                out = self.op_stop(plain)
            elif op == "approve":
                out = self.op_approve(plain)
            elif op == "handoff":
                out = self.op_handoff(plain)
            elif op == "inbox":
                out = self.op_inbox(plain)
            else:
                out = {"error": f"unknown op {op}"}
        except Exception as e:
            out = {"error": str(e)}
        return seal(key, out, AAD_API)

    def op_task(self, plain: dict) -> dict:
        text = (plain.get("text") or "").strip()
        existing = int(plain.get("task") or 0)
        with self._lock:
            if existing and str(existing) in self.state["tasks"]:
                tid = existing
                t = self.state["tasks"][str(tid)]
                t["cancelled"] = False
                t["done"] = False
                t["lines"].append(f"You: {text}")
            else:
                tid = int(self.state["next_task"])
                self.state["next_task"] = tid + 1
                self.state["tasks"][str(tid)] = {
                    "id": tid, "title": text[:40], "lines": [f"You: {text}"],
                    "approvals": [], "done": False, "cancelled": False,
                }
            self.save_state()
        th = threading.Thread(target=self._run_task, args=(tid, text), daemon=True)
        self._task_threads[tid] = th
        th.start()
        return {"task": tid}

    def op_poll(self, plain: dict) -> dict:
        tid = int(plain.get("task") or 0)
        since = int(plain.get("since") or 0)
        with self._lock:
            t = self.state["tasks"].get(str(tid))
            if not t:
                return {"lines": [], "done": True, "approvals": []}
            lines = t["lines"][since:]
            approvals = list(t.get("approvals") or [])
            done = bool(t.get("done") or t.get("cancelled"))
        return {"lines": lines, "done": done, "approvals": approvals}

    def op_stop(self, plain: dict) -> dict:
        tid = int(plain.get("task") or 0)
        with self._lock:
            t = self.state["tasks"].get(str(tid))
            if t:
                t["cancelled"] = True
                t["lines"].append("⏹ Stopped.")
                t["done"] = True
                self.save_state()
        return {"ok": True}

    def op_approve(self, plain: dict) -> dict:
        aid = plain.get("id")
        allow = bool(plain.get("allow"))
        with self._lock:
            for t in self.state["tasks"].values():
                for a in list(t.get("approvals") or []):
                    if a.get("id") == aid:
                        a["resolved"] = True
                        a["allow"] = allow
                        t["lines"].append(f"{'✓' if allow else '✗'} approval {aid}")
            self.save_state()
        return {"ok": True}

    def op_handoff(self, plain: dict) -> dict:
        title = plain.get("title") or "Handoff"
        entries = plain.get("entries") or []
        item = {"id": secrets.token_hex(8), "kind": "handoff", "title": title,
                "entries": entries[-40:], "at": int(time.time())}
        with self._lock:
            self.state["inbox"].append(item)
            self.save_state()
        print(f"[handoff] received: {title}")
        return {"ok": True}

    def op_inbox(self, plain: dict) -> dict:
        with self._lock:
            items = list(self.state.get("inbox") or [])
            self.state["inbox"] = []
            self.save_state()
        return {"items": items}

    def _emit(self, tid: int, line: str) -> None:
        with self._lock:
            t = self.state["tasks"].get(str(tid))
            if not t:
                return
            t["lines"].append(line)
            self.save_state()
        print(f"[task {tid}] {line[:200]}")

    def _cancelled(self, tid: int) -> bool:
        with self._lock:
            t = self.state["tasks"].get(str(tid))
            return bool(t and t.get("cancelled"))

    def _run_task(self, tid: int, text: str) -> None:
        try:
            self._emit(tid, "→ thinking")
            reply = self._llm(text)
            if self._cancelled(tid):
                return
            self._emit(tid, f"🤖 {reply}")
        except Exception as e:
            self._emit(tid, f"⚠ {e}")
        finally:
            with self._lock:
                t = self.state["tasks"].get(str(tid))
                if t:
                    t["done"] = True
                    self.save_state()

    def _llm(self, user_text: str) -> str:
        provider = (self.config.get("provider") or "gemini").lower()
        if provider == "openai" or (provider == "auto" and self.config.get("openai_key")):
            if self.config.get("openai_key"):
                try:
                    return self._openai_chat(user_text)
                except Exception:
                    if not self.config.get("api_key"):
                        raise
        if not self.config.get("api_key"):
            if self.config.get("openai_key"):
                return self._openai_chat(user_text)
            return "No API key yet. Pair from the phone so keys sync automatically."
        return self._gemini_chat(user_text)

    def _gemini_chat(self, user_text: str) -> str:
        key = self.config["api_key"]
        model = self.config.get("model") or "gemini-2.0-flash"
        models = [model, "gemini-2.0-flash", "gemini-2.5-flash", "gemini-1.5-flash"]
        body = json.dumps({
            "contents": [{"role": "user", "parts": [{"text": user_text}]}],
            "systemInstruction": {"parts": [{"text": "You are Iris on the user's PC. Be concise and helpful."}]},
        }).encode("utf-8")
        last = "unknown error"
        for m in models:
            url = f"https://generativelanguage.googleapis.com/v1beta/models/{m}:generateContent?key={key}"
            req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"}, method="POST")
            try:
                with urllib.request.urlopen(req, timeout=90) as r:
                    data = json.loads(r.read().decode("utf-8"))
                parts = data.get("candidates", [{}])[0].get("content", {}).get("parts", [])
                text = "".join(p.get("text", "") for p in parts).strip()
                if text:
                    return text
                last = "empty response"
            except urllib.error.HTTPError as e:
                last = f"HTTP {e.code} ({m})"
                if e.code in (400, 404):
                    continue
                if e.code not in (429, 500, 502, 503, 504):
                    break
            except Exception as e:
                last = str(e)
        raise RuntimeError(last)

    def _openai_chat(self, user_text: str) -> str:
        key = self.config["openai_key"]
        base = (self.config.get("openai_base") or "https://api.openai.com/v1").rstrip("/")
        model = self.config.get("openai_model") or "gpt-4o-mini"
        body = json.dumps({
            "model": model,
            "messages": [
                {"role": "system", "content": "You are Iris on the user's PC. Be concise and helpful."},
                {"role": "user", "content": user_text},
            ],
        }).encode("utf-8")
        req = urllib.request.Request(
            f"{base}/chat/completions", data=body,
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"}, method="POST",
        )
        with urllib.request.urlopen(req, timeout=90) as r:
            data = json.loads(r.read().decode("utf-8"))
        return (data.get("choices", [{}])[0].get("message", {}).get("content") or "").strip() or "(empty)"


class Handler(BaseHTTPRequestHandler):
    server_version = "IrisPC/1.0"

    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"[http] {self.address_string()} {fmt % args}")

    def _read_json(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b"{}"
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception:
            return {}

    def _write_json(self, code: int, obj: dict) -> None:
        data = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self) -> None:
        app: IrisPC = self.server.app  # type: ignore[attr-defined]
        body = self._read_json()
        if self.path == "/pair":
            self._write_json(200, app.handle_pair(body))
        elif self.path == "/api":
            self._write_json(200, app.handle_api(body))
        else:
            self._write_json(404, {"error": "not found"})

    def do_GET(self) -> None:
        app: IrisPC = self.server.app  # type: ignore[attr-defined]
        if self.path in ("/", "/status"):
            self._write_json(200, {
                "name": app.config.get("name"),
                "devices": len(app.state.get("devices") or {}),
                "tasks": len(app.state.get("tasks") or {}),
                "pair_link": app.pair_link(),
            })
        else:
            self._write_json(404, {"error": "not found"})


def main() -> None:
    app = IrisPC()
    port = int(app.config.get("port") or DEFAULT_PORT)
    app.save_config()
    httpd = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    httpd.app = app  # type: ignore[attr-defined]
    link = app.pair_link()
    print("=" * 60)
    print("  Iris for PC")
    print(f"  Listen: {local_ip()}:{port}")
    print(f"  Pair:   {link}")
    print("  API keys arrive automatically when the phone pairs.")
    print("=" * 60)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nBye.")
        httpd.server_close()


if __name__ == "__main__":
    main()

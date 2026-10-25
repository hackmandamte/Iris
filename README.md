# Iris

Autonomous agent for Android, with an optional **PC companion** for pairing, remote tasks, and handoff.

| Piece | Path | What it is |
|-------|------|------------|
| **Android app** | `app/` | Chat agent, tools, screen control, post queue |
| **Iris for PC** | [`pc/`](pc/) | Python server: pair over LAN, run tasks, handoff |

## Android APK (GitHub Actions)

1. Actions → **Build APK** (runs on push).
2. Download the `Iris-apk` artifact (or a Release asset when published).
3. Install (allow unknown apps).

### First run (phone)

- Paste a Gemini and/or OpenAI-compatible API key in **Settings**.
- Optional: Screen control (Accessibility), Face island / Quick access (overlay), notification access.

## Iris for PC

Full guide: **[pc/README.md](pc/README.md)**

```bash
cd pc
python3 -m venv .venv
source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
python iris_pc.py
```

Copy the **pair link** from the terminal → phone **Settings → PC → Pair PC**.  
Same Wi‑Fi. Traffic is encrypted (AES-GCM) with a key created at pairing.

Config lives in `~/.iris-pc/config.json` (Gemini and/or OpenRouter-style OpenAI-compatible keys).

## Features (phone)

- Chat + voice, parallel tasks, tool use (screen, notifications, shell, memory, GitHub, posts).
- Gemini model chain + OpenAI-compatible provider + optional local LLM fallback.
- Quick access bubble and face island (optional).
- Pair with Iris for PC for remote tasks and handoff.

## License / status

Personal project. Android build via Actions; PC companion is source under `pc/` (run with Python).

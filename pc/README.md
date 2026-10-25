# Iris for PC

Companion for the Iris Android app. **No typing API keys on the PC** — when you pair from the phone, keys are sent over the encrypted pair channel automatically.

## Quick start (no terminal)

1. Install [Python 3](https://www.python.org/downloads/) (on Windows, check **“Add python.exe to PATH”**).
2. Double‑click:
   - **Windows:** `Start Iris PC.bat`
   - **Mac:** `Start Iris PC.command` (first time: right‑click → Open)
3. Click **Copy link** in the window.
4. On the phone: **Settings → PC → Pair PC** (paste the link).

Same Wi‑Fi required. After pairing, the PC has your Gemini / OpenAI-compatible keys from the phone.

## What you get

- Encrypted pair (`iris://pair?…`)
- Phone can send tasks to the PC and poll progress
- Handoff from phone → PC
- Keys synced **phone → PC** on pair (empty phone fields can also be filled from PC if needed)

## Optional: terminal

```text
python IrisPC.pyw
```

or headless: `python iris_pc.py`

Config (auto-written): `~/.iris-pc/config.json` or `%USERPROFILE%\.iris-pc\config.json` on Windows.

## Firewall

Allow inbound TCP **8765** on your private network.

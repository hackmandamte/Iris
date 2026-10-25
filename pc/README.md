# Iris for PC

Companion for the [Iris Android app](https://github.com/hackmandamte/Iris). Runs on your computer so the phone can:

- **Pair** over the local network (encrypted)
- **Send tasks** to the PC and watch progress live
- **Hand off** finished phone tasks to the PC
- **Import** API settings from the PC at pair time

## Requirements

- Python 3.10+
- Phone and PC on the **same Wi‑Fi**
- Iris Android APK with Pair PC support

## Install

```bash
cd pc
python3 -m venv .venv
source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

## Run

```bash
python iris_pc.py
```

Copy the **pair link** printed in the terminal.

On the phone: **Settings → PC → Pair PC (paste link from clipboard)**  
(or scan if you put the link into a QR generator).

## API keys

Edit `~/.iris-pc/config.json` (created on first run):

```json
{
  "name": "My Laptop",
  "port": 8765,
  "api_key": "YOUR_GEMINI_KEY",
  "model": "gemini-2.0-flash",
  "provider": "gemini",
  "openai_key": "",
  "openai_base": "https://openrouter.ai/api/v1",
  "openai_model": "openai/gpt-4o-mini"
}
```

- **Gemini:** set `api_key` and `provider` to `gemini`.
- **OpenRouter / Groq / any OpenAI-compatible:** set `openai_key`, `openai_base`, `openai_model`, and `provider` to `openai`.

Environment overrides: `IRIS_API_KEY`, `IRIS_OPENAI_KEY`, `IRIS_OPENAI_BASE`, `IRIS_OPENAI_MODEL`.

## Firewall

Allow inbound TCP on the chosen port (default **8765**) from your LAN.

## Protocol

Matches the Android `Pc.kt` client:

| Endpoint | Crypto | Purpose |
|----------|--------|--------|
| `POST /pair` | AES-GCM, key = SHA-256(pair token), AAD `iris-pair` | One-time pairing |
| `POST /api`  | AES-GCM, per-device key, AAD `iris-api` | `task`, `poll`, `stop`, `approve`, `handoff`, `inbox` |
| `GET /status` | none | Health + current pair link |

Pair URI: `iris://pair?h=<host>&p=<port>&t=<token>&n=<name>`

## Status

**v1 companion:** chat-style tasks on the PC, pair/handoff with the phone. Desktop tool-use can grow later without changing the phone protocol.

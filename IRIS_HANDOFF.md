# Iris handoff — 2026-10-22

Repo: https://github.com/hackmandamte/Iris

## Goals
- Grok-like minimal chat; accordion Settings with icons
- Quick access = Face bubble + overlay toolbar on **current screen** (tap does not open app)
- Face island separate from Quick access
- Cloud LLM auto-fallback + OpenAI-compatible + local HTTP models
- Screen control via Accessibility only (cannot be bypassed on stock Android)

## Key files
- MainActivity.kt — chat, accordion Settings, compose paperclip
- AgentEngine.kt — Gemini chain, OpenAI, LocalModels fallback
- AccessUi.kt — Quick face bubble, fade, menu, mic, quick input
- OverlayService.kt — FG service, face island, notification actions
- FaceView.kt — animated face
- LocalModels.kt — package discovery + local endpoint probe
- Voice, Tools, Social, Posts, Pc, Media

## Prefs
`api_key`, `model`, `provider`, `openai_*`, `local_llm_base/model/key`, `last_good_model`, `github_token`, `screen`, `speak`, `bubble` (island), `quick_on` (quick face), `digest_on`

## LLM order (provider=auto)
1. Gemini preferred + sticky last-good + model chain
2. OpenAI-compatible if key set
3. LocalModels.firstReachable (custom URL, then 127.0.0.1:11434/8080/1234)

## Quick access
- Logo: FaceView; idle ~4.5s fades to ~28% alpha
- Menu: quick input + mic (SpeechRecognizer) + Summarize / Mute / Stop / Close
- Open Iris app only via explicit link
- Toggle: Settings → Control → Quick access

## Screen control
AccessibilityService required for cross-app UI. No legitimate bypass. Overlay/Quick need display-over-apps only.

## Connected apps
Installed social packages + notification access status — not OAuth.

## Build
Actions → Build APK workflow → install green artifact.

## Non-goals
Bypassing Accessibility; silent social OAuth without official APIs.

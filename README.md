# Iris (Android)

Autonomous Gemini-powered agent: chat screen, floating bubble, intents/APIs, optional screen control.

## Build the APK (GitHub Actions)
1. Create a new GitHub repo and upload everything in this folder (keep the `.github/workflows/build.yml` path).
2. Open the repo > Actions > "Build APK" (it also runs on every push).
3. When it finishes, open the run and download the `Iris-apk` artifact (a zip containing app-debug.apk), or download the APK directly from the Releases page.
4. Install the APK (allow "install unknown apps").

## First run
- Paste your Gemini API key (Settings button) and tap Send. Tap the mic to speak a task; it fills the text box so you can check it first.
- Screen control: switch it on, then enable "Iris" under Settings > Accessibility.
- Face island: switch it on and allow "Display over other apps". A black pill with a face appears at the top of the screen. Drag it so the camera sits between the eyes.
  - Tap: open the task box (or stop a running task). Long-press: summarize my screen (needs Screen control).
  - Expressions: idle (eyes drift, blink), thinking (looks around), acting (focused), reading/summarizing (squint, eyes scan), speaking (mouth moves), reporting a result (happy eyes), error (worried).
  - Settings has "Speak replies aloud".

## Social media
- Platforms: Instagram, X, Telegram, WhatsApp (posting); YouTube (notifications and comment replies only).
- Ask in chat: "Write 3 caption ideas for ...", "Schedule this on X for tomorrow 6pm", "Draft replies to my unread DMs".
  The agent repeats the exact text, platform and time; once you approve, it is queued and posts itself at that time.
- Post queue button: see and remove scheduled posts. Daily summary button: pick a time; a digest notification arrives daily.
- Needs: Screen control on (for posting), Notification access granted (for summaries and inline replies).
- Scheduled posts wake the screen, but cannot get past a PIN/pattern lock. Leave the phone unlocked or set the lock to None.
- Alarms use the system alarm clock, so Android shows an alarm icon in the status bar while one is pending.

## Parallel tasks
- Every message starts its own task, so several can run at once (up to 5 working, extra ones queue). Each has its own conversation.
- Task pills under the chips show state: working, needs screen, queued, done, stopped, error. Tap a pill to stop it, remove it, or reply to it (to answer a question it asked).
- The phone has one screen, so tasks take turns using it. A task keeps the screen from its first tap or app-open until it finishes; thinking, web, notifications and scheduling still run in parallel.
- Tap the status pill (top right of the chips row) to stop everything.
- Free Gemini keys have low request-per-minute limits; many parallel tasks may slow down or retry.

## Quick access (works from any app, without the island)
- Side bubble: a small eye circle you can drag; it snaps to the screen edge and shows how many tasks are running. Tap it.
- Both volume keys held together (about 0.6 s): same menu. Needs Iris enabled under Accessibility.
- Quick Settings tile: edit your tiles (pencil icon) and drag "Iris" in. Tapping it closes the shade and opens the menu.
- The menu asks each time: Speak (hands-free voice, runs straight away), Type (text box), Tasks (list with Stop buttons).
- Persistence: a foreground service keeps Iris alive and restarts after reboot. Use the "Keep alive" chip to exempt Iris from battery
  optimization. On Infinix, also allow Autostart for Iris and lock it in Recents.
- Voice from the menu needs the microphone permission (the app asks on launch).

## Iris for PC (pairing, handoff, remote tasks)
- Run Iris for PC on your computer (see its README), press **Show QR code**, scan it with the phone camera or Google Lens; it opens Iris and asks to pair.
  No camera handy? Tap the **Pair PC** chip and paste the link under the QR.
- Pairing can import the PC's API key and settings.
- **PC chip**: switch new tasks to run on the PC. Its steps appear here live; if it needs approval (command, file write, screen control) a dialog asks you.
- **Hand off**: tap a finished task's pill > Hand off to PC. Tasks the PC hands off arrive as a dialog (and a notification) with Continue.
- Same Wi-Fi only. Traffic is encrypted end to end with a key created at pairing.

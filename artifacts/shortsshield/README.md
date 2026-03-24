# ShortsShield

**Block YouTube Shorts. Reclaim your feed.**

ShortsShield is a native Android app that uses Android's Accessibility Service to silently and automatically block YouTube Shorts — with zero internet requirement and no accounts needed.

---

## Features

| Feature | What it does |
|---|---|
| **Block Shorts Player** | Fires BACK instantly when the Shorts reel player opens |
| **Hide Shorts Shelf** | Removes the Shorts section from the YouTube Home feed |
| **Block Link Opens** | Redirects Shorts opened via external links / deep-links |
| **Live Stats** | Tracks Shorts blocked today and all-time |
| **Persistent Notification** | Optional status bar indicator while active |

---

## Download APK (GitHub Actions)

1. **Fork** this repository to your own GitHub account
2. Go to your fork → **Actions** tab → click the latest **Build APK** run
3. Scroll to **Artifacts** → download `ShortsShield-debug-apk`
4. Transfer the APK to your phone and install it (allow "Install unknown apps" if prompted)

The APK is built automatically on every push to `main`.

---

## Build locally (Android Studio)

```bash
# Requires JDK 17 + Android SDK
gradle assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and click **▶ Run**.

---

## Setup (after install)

1. Open **ShortsShield**
2. Tap **Enable Shield**
3. Find **ShortsShield** in the Accessibility list → toggle **ON** → confirm
4. Done — open YouTube, it'll never show you Shorts again

---

## How it works

The `ShortsShieldService` (`AccessibilityService`) listens to YouTube events only:

```
Event received (50 ms timeout)
  │
  ├─ Source node ID check     ← O(1), instant, no tree traversal
  │    ID in SHORTS_PLAYER_IDS?  ──yes──▶  performGlobalAction(BACK)
  │
  ├─ Window class check       ← catches deep-link Shorts launches
  │    class == ShortsActivity? ──yes──▶  BACK
  │
  └─ Root scan (fallback)
       ├─ View-ID lookup for all 7 known Shorts player IDs
       ├─ Depth-capped title scan (max 10 levels, short-circuit)
       └─ Shelf purge (cooldown 4s): More options → Fewer shorts
```

---

## Permissions

- `BIND_ACCESSIBILITY_SERVICE` — required to read the YouTube view hierarchy
- `POST_NOTIFICATIONS` — for the optional status bar notification

No internet permission. No storage permission. No accounts.

---

## Compatibility

- Android 8.0+ (API 26)
- Tested against YouTube v19.x–v20.x
- Works in Expo Go testing environments

---

## License

MIT

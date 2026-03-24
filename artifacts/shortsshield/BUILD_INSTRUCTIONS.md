# ShortsShield — Build & Install Guide

## What you need

- **Android Studio** (any recent version — Hedgehog or newer)
  → Download free at: https://developer.android.com/studio
- **Your Android phone** with a USB cable (or ADB over Wi-Fi)
- USB Debugging enabled on your phone (Settings → Developer options → USB Debugging)

---

## Step 1 — Open the project

1. Open Android Studio
2. Click **"Open"** (not "New Project")
3. Navigate to this `shortsshield/` folder and click **OK**
4. Wait for Gradle sync to finish (a few minutes the first time)

---

## Step 2 — Connect your phone

1. Connect your Android phone via USB
2. Allow USB Debugging when prompted on the phone
3. Your device should appear in the device dropdown at the top of Android Studio

---

## Step 3 — Build & Install

Click the **▶ Run** button (green triangle) at the top.

Android Studio will:
- Build the APK
- Install it on your phone automatically
- Launch the app

---

## Step 4 — Enable the Accessibility Service

1. The app opens to the **ShortsShield** screen
2. Tap **"Enable Shield"**
3. Find **ShortsShield** in the Accessibility list
4. Toggle it **ON** and tap **Allow**
5. Done — the shield is active!

---

## How it works after setup

- **No action needed** — ShortsShield runs silently whenever YouTube is open
- Open YouTube → if it lands on a Short → immediately goes back
- Scrolling Home feed → any Shorts shelf gets removed automatically
- Works even on slow/no internet (it reads the screen, not the network)
- Works even when scrolling very fast (50ms response time)

---

## Generating a standalone APK (to share)

In Android Studio:
1. **Build** → **Generate Signed Bundle / APK**
2. Choose **APK**
3. Create or use a keystore (follow the prompts)
4. Choose **release** build
5. The APK is saved to `app/release/app-release.apk`

---

## Troubleshooting

| Issue | Fix |
|---|---|
| Gradle sync fails | Check internet connection; let it retry |
| Device not showing | Enable USB Debugging; try a different cable |
| App installed but Shield doesn't work | Make sure the Accessibility Service is toggled ON |
| YouTube updated and broke detection | Report via GitHub — view IDs change occasionally |

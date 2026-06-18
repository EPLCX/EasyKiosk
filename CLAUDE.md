# Kiosk 广告机 - Android Root Kiosk App

Native Android kiosk application built with Kotlin. Single-activity WebView-based
advertisement player with full-screen lock, root-level system hardening, and
password-protected exit.

## Architecture

```
com.adscreen.kiosk/
├── KioskApp.kt                  — Application class, libsu config
├── MainActivity.kt              — WebView + immersive + screen pinning + settings
├── SettingsActivity.kt          — Password-protected settings (URL, password, orientation, root)
├── SetupWizardActivity.kt       — First-run password/URL setup
├── exit/
│   └── ExitDialogFragment.kt    — Password-protected exit/settings gate
├── manager/
│   ├── DeviceAdminReceiverImpl.kt — DPC receiver for lock-task
│   ├── KioskManager.kt          — SystemUI flags + lock-task control
│   ├── RootManager.kt           — libsu root operations (no launcher freeze by default)
│   └── SecurityManager.kt       — DPM + screen pinning helpers
└── util/
    ├── Constants.kt             — Shared constants & shell commands
    ├── CryptoUtil.kt            — EncryptedSharedPreferences wrapper
    └── NetworkUtil.kt           — Connectivity monitoring
```

## Build & Dependencies

- Min SDK: 24 (Android 7.0), Target: 34
- Kotlin 1.9.22, AGP 8.2.2, Gradle 8.5
- Key libs: AndroidX, Material3, libsu (root), Glide, Security-Crypto

## Key Design Decisions

1. **SetupWizard → Main flow**: First-run detection via EncryptedSharedPreferences.
   Password and URL encrypted at rest with AES-256 GCM.

2. **Immersive enforcement**: Uses `WindowInsetsController` (API 30+) or
   `SYSTEM_UI_FLAG_IMMERSIVE_STICKY` (API <30). Re-applied in
   `onWindowFocusChanged` every time the window gains focus.

3. **Root is mandatory**: No lock-task fallback. On startup, app checks root
   availability; if absent, shows a blocking dialog and exits. Root enforcement
   via `am start` in `onUserLeaveHint`/`onWindowFocusChanged` (lost focus)
   prevents the app from being sent to background.

4. **Root operations via libsu**: All `su -c` commands are best-effort. Root
   auto-requested at startup. By default, root is used ONLY for global fullscreen
   (`policy_control immersive.full=*`) and process priority — launcher freeze is
   OFF and must be explicitly enabled in settings.

5. **Settings**: Settings gear icon (alpha 0.3) next to exit button, password-protected.
   Configure URL, admin password, screen orientation (landscape/portrait/auto), and
   root behavior. Orientation applied via `requestedOrientation` at resume time.

6. **Exit**: Right-aligned exit button (alpha 0.3), triggers 6-digit password
   dialog. Successful unlock calls `stopLockTask()` + root cleanup + finishAffinity().

7. **Network error**: WebView `onReceivedError` (main-frame only) shows a retry
   overlay. Auto-recovers when network becomes available via ConnectivityManager
   callback.

## Build

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

## Root Setup (recommended)

For full kiosk lock, pre-install as a system app or grant via ADB:
```bash
adb shell dpm set-device-owner com.adscreen.kiosk/.manager.DeviceAdminReceiverImpl
```

This enables lock-task mode without user prompt.

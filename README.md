# EasyKiosk

A native Android kiosk application built with Kotlin. Turns an Android device into a locked-down, single-app advertisement player or digital signage display.

## Features

- **Full-screen WebView** — Displays a target webpage in full-screen kiosk mode with no visible chrome
- **Root-level enforcement** — Uses root access for global immersive mode, system UI hardening, and screen pinning
- **Auto-start on boot** — Automatically launches into kiosk mode when the device powers on
- **Password-protected settings** — Configure URL, admin password, screen orientation, and root behavior via a password-gated settings panel
- **Password-protected exit** — Exit kiosk mode only after entering the 6-digit admin password
- **Network error recovery** — Shows a retry overlay on connection loss; auto-recovers when the network is back
- **First-run setup wizard** — Guided initial configuration of password and target URL
- **Uninstall protection** — Prevents accidental uninstallation; requires password-protected settings to trigger

## Requirements

- Android 7.0+ (API 24)
- **Root access** is mandatory — the app uses root for:
  - Global immersive mode (`policy_control`)
  - System UI hardening (status bar, quick settings, navigation bar)
  - Screen pinning without user prompt
  - Enabling/disabling system launchers

## Build

### Prerequisites

- JDK 17
- Gradle (bundled `gradle-8.5/`) or system-installed Gradle 8.5+
- Android SDK (see `android-sdk/` or set `ANDROID_HOME`)

### Steps

```bash
# Using the bundled Gradle
gradle-8.5/bin/gradle assembleRelease

# Or using build script
python build.py

# Or directly
./gradlew assembleRelease
# (if you've set up the Gradle wrapper)
```

Output: `app/build/outputs/apk/release/app-release.apk`

### Device Owner Setup (recommended)

For seamless lock-task mode without a confirmation dialog:

```bash
adb shell dpm set-device-owner com.adscreen.kiosk/.manager.DeviceAdminReceiverImpl
```

## Usage

1. **First boot** — The setup wizard guides you through setting a 6-digit admin password and target URL
2. **Kiosk mode** — The app locks into full-screen, displaying the configured webpage
3. **Settings** — Tap the gear icon (top-right corner, semi-transparent), enter password to access:
   - Change target URL
   - Change admin password
   - Switch screen orientation (landscape / portrait / auto)
   - Toggle launcher freeze (Root: freeze system launchers)
   - Uninstall protection
   - Exit kiosk mode
4. **Exit** — Go to Settings → "Exit Kiosk Mode" → enter password → device returns to normal

## Security Notes

- Admin password is encrypted using AES-256 GCM via Android Keystore
- The app window is marked `FLAG_SECURE` to prevent screenshots/screen recording
- Physical buttons (Home, Recents, Menu) are intercepted and blocked
- Back button is disabled
- Periodic enforcement (every 2s) re-applies immersive flags and re-enters lock-task mode if tampered with
- Lock screen / keyguard may prevent the app from showing on boot until the device is unlocked — this is a platform limitation

## License

MIT

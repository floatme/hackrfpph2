# HackRFPPH2

Android controller and mirror app for HackRF One + PortaPack H2/H2+ (Mayhem firmware).

This app was built to fully operate PortaPack from phone touch controls, including cases where physical PortaPack buttons/encoder are damaged. It is also useful for normal remote operation.

## Features

- Live screen mirror from PortaPack (`screenframeshort`).
- Virtual hardware controls:
  - `UP`, `DOWN`, `LEFT`, `RIGHT`
  - `OK` (encoder press)
  - Rotary encoder emulation:
    - horizontal drag strip
    - rotary dial widget
- Frequency keyboard assist (`Freq KB`):
  - Tap a frequency field in Mayhem first (opens Mayhem frequency keypad).
  - Type digits from Android keyboard.
  - Optional `Type + OK` to apply immediately.
- Tap-to-touch on mirrored frame (`touch x y` injection).
- Screen profile selection:
  - Auto (detected)
  - H2 240x320
  - H2 320x240
- Fullscreen mode with optional control overlay (`FS Controls` + `Hide UI/Show UI`).
- Touch safety:
  - Touch Lock
  - debounced control actions
- Stream resilience:
  - stream-failure auto reconnect with backoff
  - control commands prioritized over frame polling
  - Turbo link auto-detection (`button_fast`, `touch_fast`, `screenframeshort2`)

## Requirements

- PortaPack H2/H2+ on Mayhem firmware with USB shell/serial available.
- HackRF One + PortaPack physically connected and powered.
- Android device with USB OTG host support.
- USB OTG data cable/adapter (stable cable quality is important).
- JDK 17+ and Android SDK (for local compile).

## Build (Linux/macOS/Windows)

From repo root:

```bash
./gradlew :app:assembleFreeDebug
```

On Windows PowerShell:

```powershell
.\gradlew :app:assembleFreeDebug
```

APK output:

`app/build/outputs/apk/free/debug/app-free-debug.apk`

Unsigned release APK:

```bash
./gradlew :app:assembleFreeRelease
```

`app/build/outputs/apk/free/release/app-free-release-unsigned.apk`

Signed release APK (recommended for distribution):

```powershell
.\gradlew :app:assembleFreeRelease `
  -Pandroid.injected.signing.store.file="<path-to-keystore.jks>" `
  -Pandroid.injected.signing.store.password="***" `
  -Pandroid.injected.signing.key.alias="your_alias" `
  -Pandroid.injected.signing.key.password="***"
```

Signed output (when signing properties are provided):  
`app/build/outputs/apk/free/release/app-free-release.apk`

## Install

```bash
adb install -r app/build/outputs/apk/free/debug/app-free-debug.apk
```

## How it works

The app opens Mayhem's USB serial shell and uses shell commands:

- `getres` for active screen geometry.
- `screenframeshort` for frame stream.
- `button <1..8>` for hardware-equivalent controls.
- `touch x y` for direct touch injection.
- `linkcaps` (if available) to enable turbo path automatically.

Because the app sends the same `button` events as hardware, behavior is consistent across apps without app-specific logic.

## How to use

1. Connect phone to PortaPack/HackRF over OTG.
1. Open `HackRFPPH2`.
1. Tap `Refresh`, select USB device, tap `Connect`.
1. Accept USB permission.
1. Use virtual controls and dial, or tap directly on mirrored frame.
1. For fast frequency entry:
   - Tap the frequency value inside the mirrored Mayhem UI.
   - Tap `Freq KB` in app controls.
   - Enter value and press `Type + OK`.
   - If status says keyboard is unsupported, update Mayhem to a build with shell `keyboard` command.
1. Use `Touch Lock` when needed to prevent accidental touch input.
1. Use `Full` for fullscreen and `Hide UI/Show UI` to toggle fullscreen controls.

## Donations

The app shows a donation popup on app start and when you press back to close the app.  
You can dismiss it and continue using the app with no restrictions.
Each address includes an in-app copy button.

- ETH: `0xEcA58b1a98B457C2a07ba74e67dc15d26c39698F`
- BTC: `bc1qsd93dmcrc6l3huyyz3yp8qm4arw38rhf6hsw84`
- USDT (ERC20): `0xEcA58b1a98B457C2a07ba74e67dc15d26c39698F`

## Security checks performed

- Manifest hardening:
  - app backup disabled (`allowBackup=false`, `fullBackupContent=false`)
  - Android 12+ transfer/backup excluded via `data_extraction_rules.xml`
  - cleartext network traffic disabled (`usesCleartextTraffic=false`)
- Build hardening:
  - release builds now enable R8 minification + resource shrinking
  - release build no longer forces debug signing
- Input hardening:
  - frequency keyboard input length capped
  - keyboard hex payload formatting forced to `Locale.US` for stable command encoding

## Security recommendations

1. Use a dedicated signing key for release builds (never the debug keystore).
2. Keep USB control app in foreground while connected; avoid running unknown SDR/USB apps in parallel.
3. Leave `Touch Lock` enabled when not actively controlling the device.
4. Avoid entering sensitive information in mirrored Mayhem fields when screen sharing/recording is active.
5. Update dependencies regularly; `usb-serial-for-android` is pinned to `3.9.0`.

## Troubleshooting

- Connects then quickly disconnects:
  - Try a different OTG cable/adapter.
  - Ensure phone battery optimization is disabled for this app.
  - Ensure HackRF power is stable (brown-outs can cause USB detach).
  - Close other SDR apps that can claim the same USB device:
    - `marto.rtl_tcp_andro`
    - `com.s33me.myhackrf`
  - Use app status line and logcat tags:
    - `HackRFPPH2.Controller`
    - `HackRFPPH2.UsbShell`
- `Connect failed: USB busy`:
  - Another app is likely contending for the HackRF USB serial endpoint.
  - Force-stop competing apps and retry:
    - `adb shell am force-stop marto.rtl_tcp_andro`
    - `adb shell am force-stop com.s33me.myhackrf`
  - If needed, unplug/replug OTG after force-stop.
- No controls response:
  - Confirm app status is `Connected`.
  - Check `Last control` field updates when pressing controls.
  - Disable `Touch Lock`.
- Wrong sizing:
  - Change screen profile chip from Auto to matching H2 orientation.

## Automated QA

- UI + structure check:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\qa_portapack_remote.ps1
```

- Live connected control validation:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\qa_portapack_remote.ps1 -RequireConnected
```

- If phone USB is used by HackRF, use wireless ADB:
  - `docs/wireless-adb-qa.md`

## Docs

- Usage and behavior: `docs/portapack-remote.md`
- Wireless ADB QA: `docs/wireless-adb-qa.md`
- Security review: `docs/security-review-2026-02-25.md`

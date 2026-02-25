# HackRFPPH2 (Android OTG Mirror + Virtual Controls)

`HackRFPPH2` mirrors PortaPack Mayhem display frames over USB OTG and injects the same virtual button events Mayhem expects from hardware controls.

## What it does

- Streams display frames with `screenframeshort`.
- Reads active screen resolution with `getres`.
- Sends hardware-equivalent events with `button`:
  - `UP` -> `4`
  - `DOWN` -> `3`
  - `LEFT` -> `2`
  - `RIGHT` -> `1`
  - `OK / encoder press` -> `5`
  - `Encoder CCW/CW` -> `7/8`
- Sends touch taps to PortaPack UI with `touch x y`.
- Sends keyboard bytes to focused Mayhem widgets with `keyboard <HEX>`.
- Auto-detects turbo shell capabilities (`linkcaps`) and uses:
  - `button_fast`
  - `touch_fast`
  - `screenframeshort2` (2x downsampled frames, upscaled on phone)

## Requirements

- HackRF One + PortaPack H2/H2+ running Mayhem firmware with USB shell enabled.
- Android phone/tablet with USB OTG host support.
- Good USB OTG data cable (power-stable cable matters for link stability).

## Build and install

```powershell
.\gradlew :app:assembleFreeDebug
adb install -r app/build/outputs/apk/free/debug/app-free-debug.apk
```

## Usage

1. Connect phone to HackRF/PortaPack over OTG.
1. Open `HackRFPPH2`.
1. Tap `Refresh` -> select USB device -> `Connect`.
1. Accept Android USB permission.
1. Use:
   - Mirror tap-to-touch on the frame area.
   - D-pad (`UP/DN/LT/RT`) + `OK`.
   - Encoder strip and rotary dial.
   - Fine/Coarse toggle.
   - `Freq KB` (frequency keyboard assist):
     - Tap a frequency field in Mayhem first.
     - Open `Freq KB` in the app.
     - Type digits and press `Type + OK`.
1. Use `Touch Lock` to block accidental inputs.

## Fullscreen controls

- Enter fullscreen with `Full`.
- Toggle overlays with `Hide UI` / `Show UI`.
- `FS Controls` switch in the connection panel controls whether fullscreen shows virtual controls by default.

## Disconnect troubleshooting

If you connect, then disconnect a few seconds later:

- Use a shorter/higher-quality OTG cable.
- Ensure phone battery saver is off for this app.
- Avoid low-power hubs/adapters.
- Keep HackRF well powered (power instability can cause USB detach/reattach).
- Check controller status text and logcat tags:
  - `HackRFPPH2.Controller`
  - `HackRFPPH2.UsbShell`

The app now includes stream-failure auto-reconnect with backoff for transient dropouts.

## Turbo link requirements

Turbo link is enabled only when firmware exposes `linkcaps` and the fast commands listed above.
If firmware is older, the app automatically falls back to legacy commands.

## Automated QA

- Local script: `scripts/qa_portapack_remote.ps1`
- UI-only check:
  - `powershell -ExecutionPolicy Bypass -File .\scripts\qa_portapack_remote.ps1`
- Require live connected controls:
  - `powershell -ExecutionPolicy Bypass -File .\scripts\qa_portapack_remote.ps1 -RequireConnected`

For QA while the phone USB port is occupied by HackRF/PortaPack, use wireless ADB (`docs/wireless-adb-qa.md`).

# HackRFPPH2

HackRFPPH2 is an Android app that mirrors PortaPack H2/H2+ screen output and sends control input (navigation, OK, dial/drag, and touch) to Mayhem over USB serial.

## Requirements

- Android Studio (latest stable)
- Android SDK Platform 36
- JDK 17
- USB OTG-capable Android phone/tablet
- HackRF One + PortaPack H2/H2+ running Mayhem firmware

## Build APK

From the project root:

```bash
./gradlew :app:assembleFreeDebug
```

Windows PowerShell:

```powershell
.\gradlew :app:assembleFreeDebug
```

Output APK:

`app/build/outputs/apk/free/debug/app-free-debug.apk`

Optional unsigned release build:

```bash
./gradlew :app:assembleFreeRelease
```

Output:

`app/build/outputs/apk/free/release/app-free-release-unsigned.apk`

## Install APK

```bash
adb install -r app/build/outputs/apk/free/debug/app-free-debug.apk
```

## Basic Use

1. Connect Android device to HackRF/PortaPack through USB OTG.
2. Open `HackRFPPH2`.
3. Grant USB permission and tap `Connect`.
4. Use on-screen controls to navigate and tune.
5. Use `Touch Lock` when you want to block accidental touches.

## Minimum Files Needed To Compile

Keep these files/folders if you want the smallest buildable source package:

- `settings.gradle.kts`
- `build.gradle.kts`
- `gradle.properties`
- `gradlew`
- `gradlew.bat`
- `gradle/wrapper/gradle-wrapper.properties`
- `gradle/wrapper/gradle-wrapper.jar`
- `app/build.gradle.kts`
- `app/proguard-rules.pro`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/**`
- `app/src/main/res/**`
- `app/src/main/assets/**` (if present)

## Notes

- `docs/`, `scripts/`, `dist/`, and test sources are not required to compile the debug APK.
- If you use Android Studio, open the project root and run the `app` module directly.

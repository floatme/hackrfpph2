# HackRFPPH2 Security Review (2026-02-25)

## Scope

- Static review of Android app configuration and PortaPack control path.
- Build/lint/test verification from local workspace.
- No live dynamic pentest against a connected phone/device in this pass.

## Checks Run

- `.\gradlew :app:assembleFreeDebug :app:testFreeDebugUnitTest`
- `.\gradlew :app:assembleFreeRelease`
- `.\gradlew :app:lintFreeDebug`

## Findings and Fixes

### S-01 (High) Release build previously used weak defaults

- Issue:
  - Release build had shrinking disabled and was configured for debug signing.
- Fix:
  - Enabled minification and resource shrinking in release build.
  - Removed explicit debug signing from release config.
- Evidence:
  - `app/build.gradle.kts:43`
  - `app/build.gradle.kts:44`

### S-02 (Medium) Backup/data-extraction exposure

- Issue:
  - App backup protections were not explicitly hardened.
- Fix:
  - Disabled app backup.
  - Disabled full backup content.
  - Added Android 12+ data extraction rules that exclude all app data from cloud/device transfer.
- Evidence:
  - `app/src/main/AndroidManifest.xml:26`
  - `app/src/main/AndroidManifest.xml:27`
  - `app/src/main/AndroidManifest.xml:28`
  - `app/src/main/res/xml/data_extraction_rules.xml:1`

### S-03 (Medium) Cleartext transport was not explicitly forbidden

- Issue:
  - Manifest did not explicitly deny cleartext HTTP transport.
- Fix:
  - Set `usesCleartextTraffic="false"`.
- Evidence:
  - `app/src/main/AndroidManifest.xml:31`

### S-04 (Medium) Notification permission safety gaps

- Issue:
  - Multiple workers called `NotificationManagerCompat.notify(...)` without runtime POST_NOTIFICATIONS guard.
  - Lint reported these as errors.
- Fix:
  - Added centralized notification permission guard.
  - Updated all worker notification call sites to use permission-safe notifier.
- Evidence:
  - `app/src/main/java/com/orbit/app/notifications/NotificationPermission.kt:13`
  - `app/src/main/java/com/orbit/app/notifications/NotificationPermission.kt:24`
  - `app/src/main/java/com/orbit/app/notifications/CheckInWorker.kt:20`
  - `app/src/main/java/com/orbit/app/focus/FocusWorker.kt:25`
  - `app/src/main/java/com/orbit/app/notifications/LeaveNowWorker.kt:24`
  - `app/src/main/java/com/orbit/app/notifications/MedicationReminderWorker.kt:79`
  - `app/src/main/java/com/orbit/app/notifications/ReminderWorker.kt:28`
  - `app/src/main/java/com/orbit/app/notifications/RoutineReminderWorker.kt:36`
  - `app/src/main/java/com/orbit/app/notifications/WrapUpWorker.kt:24`

### S-05 (Low) Input robustness hardening for keyboard injection path

- Issue:
  - Frequency input path had no explicit max length cap; hex formatting used default locale.
- Fix:
  - Added max input length cap.
  - Forced `Locale.US` for deterministic hex encoding.
- Evidence:
  - `app/src/main/java/com/orbit/app/portapack/FrequencyInputCodec.kt:6`
  - `app/src/main/java/com/orbit/app/portapack/FrequencyInputCodec.kt:9`
  - `app/src/main/java/com/orbit/app/portapack/FrequencyInputCodec.kt:32`

## Remaining Risk / Follow-up

1. `lintFreeDebug` still fails with 49 non-PortaPack errors, mostly UI/lint policy issues outside the remote module.
2. Multiple app components are intentionally exported for launcher/integration flows; keep reviewing whether each exported entry point is truly required.
3. This pass is static-only. A true pentest phase should include:
   - USB fuzzing of shell responses.
   - Broadcast/intent abuse checks on a physical test device.
   - Runtime traffic inspection and crash/ANR pressure testing.

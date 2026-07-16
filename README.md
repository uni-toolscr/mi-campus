# Mi Campus

Mi Campus is a Spanish-first, offline-first Android MVP for Universidad de Costa Rica (UCR) and Universidad Nacional de Costa Rica (UNA) students. It targets Android API 26–36 with Kotlin, Jetpack Compose, Material 3, Room, DataStore, and WorkManager.

The app provides:

- onboarding for one or both institutions;
- offline, source-attributed 2026 bus schedules with date overrides and expiry handling;
- persistent agenda/month calendar views, filters, editing, reminders, and idempotent Android Calendar export;
- SAF PDF import, API 35 embedded-text extraction, ML Kit Latin OCR fallback, editable drafts, and manual entry;
- optional Gemini Nano first, then per-import-consented cloud parsing with a user-supplied encrypted key;
- light, dark, dynamic-color, compact, and expanded layouts.

Academic calendar entries are intentionally not bundled until an official versioned dataset is supplied.

## Build and verify

Use JDK 17 and an Android SDK containing platform 36:

```bash
./gradlew lint testDebugUnitTest assembleDebug
```

With emulators or devices running API 26 and API 35+:

```bash
./gradlew connectedDebugAndroidTest
```

CI runs both commands and exercises the instrumentation suite on API 26 and 35. The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Privacy

The app works without an account. Imported PDFs and their complete extracted text are not persisted. Cloud processing is disabled by default, never sends the original PDF, and requires a fresh decision for each import. See [PRIVACY.md](PRIVACY.md) and [IMPLEMENTATION_NOTES.md](IMPLEMENTATION_NOTES.md).

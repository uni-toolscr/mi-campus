# Mi Campus

Mi Campus is a Spanish-first, offline-first Android MVP for Universidad de Costa Rica (UCR) and Universidad Nacional de Costa Rica (UNA) students. It targets Android API 26–36 with Kotlin, Jetpack Compose, Material 3, Room, DataStore, and WorkManager. Built edge to edge on Codex

The app provides:

- onboarding for one or both institutions;
- offline, source-attributed 2026 bus schedules with date overrides and expiry handling ([UCR external service](https://www.ucr.ac.cr/acerca-u/campus/bus-externo.html); [UNA peripheral service](https://www.vidaestudiantil.una.ac.cr/noticias/2653-servicio-de-periferica-para-estudiantes-desde-el-campus-omar-dengo-al-campus-benjamin-nunez-y-viceversa));
- persistent agenda/month calendar views, filters, editing, reminders, and idempotent Android Calendar export;
- multi-select SAF PDF import with an app-private local library, API 35 embedded-text extraction, ML Kit Latin OCR fallback, editable drafts, and manual entry;
- independent, default-off Gemini Nano and Google Gemini cloud controls, with local-first routing, per-batch cloud consent, automatic stable-model failover, and a user-supplied encrypted key;
- light, dark, dynamic-color, compact, and expanded layouts.

Academic calendar entries are intentionally not bundled until an official versioned dataset is supplied. The bundled assistant knowledge is limited to UNA material; it is not a general UCR/UNA knowledge base, and UCR knowledge is not bundled.

The chat works without an imported PDF. It adds the selected institution short names to the model request as hidden context while keeping the student's visible message unchanged. Bundled retrieval is filtered by institution, and the UI only opens exact `http`/`https` links extracted from the selected corpus. UNA is currently covered; UCR-specific procedures require a relevant imported document until a UCR corpus is added. Scheduling statements and `/crear-evento` or `/create-event` produce an editable proposal; nothing is saved or scheduled until the student confirms it.

## Build and verify

Use JDK 17 and an Android SDK containing platform 36:

```bash
./gradlew lint testDebugUnitTest assembleDebug
```

With an emulator or device:

```bash
./gradlew connectedDebugAndroidTest
```

GitHub Actions runs lint, debug unit tests, and a debug build. Device tests remain available to run locally. Pushing a `v*` tag creates a signed GitHub Release using the configured release-keystore secrets; the release attaches the APK and its SHA-256 checksum. The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Privacy

The app works without an account. Imported PDFs are retained only in app-private, no-backup device storage until the student deletes them; extracted text chunks used by document chat are stored locally in the backup-excluded Room database and deleted with the PDF. Both AI processors are disabled on a fresh installation. **Modelo local (Gemini Nano)** is enabled only when AICore reports the device as available, downloadable, or downloading; **API de Google Gemini (nube)** remains independent and requires a fresh decision for each selected import batch and each chat question. Cloud processing never sends the original PDF. See [PRIVACY.md](PRIVACY.md) and [IMPLEMENTATION_NOTES.md](IMPLEMENTATION_NOTES.md).

## Gemini Nano on compatible devices

Mi Campus uses the pinned `com.google.mlkit:genai-prompt:1.0.0-beta3` artifact through AICore and does not bundle model weights. Event proposals use ML Kit Structured Output (`genai-schema-compiler:1.0.0-alpha1`) when the device runtime supports it and fall back to constrained JSON with strict validation. Runtime `checkStatus()` is the compatibility authority; the app does not infer support or a Nano version from the device manufacturer or model. When available, the base-model identifier and token limit come directly from ML Kit. The first model download requires a network connection and explicit confirmation; after it completes, syllabus/event extraction can run offline while Mi Campus remains in the foreground. A locked bootloader, current Google system components, sufficient storage, and available AICore quota are required.

The optional device smoke test is skipped by default, runs on any attached device, skips unsupported devices, and never starts a download. With the model already available, run:

```bash
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.runNanoDeviceTest=true
```

Debug builds also expose **Ajustes → Diagnóstico de importación**, including an explicit **Probar Gemini Nano** action with a constant content-free request. The exported ZIP contains a redacted route trace for the latest 10 attempts or tests, including operation names, allowlisted exception categories, ML Kit error codes, numeric input sizes, Nano capability/token checks, and cloud HTTP status/fallback decisions. It never contains PDF text, prompts, responses, exception messages, stack traces, document names, API keys, or calendar data; release builds neither record nor expose this diagnostic feature.

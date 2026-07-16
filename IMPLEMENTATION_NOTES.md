# Implementation notes

The project was initialized as a nested Git repository at `/home/jafed/App/MiCampus`; the sibling `REFERENCE` repository was inspected read-only and was never modified. No commit is created by this implementation.

## Architecture and persistence

- One `:app` module keeps domain models in `core.model`, state and Compose UI in `feature.*`, persistence in `data.local`, institutional data in `data.institution`, import engines in `data.document`/`data.ai`, and Android integrations in `platform.*`.
- Immutable `StateFlow` screen states drive Compose. `NavigationSuiteScaffold` changes navigation presentation with window width, while Calendario uses side-by-side month/agenda content at 840dp and above.
- Room version 4 stores confirmed events, editable drafts, and external calendar records. Explicit 1→2→3→4 migrations preserve existing data. Draft rows never store the complete extracted text; evidence snippets are deleted with the draft after confirmation.
- DataStore persists onboarding, selected institutions, theme, reminder options, and whether optional AI is enabled.

## Transport resolution

- Versioned UCR/UNA JSON assets carry their official source URL, verification range, stops, notes, weekday/Saturday trips, inclusions/exclusions, and the UCR intermediate-term override.
- A date must first be inside the verified range. The UCR weekday override is inclusive from 2026-07-13 through 2026-08-07; Saturdays in that interval continue to use the regular Saturday timetable. Explicit exclusions suppress service and inclusions enable weekday departures. The resolver searches bounded future dates for the next verified service day.
- Expired or unknown data never produces a countdown. Academic calendar data remains pending and no entries are invented.

## PDF and AI pipeline

- SAF supplies a read-only URI. On API 35+, `PdfRenderer.Page.textContents` is used directly. Empty pages are rendered within a pixel bound and processed by the bundled Latin ML Kit recognizer. Page numbers survive normalization and overlapping token-safe chunking.
- The chunker budgets the final page-labelled prompt at 2,400 words with 120-word overlap. Gemini Nano then counts the complete instruction plus document chunk with ML Kit `countTokens()`, reserves 1,024 output tokens against the device-reported token limit, and recursively splits oversized local prompts. Local sizing or generation failures continue to per-import cloud consent instead of blocking import.
- Selectable, scanned, tabular, OCR-error, and empty-document paths are covered by JVM coordinator tests. Malformed and password-protected fixtures exercise the Android `PdfRenderer` adapter in the API 26/35 device job; permission denial is also represented by a distinct typed failure.
- `EventExtractionEngine` checks Gemini Nano capability first (`AVAILABLE`, `DOWNLOADABLE`, `DOWNLOADING`, or `UNAVAILABLE`). If local parsing cannot produce valid drafts, cloud parsing requires an explicit decision tied to that import; cancellation goes directly to manual entry.
- ML Kit Prompt `1.0.0-beta2` supports local status/download/generation in this build. Its published runtime artifact does not expose the structured-output capability/typed generation API, so local generation uses the documented fallback: a strict JSON-only prompt followed by defensive Kotlin/Gson validation. No unsupported typed API is simulated.
- Cloud requests prefer `responseMimeType: application/json` with a nullable JSON Schema. If the generate-content endpoint rejects that option, the same consented import retries with a strict JSON-only prompt and still validates in Kotlin. Requests send extracted text, never PDF bytes, use stable `gemini-3.5-flash` with `LOW` thinking, the `x-goog-api-key` header, bounded timeouts/responses, and typed invalid-key, quota, offline, server, and invalid-response failures. Keys use Android Keystore AES-GCM; ciphertext is atomically stored in `noBackupFilesDir`.
- Missing or ambiguous values stay nullable and are marked. All generated results remain editable drafts until the student explicitly confirms each one.

## Calendar and reminders

- Calendar read/write permissions are requested only from the export action. A SHA-256 content hash plus `(eventId, calendarId)` record makes export idempotent: unchanged events are skipped, changes require confirmation, and external events are never automatically deleted.
- Timed events default to 24-hour and 1-hour reminders; all-day events default to 24 hours. Past triggers are skipped. Exact alarms are used only when requested and authorized, otherwise unique WorkManager jobs provide inexact delivery. Notification/exact-alarm access is requested only when enabled in Ajustes. Boot, clock, timezone, and exact-alarm-access changes reschedule future reminders.

## Verification environment

This Codex host does not provide a system JDK/AAPT2, so verification uses a temporary Nix JDK 17 and AAPT2 without modifying the machine. The equivalent local command is:

```bash
nix-shell -p jdk17 aapt --run 'ANDROID_HOME=/home/jafed/Android/Sdk ./gradlew -Pandroid.aapt2FromMavenOverride=$(command -v aapt2) lint testDebugUnitTest assembleDebug'
```

Compose instrumentation and screenshot smoke tests compile locally. Execution is delegated to the API 26/API 35 emulator CI job when no emulator is attached.

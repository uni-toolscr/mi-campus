# MiCampus

MiCampus is a Spanish-first, offline-first Android MVP for UCR and UNA students. This nested repository is the sole ownership boundary for the app — do not modify the sibling `../REFERENCE` tree.

Fixed MVP scope: one `:app` module, application id `cr.micampus.app`, API 26–36, four primary screens (Inicio, Calendario, Transporte, Ajustes), onboarding institution checkboxes, import CTA, schedule filters, route directions/expiry, optional AI, reminders, and calendar export. Supported institutions are UCR and UNA only.

## Package boundaries

- `core.model` — platform-neutral domain models.
- `data.local` — Room and DataStore persistence.
- `data.institution` — bundled UCR/UNA schedules and resolver precedence.
- `data.document` / `data.ai` — import/parsing abstractions and cloud consent/key handling.
- `platform.calendar` / `platform.reminders` — the only Android provider/WorkManager adapters.
- `feature.*` — screen state; `core.designsystem` — Material 3 styling.

Dependencies flow toward `core.model`. Platform integrations must not leak into domain models.

## Build & verify

Run `./gradlew test` and `./gradlew assembleDebug` when a JDK/SDK is available; note explicitly if tooling is unavailable rather than skipping silently. Before handoff, inspect `git diff` and verify:
- backup exclusion rules
- calendar permission timing (requested only at export, not startup)
- AI model/endpoint (`gemini-3.5-flash`, `LOW` thinking, `x-goog-api-key` header)
- resolver override dates (latest `effectiveFrom` covering a date wins)

Keep user data local by default; cloud AI requires explicit user consent.

For each major screen, check: Spanish labels, TalkBack content descriptions, minimum touch targets, portrait layout, dark theme contrast, offline empty/error states, and screenshot smoke coverage. Run lint and unit tests; run instrumentation tests when an emulator is available. Review manifest permissions and privacy docs before release.

## Reference boundary

`../REFERENCE` is read-only inspiration only — never copy implementation files, assets, branding, logos, artwork, or license text from it. Record any audited patterns and official source URLs in `REFERENCE_AUDIT.md`. When linking to reference material, preserve its license.

## Key facts (from implementation notes)

- Room stores confirmed events, draft imports, and `(eventId, calendarId)` export records for idempotency.
- `CalendarExporter` checks READ/WRITE calendar permission at export time only.
- Reminder scheduling uses WorkManager with a graceful immediate fallback for past times.
- Auto Backup excludes `noBackupFilesDir`, databases, and preferences via both legacy and Android 12+ extraction rules.
- In restricted/sandboxed build environments, Gradle may fail to launch (no JDK, or AAPT2 can't load the Nix dynamic linker) — fall back to source/config review and resolver unit tests as verification evidence, and note that `./gradlew test`/`assembleDebug` need a standard Android SDK/JDK host.

# Delegation policy

The main Fable agent is the project architect, orchestrator, and final
integrator. Preserve its context and usage for work that benefits from
high-level reasoning.

## Delegate to explorer

Use the explorer agent for:

- locating files and symbols
- targeted repository investigation
- tracing bounded code paths
- dependency and configuration discovery
- running simple commands or tests
- summarizing logs and tool output

## Delegate to implementer

Use the implementer agent for:

- bounded feature implementation
- nontrivial but well-defined bug fixes
- focused refactoring
- writing or expanding tests
- iterative implementation and validation

## Delegate to reviewer

Use the reviewer after substantial implementation when an independent review
is likely to catch meaningful defects.

## Retain in the main agent

The main agent retains responsibility for:

- interpreting the user's actual objective
- architectural and cross-cutting decisions
- decomposing work into non-overlapping assignments
- defining acceptance criteria
- reconciling conflicting findings
- reviewing important diffs
- integrating changes
- final validation and user-facing reporting

## Delegation threshold

Delegate only when delegation replaces meaningful main-agent work.

Do not delegate:

- trivial edits
- one-file changes that are already fully understood
- single symbol lookups
- one or two obvious commands
- tasks whose explanation would cost more than direct execution
- overlapping investigations
- work the main agent will immediately repeat

## Assignment requirements

Every delegated task must specify:

- objective
- exact scope
- relevant files or subsystem, when known
- constraints
- expected deliverable
- validation requirements
- whether edits are permitted

Agents must return concise findings and avoid dumping large file contents into
the main context.

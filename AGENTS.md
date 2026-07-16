# MiCampus repository governance

## Mission and scope

MiCampus is a Spanish-first, offline-first Android MVP for UCR and UNA students. The nested repository is the sole ownership boundary for the app; do not modify the sibling `../REFERENCE` tree.

Fixed MVP: one `:app` module, application id `cr.micampus.app`, API 26–36, four primary screens (Inicio, Calendario, Transporte, Ajustes), onboarding institution checkboxes, import CTA, schedule filters, route directions/expiry, optional AI, reminders, and calendar export. Supported institutions are UCR and UNA only.

## Package boundaries

- `core.model` contains platform-neutral domain models.
- `data.local` owns Room and DataStore persistence.
- `data.institution` owns bundled UCR/UNA schedules and resolver precedence.
- `data.document` and `data.ai` own import/parsing abstractions and cloud consent/key handling.
- `platform.calendar` and `platform.reminders` are the only Android provider/WorkManager adapters.
- `feature.*` owns screen state; `core.designsystem` owns Material 3 styling.

Dependencies should flow toward `core.model`; platform integrations must not leak into domain models.

## Mandatory checks

Before handoff, inspect `git diff`, run `./gradlew test` and `./gradlew assembleDebug` when a JDK/SDK is available, and document unavailable tooling. Verify backup exclusion rules, calendar permission timing, AI model/endpoint, and resolver override dates. Keep user data local by default and require explicit consent for cloud AI.

For each major screen, check Spanish labels, TalkBack content descriptions, minimum touch targets, portrait layout, dark theme contrast, offline empty/error states, and screenshot smoke coverage. Run lint and unit tests; instrumentation is required when an emulator is available. Review manifest permissions and privacy docs before release.

## Reference boundary

`../REFERENCE` is read-only inspiration only. Record any audited patterns and official source URLs in `REFERENCE_AUDIT.md`; never copy implementation files or assets.
Do not copy branding, logos, artwork, or license text from samples; preserve their licenses when merely linking to them.

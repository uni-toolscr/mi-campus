# Plan: open HTML-backed course resources as links

## Problem

In **Contenidos**, Moodle modules are grouped by course and section. The catalog mapper currently converts every entry in a module's `contents` array into a downloadable file. When Moodle represents a browser-oriented resource as `text/html` (or an `.html`/`.htm` file), the UI therefore presents it with the file download action. Downloading that HTML is not a useful way to consume the resource; the user should open the parent Moodle module URL in a browser, as link, page, forum, and other navigable resources already do.

The affected flow is:

1. `MoodleContentMapper.toResource` in `app/src/main/java/cr/micampus/app/data/moodle/MoodleApi.kt` maps module contents to `ResourceFile` records without distinguishing browser-oriented HTML.
2. `MoodleContentRepository` persists those records as Moodle files.
3. `ResourceRow` and `ContentFileRow` in `app/src/main/java/cr/micampus/app/feature/contents/ContentsScreen.kt` treat nested files as downloads and call `onOpenFile`.
4. The working browser path already exists: `ContentsViewModel.openResource` emits `ContentsEffect.OpenUrl`, and `MainActivity` opens it with `Intent.ACTION_VIEW`.

## Implementation approach

### 1. Add explicit HTML-resource classification at the API mapping boundary

Update `MoodleContentMapper` in `app/src/main/java/cr/micampus/app/data/moodle/MoodleApi.kt` with a small, testable helper that recognizes browser-oriented HTML by:

- MIME type `text/html`, case-insensitively and without depending on optional MIME parameters.
- `.html` or `.htm` filename as a fallback when Moodle omits or misreports the MIME type.

Only promote an HTML file to browser navigation when the parent module has a valid canonical HTTPS module URL. This avoids creating an action that cannot be completed. If the URL is absent or invalid, preserve the existing file behavior as a fallback rather than dropping the content.

For a file-style module whose sole meaningful content is HTML:

- retain the module title, availability, ordering, and canonical module URL;
- expose it as a navigable resource (using the existing page/link presentation semantics rather than a nested downloadable file);
- do not emit the HTML entry as a `ResourceFile`.

Keep normal files (PDF, Office documents, notebooks, archives, and so on) and folder contents on the existing download path. Do not reinterpret a mixed folder or multi-file module wholesale because it happens to contain an HTML file; scope the normalization to the single HTML-backed resource pattern so unrelated downloads are not lost.

### 2. Reuse the existing browser-opening UI path

Confirm the normalized resource is actionable in `ResourceRow` in `app/src/main/java/cr/micampus/app/feature/contents/ContentsScreen.kt` and displays the existing external-link affordance. Tapping it must call `onOpenResource(resource.url)`, never `onOpenFile`.

Prefer representing the corrected behavior in the mapped resource kind so the current UI logic can handle it without a second MIME-based decision. If a small UI adjustment is still required, keep the action choice explicit and derived from the resource model; do not infer behavior independently in multiple composables.

No new intent handling should be added. Continue through `ContentsViewModel.openResource` so HTTPS validation and `ContentsEffect.OpenUrl` remain the single browser-navigation path.

### 3. Reconcile cached catalog data through normal refresh

No Room schema migration should be necessary: the resource URL and kind are already persisted, and catalog replacement already removes file rows that are no longer present in the mapped snapshot.

Verify that the next successful content refresh:

- replaces the old HTML `MoodleFileEntity` with the navigable resource representation;
- removes any stale star entry through the existing file/star reconciliation;
- removes any stale downloaded HTML bytes through the existing catalog replacement cleanup;
- leaves unchanged downloaded documents intact.

If those guarantees are not already covered by reconciliation tests, extend `app/src/androidTest/java/cr/micampus/app/data/local/MoodleContentReconciliationDeviceTest.kt` with the minimal transition case.

## Regression tests

### Mapper unit tests

Add focused cases to `app/src/test/java/cr/micampus/app/data/moodle/MoodleClientTest.kt` using the existing fake Moodle transport:

1. A `resource` module with a module URL and `text/html` content produces one navigable resource, preserves the canonical module URL, and produces no downloadable file.
2. `.html` and `.htm` filenames receive the same treatment when MIME type is missing.
3. MIME matching is case-insensitive and tolerates parameters such as `text/html; charset=utf-8`.
4. An ordinary PDF resource remains a `ResourceKind.FILE` with one downloadable file.
5. An HTML entry without a usable module URL follows the documented fallback and is not silently discarded.
6. A folder or mixed-content module is not accidentally converted into a single browser link.

The first case is the primary red/green regression signal for the reported bug.

### Compose behavior test

Add a case to `app/src/androidTest/java/cr/micampus/app/feature/contents/ContentsScreenBehaviorTest.kt` that renders the normalized resource, expands its course and section, taps it, and asserts:

- `onOpenResource` receives the expected Moodle module URL;
- `onOpenFile` is not called;
- the row exposes the existing **Abrir en Aula Virtual** accessibility action/description rather than a download affordance.

Retain the existing file test to prove that PDF/document rows still invoke `onOpenFile`.

## Validation

Run the narrow checks first:

```bash
./gradlew testDebugUnitTest --tests 'cr.micampus.app.data.moodle.MoodleClientTest'
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cr.micampus.app.feature.contents.ContentsScreenBehaviorTest
```

Then run the project-level checks before merging:

```bash
./gradlew lint testDebugUnitTest assembleDebug
```

If a device or emulator is available, finish with the full instrumentation suite:

```bash
./gradlew connectedDebugAndroidTest
```

## Acceptance criteria

- HTML-backed Moodle resources in **Contenidos** open their parent Aula Virtual URL through the browser instead of attempting a file download.
- The module URL is canonical and contains no Moodle access token.
- Normal downloadable course files and folders keep their current offline download, starring, progress, and deletion behavior.
- Restricted or disabled resources remain non-actionable.
- Previously cached HTML-file entries are corrected after a successful refresh without a database migration.
- Unit and Compose regression tests cover both the corrected HTML behavior and the unchanged document-download behavior.

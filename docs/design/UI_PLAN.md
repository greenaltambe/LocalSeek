# UI overhaul plan (task S, branch `ui-overhaul`)

Scope: user-facing UI only. Retrieval, indexing logic, ml, eval and androidTest are untouched.
All filtering, formatting and icons happen in `ui/` and `tools/` on already-retrieved results.

## Current structure (S0 findings)

- `SearchApp.kt`: hand-rolled route enum (SEARCH, SETTINGS, ABOUT, PERFORMANCE, QRELS_*) with `BackHandler`;
  one `Surface`; each screen owns its `Scaffold`. No navigation library in the dependency list.
- `ui/SearchScreen.kt` (745 lines): `TopAppBar`, `SearchInput` (pill `CircleShape`), two dropdown filter chips,
  `ToolsPanel`, `LazyColumn` of `FileResultCard` + `ResultActionsRow`. Whole-screen `imePadding()` on the
  content Box plus Scaffold padding. Web chips (`ToolsPanel.WebSearchBar`) are shown only when
  `results.isEmpty()`, so they vanish as soon as results load.
- `ui/SearchResultCard.kt`: card with relevance `%` always shown, date always shown (`yyyy-MM-dd`, raw ms),
  file size, type pill; icons are generic Material icons; thumbnails for images.
- `ui/ContactActionsRow.kt`: horizontally scrolling text-only buttons (clipped on narrow screens).
- `ui/theme`: `ExpressiveShapes` 8/12/20/28/36, `rememberPressMorph` (24 dp pill-ish buttons), `EntityStyle`.
- `ui/settings/SettingsScreen.kt` (940 lines): one long `LazyColumn` with every section; `AppSettings`
  in DataStore `app_settings`; tools data (engines, aliases, pins, theme, one-handed) in `tools_settings`.
- Indexing: `IndexWorker` (frozen except notification code) runs `FileIndexer.runFullIndex` (frozen, no
  progress callback). Banner text comes from `IndexingStatusMapper` (label only, spinner).
- Web: `WebLauncher` opens a Custom Tab (androidx.browser already a dependency; no WebView, no INTERNET).

## Bugs confirmed in code

1. Performance metrics setting ignored: `FileResultCard` always prints `%`; `showScore` is gated on `BuildConfig.DEBUG`.
2. Image date 1970: image `modifiedAt` is seconds; `formatDate` treats it as ms.
3. Web chips hidden when results exist (`showWebSearchBar = results.isEmpty() && ...`).
4. Keyboard: `imePadding()` on the content Box and Scaffold insets both apply; list collapses.
5. Reranking subtitle claims "+15% quality (+150 ms)". Default is `true` in `AppSettings` and `SettingsRepository`
   (both in `ui/settings/`, not frozen). `RetrievalConfig.DEFAULT` (frozen, benchmarks) is NOT changed.
6. "Clear all data" only resets the settings DataStore (`app_settings`); it does not delete the index or
   pins/engines. The confirmation dialog will say exactly that.

## Phases

- **S1 Search screen**: new cards (12 dp, 1 dp outline, 8 dp gap); section headers with counts; icons per type
  (`ui/ResultIcons.kt`: app icon LruCache off-main, contact photo + letter avatar, file-type icons);
  metadata rules (`ui/ResultFormat.kt`, pure + unit-tested: seconds/ms detection, relative date, parent folder);
  contact actions in a `FlowRow` with icon + label; type chip row + Filters bottom sheet, filter/sort logic in a
  pure `ResultFilters.kt` (client-side; the retrieval call is not changed); pinned web row with engine icons;
  prefix chip in the search bar (`PrefixParser`, pure); "Open in" setting; unified answer card;
  edge-to-edge + `adjustResize`, one-handed layout with `reverseLayout`; empty state with pinned + recent
  searches (stored in `tools_settings`, never exported/logged); no-results and error actions; long-press sheet.
- **S2 Mascot**: Hazel icon sources in `docs/design/icon_candidates/` (round-1 candidates were archived on 2026-10-05; round-2 variant A is the shipped icon)
  vector (background, foreground, monochrome) and the Android 12 splash (same drawable, already wired);
  `Hazel` composable (Canvas), loader with 150 ms delay and reduced-motion check; "Mascot and playful text" toggle.
- **S3 Indexing**: slim banner under the app bar (state machine `IndexBannerState`, pure + tested); notification with
  progress, channel "Indexing", completion notification, mode setting; POST_NOTIFICATIONS requested in context once.
- **S4 Settings**: home screen of cards -> sub-pages with a small in-app route stack (see decisions); reranking text +
  default OFF; "Stats" button; engine icon keys (backward compatible JSON); prefix targets `a c f i s`.
- **S5 QA**: contrast unit test, semantics, tablet max width, haptics, lint, R8 mapping check, size breakdown,
  emulator smoke (if one exists), resume checklist.
- **S6**: at most three ideas that need no frozen file and no permission.

## Decisions made where the brief left room

- **No `navigation-compose` dependency.** It is not in the Gradle cache/version catalogue and the brief forbids
  heavy deps; a typed route stack with `BackHandler` (predictive back already enabled in the manifest) and
  `AnimatedContent` gives the same behaviour. Settings stays one `NavHost`-equivalent in `SearchApp.kt`.
- **Determinate indexing progress without touching `FileIndexer`**: `IndexWorker` (notification code only) polls
  the index counts while the unchanged `runFullIndex` runs, and uses the last completed run's total as the estimate.
  First ever run has no estimate and shows an indeterminate bar with "N files indexed so far".
- Mascot art is drawn in code (Canvas / vector XML); no PNG in the APK; previews are docs-only.

## Frozen-file blockers (so far)

- `FileIndexer` has no progress hook (frozen): handled with polling as above; exact "X of Y" is an estimate.
- Hit type/date retrieval parameters: `SearchEngine.search(query, config)` takes none, so all filters are client-side.
- `IndexScheduler.TAG_ONE_TIME` is private (IndexingStatusMapper already mirrors it): left as is.

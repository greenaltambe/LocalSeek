# MANAGE_EXTERNAL_STORAGE (All files access): declaration draft and fallback plan

Policy checked 2026-10-03 against Google's "All files access" help page
(https://support.google.com/googleplay/android-developer/answer/10467955). It lists permitted core use cases,
including **"Search (On Device): App's core purpose is to search through files and folders across the device's
external storage"**. Apps must complete the Permissions Declaration Form and receive approval from Google Play.
Approval is not guaranteed; the self-assessment below still applies. That page did not mention a video
requirement; the Console form may still ask for one, so a script is in `DEMO_VIDEO_SCRIPT.md`.

## Honest self-assessment (read first)

The code only scans **Documents and Download** (`FileIndexer.scanRoots`). A reviewer can argue this is
achievable with the Storage Access Framework or `MediaStore.Files`/`Downloads`, which weakens the "core
functionality requires it" claim. Rejection risk is real. Decide whether to submit with the permission or ship
the SAF variant first. The exact code paths that need all-files access and a 4 to 6 day estimate for the SAF
variant are in `docs/investigations/T9_INVESTIGATIONS.md`, section d.

## What changed since the first draft

- **Onboarding.** On first run a skippable tour asks for contacts, all-files access and photos one at a
  time. Each page explains why, says it is optional, and has "Not now". The permission request is the
  existing one (the system all-files settings screen on API 30+); the app keeps working without it (apps,
  contacts, tools, web shortcuts). "Replay onboarding" is in Settings.
- **No network.** The merged debug and release manifests contain no `INTERNET` permission.
- **Backup.** `allowBackup="false"`, extraction rules exclude everything; contact pins are not exported.

## Draft justification text (for the Console declaration form)

> **Core functionality:** LocalSeek is an on-device search app. Its single purpose is to let the user
> search the text content and names of documents stored on their device (PDF and plain-text formats)
> together with installed apps and contacts. To do this it builds a private full-text and semantic
> index of the user's documents.
>
> **Why All files access is required:** Documents can be stored by any app in any folder of shared
> storage, and the index must be kept up to date in the background as files are added, edited or
> deleted, without asking the user to re-pick files each time. MediaStore does not expose the content
> of non-media documents to an app that did not create them, and per-file SAF picking cannot
> continuously index a folder tree in a background worker.
>
> **Data handling:** All processing happens on the device. The app declares no INTERNET permission.
> File contents and the index are never transmitted and are excluded from backup. The permission is
> requested only after an in-app explanation, is optional, and the app remains usable (apps, contacts,
> tools) without it.
>
> **Video / demo:** Play requires a short video showing the permission prompt and the core feature.
> Script: first launch, onboarding page "Search your documents" with the rationale, tap Allow, the Android
> all-files screen, enable, return; open search, type a phrase that appears inside a demo PDF, show the result
> with the highlighted snippet; open Settings, About, show "No internet permission".

## Fallback: Storage Access Framework (folder picker). NOT implemented

Design: the user chooses folders with `ACTION_OPEN_DOCUMENT_TREE`; the app persists the tree URIs with
`takePersistableUriPermission` and indexes via `DocumentsContract` instead of `java.io.File`.

| File | Change |
|---|---|
| `AndroidManifest.xml` | remove `MANAGE_EXTERNAL_STORAGE`; keep `READ_EXTERNAL_STORAGE` (max 29) only if API 26-29 needs it |
| `MainActivity.kt` | replace `isExternalStorageManager()` checks and the all-files intent (also the `FILES` step of onboarding) with a folder-picker flow |
| `indexing/FileIndexer.kt` | `scanRoots` + `walkTopDown()` become a walk over persisted tree URIs |
| `indexing/DocumentParser.kt` | `parse(File)` / `canParse(File)` need `InputStream`/URI variants |
| `data/` Document entity, `core/IdentityUtils.kt` | `filePath` is the identity; URI identity changes stable keys, so the index is rebuilt and **benchmark stable keys change** |
| `ui/FileOpener.kt`, `ui/SearchViewModel.kt` | open the document URI directly with `FLAG_GRANT_READ_URI_PERMISSION` |
| `res/xml/file_paths.xml` | the `external-path "."` entry becomes unnecessary |
| Settings UI | manage granted folders; `releasePersistableUriPermission` |

Most of this lives in frozen packages, so it belongs after the paper freeze. Suggested compromise: ship v1.0
with the permission if approved; keep SAF as the v1.x fallback branch.

Photos: `READ_MEDIA_VISUAL_USER_SELECTED` partial access is not handled (lint `SelectedPhotoAccess`); continuous
image indexing needs the full permission, so check Play's photo-and-video permissions policy (VERIFY).

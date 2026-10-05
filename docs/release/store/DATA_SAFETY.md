# Google Play Data safety: proposed answers

Derived from `app/src/main/AndroidManifest.xml` and the code (re-checked on branch `release-prep-r`: `aapt2 dump permissions` on the release APK shows 0 INTERNET entries). Play's exact form
wording changes over time: VERIFY each answer in the console. Re-check after any change that adds a
permission or a dependency.

## Form answers

| Question | Proposed answer | Basis |
|---|---|---|
| Does your app collect or share any of the required user data types? | **No** | No `INTERNET` permission in the merged debug and release manifests (checked on this branch) and no network code; all processing is local. Play's definition of "collect" is transmitting data off the device. VERIFY that this reading holds for the on-device index of contacts and files. |
| Data collected | None | as above |
| Data shared | None | as above |
| Is all data encrypted in transit? | Not applicable (no data leaves the device) | no network |
| Do you provide a way for users to request data deletion? | Yes: uninstall the app or clear its storage in system settings; the index lives only in app-private storage | backup disabled, see below |
| Independent security review | No | |
| Privacy policy URL | Required: publish `docs/index.html` via GitHub Pages (or any public URL) and use that link | `PRIVACY.md` is the source text |

Backup and transfer: `android:allowBackup="false"`, plus `data_extraction_rules.xml` and `backup_rules.xml`
that exclude every domain, so the index and preferences do not leave the device through Google backup or
device-to-device transfer. The optional settings export is user-initiated (Storage Access Framework file
the user chooses), contains only engines, prefixes, non-contact pins and theme, and **contact pins are
excluded from the export and dropped on import** (`SettingsBackup`).

Optional model pack: image search needs a ~290 MB CLIP model delivered as an **on-demand Play Asset Delivery
pack** (`:clip_model`). Google Play downloads and installs it; the app has no network access and does not
send anything. This is Play's standard delivery, not data collection by the app. VERIFY that using the
`asset-delivery` library does not need a disclosure in the current form.

Caveats to state in the privacy policy rather than the form:
- The research/debug UI (benchmark and qrels export, performance dashboard) is compiled in but hidden in
  release builds (`BuildConfig.DEBUG` gating in `SearchApp`, `SettingsScreen`, `SearchScreen`).
- Logcat contains indexing diagnostics; check that no titles or contact names are logged in release.

## Permissions and where the code uses them

| Permission | Manifest | Use in code | Notes |
|---|---|---|---|
| `MANAGE_EXTERNAL_STORAGE` | yes | `MainActivity` (`isExternalStorageManager`, all-files settings intent, also reached from onboarding); `indexing/FileIndexer` walks Documents and Download with `java.io.File` | Restricted by Play; needs the declaration form. See `PERMISSION_DECLARATION.md`. |
| `READ_EXTERNAL_STORAGE` (max SDK 29) | yes | legacy file/photo read on API 26 to 29 | |
| `READ_MEDIA_IMAGES` | yes | requested from onboarding or at start; `indexing/ImageIndexer` queries `MediaStore.Images` and computes CLIP embeddings | Play photo/video permissions policy applies (lint `SelectedPhotoAccess`). VERIFY. |
| `READ_CONTACTS` | yes | requested from onboarding or at start; `indexing/ContactIndexer` reads names/numbers into the local index; call/message buttons use the stored number | Sensitive permission; declare in the form. Onboarding shows the rationale first. |
| `FOREGROUND_SERVICE` | yes | WorkManager `SystemForegroundService` used by `indexing/IndexWorker` (`setForeground`) | |
| `FOREGROUND_SERVICE_DATA_SYNC` | yes | `foregroundServiceType="dataSync"` | Declaration text and the Android 15 six-hour limit are in `docs/investigations/T9_INVESTIGATIONS.md`. |
| `POST_NOTIFICATIONS` | **not declared** | the indexing worker shows a foreground notification | On API 33+ the notification is hidden from the shade without it but the service runs. VERIFY. |
| `QUERY_ALL_PACKAGES` | not declared | `AppIndexer` uses a `<queries>` MAIN/LAUNCHER intent; three `<package>` entries detect messenger apps for contact shortcuts | Avoids a restricted permission. |
| `INTERNET` | **not declared** | none | Web search and the GitHub links open the user's browser (Custom Tabs). |
| `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED` | added by WorkManager at manifest merge | scheduling of the 6-hour job | Not in the app's own manifest. |
| `BIND_QUICK_SETTINGS_TILE` | a permission the tile service requires of the system | `quick/SearchTileService` | Not a `uses-permission`. |

## Third-party libraries

LiteRT (`com.google.ai.edge.litert:litert:1.0.1`), PDFBox-Android, Room 3 (alpha), WorkManager, DataStore,
Compose, Play Asset Delivery (`asset-delivery-ktx`), AndroidX Browser (Custom Tabs). No analytics, ads or
crash-reporting SDKs; Play Asset Delivery is the only Google Play library. Licenses are listed in
`THIRD_PARTY_NOTICES.md` and shown in the app (Settings, About).

# Overnight 2, T9: investigations (report only, no code changed)

Facts come from the repository and from the release artifacts built on this branch. Anything I could not
confirm from the repo is marked **[VERIFY]**.

## a. The BouncyCastle/PQC packaging exclude

`app/build.gradle.kts` has `packaging.resources.excludes += "org/bouncycastle/pqc/crypto/**/*.properties"`.

**What it removes.** PDFBox-Android 2.0.27.0 pulls `bcprov-jdk15to18:1.72` (plus `bcpkix`/`bcutil`). Inspecting
that jar, the glob matches exactly five resource files, about 8.2 MB raw / 4.1 MB compressed:

| Resource | Used by |
|---|---|
| `org/bouncycastle/pqc/crypto/picnic/lowmc.properties` | Picnic signature constants (`LowmcConstants`) |
| `org/bouncycastle/pqc/crypto/sike/p434, p503, p610, p751.properties` | SIKE key-encapsulation parameter tables |

(Other BouncyCastle versions in the Gradle cache, 1.77 and 1.80.2, carry smaller `lowmcL*.bin.properties`
files; they are not on this app's classpath.) Nothing in the app or in PDFBox's text path references these
algorithms. They are loaded lazily via `getResourceAsStream` only if Picnic or SIKE classes are instantiated.

**Can PDFBox-Android open encrypted PDFs without it?** Expected yes, for every case the app can meet:

- `DocumentParser.extractTextFromPdf` calls `PDDocument.load(file)` with no password, so only PDFs with an
  empty user password can ever be opened (owner-password-only PDFs, which are common).
- PDFBox 2.0's `StandardSecurityHandler` (RC4, AES-128, AES-256 R5/R6) uses `MessageDigest` and `Cipher`
  from the JDK/Android, not BouncyCastle PQC classes. BouncyCastle is used for `PublicKeySecurityHandler`
  (certificate-encrypted PDFs) and signature handling, which do not touch `pqc/crypto` resources.
- PDFs that need a user password throw `InvalidPasswordException`; it is caught, the body is empty and
  only the file name is indexed (existing behaviour, unrelated to the exclude).
- `PDFTextStripper` refuses text extraction when the owner permissions forbid it
  (`AccessPermission.canExtractContent()` false); also caught, name-only indexing. **[VERIFY against 2.0.27 source]**

I did not run this: PDFBox-Android needs `PDFBoxResourceLoader.init(Context)`, so the proof has to run on a
device/emulator, and `androidTest` is a frozen path for this run.

**Proposed test (do not remove the exclude).**
1. Generate three small fixtures with desktop PDFBox or `qpdf`: AES-256 (R6) with empty user + owner password,
   RC4-128 with empty user password, and AES-256 with a real user password. Put them in test assets with the
   same sentence in each.
2. Instrumented test (`PdfEncryptionTest`, run against the **release** variant via
   `testBuildType "release"` so R8 and the exclude are both active): call `PDFBoxResourceLoader.init`, then
   `DocumentParser.parse(file)`.
3. Expect: the first two return the sentence; the third returns `null` without throwing.
4. Also assert `getResourceAsStream("/org/bouncycastle/pqc/crypto/sike/p434.properties")` is `null` in the
   release APK, proving the exclude is active, so a future BouncyCastle bump that starts needing them fails loudly.

## b. Foreground service type `dataSync`

**Where.**
- `AndroidManifest.xml`: `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC` permissions, and WorkManager's
  `SystemForegroundService` overridden with `android:foregroundServiceType="dataSync"` (`tools:node="merge"`).
- `indexing/IndexWorker.kt`: `doWork()` calls `setForeground(createForegroundInfo())` first; on API 29+ the
  `ForegroundInfo` carries `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC`. A failure to go foreground is caught
  and the worker continues as a plain background worker.
- Triggers (`indexing/IndexScheduler.kt`): one-time work `index_once` (first run, permission grants, Rebuild in
  Settings) and a periodic job every 6 hours with `requiresBatteryNotLow`. Both use `IndexWorker`.

**Android 15 six-hour limit.** From API 35, `dataSync` foreground services get about 6 hours of foreground time
per 24 hours. When it is used up the system calls `Service.onTimeout(startId, fgsType)`; the service must call
`stopSelf()` within a few seconds or the app crashes with `RemoteServiceException$ForegroundServiceDidNotStopInTimeException`.

**Current handling: none in app code.** The project uses `androidx.work:work-runtime-ktx:2.9.1`. As far as I
recall, WorkManager 2.10.0 is the release that added `SystemForegroundService.onTimeout` handling (it stops the
worker with `STOP_REASON_FOREGROUND_SERVICE_TIMEOUT`) **[VERIFY in the 2.10.0 release notes]**. On 2.9.1 a
single indexing run that stays foreground beyond the limit would crash the app. This is realistic: the first
index on the owner's phone took hours. Mitigations, none applied tonight (a dependency bump may touch the
indexing trigger path):
1. Bump WorkManager to a stable 2.10+ release and confirm the timeout path in the release notes.
2. Make `IndexWorker` checkpoint: `FileIndexer` already skips unchanged files, so a stopped run resumes cheaply;
   return `Result.retry()` when stopped with the timeout reason.
3. Test on an Android 15+ device with a long forced reindex (Settings, Rebuild) and watch logcat after 6 h of
   foreground time; I know of no shortcut to trigger the timeout sooner **[VERIFY: check the Android 15 FGS docs]**.

**Play declaration text (Foreground service permissions form, type "Data sync").** Android's documentation
lists local file processing under `dataSync`, so the type fits; state it plainly:

> LocalSeek builds a private, on-device search index of the user's documents, contacts, installed apps and
> (optionally) photos. Reading and embedding thousands of files takes from minutes to several hours on the
> first run, so the work runs as a foreground service with a visible "LocalSeek Indexing" notification and
> the user can see it in the task manager. It is started by the user's first launch, by granting a permission,
> by pressing "Rebuild" in Settings, and by a periodic job every 6 hours while the battery is not low. No data
> is transferred off the device: the app has no INTERNET permission. If the system stops the service the work
> resumes on the next run, skipping unchanged files.

Video to attach: launch, grant the permission, show the notification and the indexing banner, then a search.

## c. SDK levels and raising targetSdk

| Setting | Value |
|---|---|
| `compileSdk` | 36 |
| `targetSdk` | 36 |
| `minSdk` | 26 |

Lint `OldTargetApi` reports that a newer API level exists. A Compose BOM newer than 2026.03.00 already
demands `compileSdk` 37, so 37 is published. You said you will verify Play's requirement; the repo cannot
tell me the deadline. **[VERIFY]**

Behaviour changes to check if `targetSdk` goes to 37 (from my memory of the Android 17 notes; **all
[VERIFY] against developer.android.com/about/versions/17/behavior-changes-17**):
- Compose BOM and `androidx.activity` must be recent enough; raising `compileSdk` is required first (separate
  from `targetSdk`). Room/SQLite/LiteRT are frozen, so confirm they still build with compileSdk 37.
- Edge-to-edge and predictive back are already enforced at 36; this app handles both (`enableEdgeToEdge`,
  `enableOnBackInvokedCallback`, insets per Scaffold).
- Foreground service rules: keep `dataSync` and handle `onTimeout` (see b).
- Media access: Android 14+ partial photo access (`READ_MEDIA_VISUAL_USER_SELECTED`) is not handled; lint
  `SelectedPhotoAccess` already warns.
- `MANAGE_EXTERNAL_STORAGE` still works but Play review is the gate (see d).
- Anything touching `WorkManager` constraints/quotas, implicit-intent restrictions, large-screen orientation
  and resizability overrides (affects the widget/tile only indirectly).
- Re-test: tile service `startActivityAndCollapse`, widget `PendingIntent` flags, notifications
  (`POST_NOTIFICATIONS` is not declared; the FGS notification is hidden on API 33+ without it).

## d. MANAGE_EXTERNAL_STORAGE versus SAF

Only Documents and Download are scanned (`FileIndexer.scanRoots`). Exact code paths that depend on all-files
access:

| Path | What needs it |
|---|---|
| `indexing/FileIndexer.kt` `scanRoots`, `runFullIndex` (`walkTopDown`, `file.lastModified()`), line ~199 `rootsAccessible` | listing and reading arbitrary files under `/storage/emulated/0/{Documents,Download}` with `java.io.File` |
| `indexing/DocumentParser.kt` `parse`/`canParse` | `File.canRead()`, `inputStream()`, `PDDocument.load(File)` |
| `ui/SearchViewModel.kt` `openFile` (~L346) and `ui/FileOpener.kt` | `File(path).exists()` then `FileProvider.getUriForFile` |
| `res/xml/file_paths.xml` | `<external-path path=".">` exposes all external storage through the FileProvider, which only works because the app can read it |
| `MainActivity.kt` | `Environment.isExternalStorageManager()` checks and the all-files settings intent (and my onboarding bridge) |
| Identity | `Document.filePath` is the key (`getDocumentByPath`, stable keys in `core/IdentityUtils`) |

Not dependent on it: apps, contacts, images (`MediaStore` + `READ_MEDIA_IMAGES`), tools, web, settings.

**SAF variant estimate.** Persisted tree URIs from `ACTION_OPEN_DOCUMENT_TREE` (Documents, Download), walk with
`DocumentsContract` child queries, parse from `InputStream` via `ContentResolver`, open results by document URI.
Roughly 4 to 6 working days: folder picker + persisted-permission settings UI (1 d), URI walker with change
detection (1 to 1.5 d), `DocumentParser` stream overloads + PDFBox from stream (0.5 d), identity/stable-key
migration and forced reindex (1 d), open-file path and FileProvider cleanup (0.5 d), tests and device checks (1 d).
Most of it is in frozen packages (`indexing/`, `data/`, `core/IdentityUtils`), changes stable keys and therefore
invalidates qrels, and the user must pick folders once. Do it after the paper freeze.

## e. Release bundle size (built tonight, unsigned)

| Artifact | Size |
|---|---|
| `app-release-unsigned.apk` (universal, 4 ABIs) | 92.0 MB |
| `app-release.aab` | 362 MB: `clip_model` pack 280 MB, `base` 69.7 MB, `BUNDLE-METADATA` (R8 map, debug symbols) 12.3 MB |

The CLIP pack is on-demand Play Asset Delivery; it is not part of the install size. Estimated user install
(arm64, base split): about 61 MB of model assets + 3.5 MB TFLite + 1.1 MB SQLite + 1.9 MB dex, roughly 68 MB.
The earlier `docs/release/APK_SIZE_REPORT.md` (69 MB universal) predates the larger FP16 cross-encoder
(now 45.3 MB stored uncompressed).

Top 15 contributors in the release APK (compressed MB; `.tflite` files are stored, not deflated):

| # | File | MB |
|---|---|---|
| 1 | assets/models/cross_encoder.tflite | 45.31 |
| 2 | assets/minilm_optimized.tflite | 22.73 |
| 3 | lib/x86_64/libtensorflowlite_jni.so | 4.69 |
| 4 | lib/x86/libtensorflowlite_jni.so | 4.61 |
| 5 | lib/arm64-v8a/libtensorflowlite_jni.so | 3.55 |
| 6 | lib/armeabi-v7a/libtensorflowlite_jni.so | 2.37 |
| 7 | classes.dex | 1.89 |
| 8 | lib/armeabi-v7a/libsqliteJni.so | 1.07 |
| 9 | lib/arm64-v8a/libsqliteJni.so | 1.07 |
| 10 | lib/x86_64/libsqliteJni.so | 1.06 |
| 11 | lib/x86/libsqliteJni.so | 1.04 |
| 12 | assets/.../LiberationSans-Regular.ttf | 0.21 |
| 13 | assets/models/tokenizer.json | 0.21 |
| 14 | assets/tokenizer.json (duplicate of 13) | 0.21 |
| 15 | resources.arsc | 0.19 |

Category totals (APK): assets 70.3 MB, native libs 19.5 MB, dex 1.9 MB, other 0.3 MB.
Top AAB entries: `clip_model` image encoder 162.6 MB, text encoder 116.9 MB, base cross-encoder 41.8 MB
(AAB compresses it), MiniLM 14.9 MB.

**16 KB page size (checked by parsing ELF `PT_LOAD` alignment and zip data offsets in the release APK).**
All 12 `.so` entries are stored and start at 16 KB-aligned offsets. LOAD alignment: `libsqliteJni.so` and
`libandroidx.graphics.path.so` are 16384 on all ABIs; **`libtensorflowlite_jni.so` (LiteRT 1.0.1) is 16384 on
arm64-v8a but 4096 on x86_64, x86 and armeabi-v7a.** Play's 16 KB rule covers 64-bit ABIs, so x86_64 is the
exposed one. Options: drop x86/x86_64 from the bundle (`ndk.abiFilters` or an ABI split), or get an aligned
x86_64 build. Not applied tonight.

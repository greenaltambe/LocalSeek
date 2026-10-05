# Release checklist

Items marked **VERIFY** are policy or version details I could not confirm from the repository.
Status as of branch `overnight2` (built but not run on a device overnight).

## Build and signing
- [ ] Create an upload keystore outside the repo. The release build reads `LOCALSEEK_KEYSTORE_PATH`,
      `LOCALSEEK_KEYSTORE_PASSWORD`, `LOCALSEEK_KEY_ALIAS`, `LOCALSEEK_KEY_PASSWORD` (Gradle property or
      env var); without them it produces an unsigned APK/AAB (`app/build.gradle.kts`).
- [ ] Enrol in Play App Signing (Google holds the app signing key; you keep the upload key).
- [x] `./gradlew :app:bundleRelease` builds: AAB 362 MB = base 69.7 MB + on-demand `clip_model` pack 280 MB +
      12 MB metadata (R8 map, debug symbols). Base is far under the 200 MB limit. Not yet uploaded.
- [ ] Smoke test the R8-minified release build on a device: onboarding, search, PDF extraction, reranker,
      contacts, photo indexing, pack download from an internal test track (a side-loaded build cannot
      download the pack). Only the build was verified.
- [ ] Keep the R8 mapping file (`app/build/outputs/mapping/release/mapping.txt`) for each upload.
- [x] Merged debug and release manifests have no `INTERNET` permission (re-check before every upload).

## Versioning
- Currently `versionCode = 1`, `versionName = "1.0"`. Play rejects a repeated `versionCode`; bump for every upload.
- The build embeds `GIT_SHA` (with `-dirty` suffix) in BuildConfig and the About page shows it; release
  builds should come from a clean tagged tree.
- Tag `paper-v1` marks the frozen retrieval pipeline; keep release-only changes off the frozen packages
  until the paper is submitted.

## Target SDK, minSdk
- `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`. Play's target-API deadline is annual: VERIFY the
  current requirement in Play Console. A Compose BOM newer than 2026.03.00 needs `compileSdk` 37.
  Behaviour-change checklist for raising `targetSdk`: `docs/investigations/T9_INVESTIGATIONS.md`, section c.

## Runtime and 16 KB page-size compatibility
The app uses LiteRT (`com.google.ai.edge.litert:litert:1.0.1`). Checked tonight by parsing the release APK:
all 12 native libs are stored and start at 16 KB-aligned zip offsets; ELF `PT_LOAD` alignment:

| Library | arm64-v8a | x86_64 | x86 | armeabi-v7a |
|---|---|---|---|---|
| `libtensorflowlite_jni.so` (LiteRT 1.0.1) | **16384** | 4096 | 4096 | 4096 |
| `libsqliteJni.so` | 16384 | 16384 | 16384 | 16384 |
| `libandroidx.graphics.path.so` | 16384 | 16384 | 16384 | 16384 |

- arm64-v8a, the ABI of real phones, is 16 KB ready. **x86_64 is not** and Play's check covers 64-bit ABIs:
  either drop x86/x86_64 from the bundle or get an aligned build (VERIFY what the Console pre-launch report says).
- Still not tested on a 16 KB emulator image or device.

## Play Console
- [ ] Complete `DATA_SAFETY.md` answers; privacy policy URL (publish `docs/index.html` with GitHub Pages).
- [ ] `MANAGE_EXTERNAL_STORAGE` declaration and demo video (`PERMISSION_DECLARATION.md`). Decide early:
      rejection costs weeks.
- [ ] Foreground service declaration for `dataSync` (text in the T9 investigation) and the photo/video
      permissions declaration for `READ_MEDIA_IMAGES`.
- [ ] Sensitive permission (`READ_CONTACTS`) disclosure: the onboarding page shows the in-app rationale first.
- [ ] Content rating questionnaire, target audience (not for children), ads: none.
- [ ] Asset pack: `clip_model` is an on-demand pack (about 290 MB installed). Upload with the AAB and test
      the download on the internal track over Wi-Fi and cellular.
- [ ] Screenshots per `SCREENSHOT_PLAN.md`; feature graphic and 512x512 icon.

## Testing track
- New **personal** developer accounts must run a closed test with at least **12 testers opted in for 14
  consecutive days** before applying for production access (VERIFY current numbers). Plan in
  `CLOSED_TEST_PLAN.md`; start early, it is calendar time.
- Test on at least 2-3 devices/API levels (26, 33/34, 35+) including one Android 15 device for 16 KB, the
  six-hour foreground-service limit, and the "All files access" flow.

## F-Droid notes
- Needs a public source repo with a licence (**none chosen yet**) and a clean history.
- Reproducible build: the version/`GIT_SHA` fields include `-dirty` state and are read from git; models are
  opaque binaries in `assets/` (provenance in `THIRD_PARTY_NOTICES.md`); the CLIP pack is a Play feature and
  would not exist in an F-Droid build (image search degrades gracefully).
- Dependencies: LiteRT (Apache-2.0), PDFBox-Android (Apache-2.0), Room3 alpha, Compose. `asset-delivery-ktx`
  is a Google Play library and is not F-Droid friendly; an F-Droid flavor would need it removed.
- Flavors: consider an F-Droid/SAF flavor without `MANAGE_EXTERNAL_STORAGE` if Play rejects it.
- Dependency pin: `androidx.room3:room3-runtime:3.0.0-alpha02` (and the `sqlite` alpha it pulls in) is an alpha.

## Before first public release
- [x] README/PRIVACY.md exist; `docs/index.html` is a static privacy page for GitHub Pages
- [ ] LICENSE
- [x] Research/debug UI hidden in release builds (`BuildConfig.DEBUG` gating)
- [x] Onboarding requests each permission optionally and one at a time; replay in Settings
- [x] About page: version, no-internet statement, privacy summary, licenses
- [ ] WorkManager `2.9.1` has no Android 15 foreground-service timeout handling (recall: added in 2.10): bump
      and verify (T9 section b); this matters because the first index takes hours
- [ ] Crash reporting: none (no network); rely on Play vitals
- [ ] Baseline profile: check whether `baselineProfiles` in the release outputs is generated from a real profile

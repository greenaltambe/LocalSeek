# Release APK size report

Measured 2026-09-30 from `./gradlew :app:assembleRelease` (R8 + resource shrinking on, unsigned,
universal APK, branch `overnight`). Sizes are compressed bytes inside the APK unless noted.

## Update 2026-10-03 (branch `ui-overhaul`): why the APK grew from 69.2 MB to 80.6 MB

Built with `./gradlew :app:assembleRelease :app:bundleRelease` on `ui-overhaul` (unsigned, abiFilters arm64-v8a + armeabi-v7a).
Per-file numbers are compressed bytes inside the APK, read from the zip central directory.

| Item | Size |
|---|---|
| Release APK, unsigned | 80,986,629 bytes (81.0 MB) |
| Release AAB (includes the on-demand `clip_model` pack) | 352,806,550 bytes (352.8 MB) |
| `zipalign -c -P 16 -v 4` | "Verification successful" |
| `aapt2 dump permissions`, INTERNET entries | 0 |

| Category | 2026-09-30 (4 ABIs) | Now (2 ABIs) | Change |
|---|---|---|---|
| assets | 48.6 MB | 70.3 MB | **+21.7 MB** |
| native libs | 24.5 MB (arm64 4.5, v7a 3.3, x86_64 5.6, x86 5.5) | 8.1 MB (arm64 4.6, v7a 3.4) | -16.4 MB |
| classes.dex | 1.3 MB | 2.1 MB | +0.8 MB |
| res + arsc + other | 0.2 MB | 0.4 MB | +0.2 MB |
| **Total** | **69.2 MB** | **80.9 MB** | +11.7 MB |

**Cause:** `assets/models/cross_encoder.tflite` went from 23.09 MB to **45.31 MB**. Commit `b0f7ed0` (2026-09-30,
"Re-export cross_encoder with token_type_ids in opset 20 (FP16, 43MB)") replaced the model after the first size
measurement. That alone is +22.2 MB. Dropping the x86 / x86_64 libraries in the release ABI filter (-11 MB) hid part of it;
the 69.2 MB figure was measured with all four ABIs, so the two changes partly cancelled.
The extra dex (+0.8 MB) is the new UI code (settings pages, mascot, banner).

**Not changed:** the model lives under `assets/models` (frozen for the paper), so it was not touched. Options for the owner:
quantise the cross-encoder to int8 (about -20 MB, changes the frozen model and needs a re-evaluation), or move it to a fast-follow
asset pack (the base download would shrink by 45 MB). Neither was attempted. Smaller duplicates exist
(`assets/tokenizer.json` and `assets/models/tokenizer.json`, `vocab.txt` twice: about 0.3 MB), also frozen paths.

Top files now: `cross_encoder.tflite` 45.31 MB, `minilm_optimized.tflite` 22.73 MB, `libtensorflowlite_jni.so` 3.55 MB (arm64) and
2.37 MB (v7a), `libsqliteJni.so` 1.07 MB each, `classes.dex` 2.08 MB.

### lintRelease and R8 on this branch

- `./gradlew :app:lintRelease`: **0 errors, 58 warnings, 10 hints**. The first run of the new UI had 3 errors
  (`LocalContextGetResourceValueCall`, all in new code); they were fixed, along with 50 unused strings, one private-resource
  override, `toUri` hints and an obsolete SDK check. Remaining warnings are dependency/version notes (GradleDependency 20,
  UseTomlInstead 16, NewerVersionAvailable 5, ...), the BouncyCastle `TrustAllX509TrustManager` inside a dependency jar, and
  items in frozen files (`UnnecessaryArrayInit` in BertTokenizer/ClipBpeTokenizer/DenseEncoder, `SwitchIntDef` and `UseKtx` in
  clip code, `SdCardPath` in FileIndexer). `Aligned16KB` and `SelectedPhotoAccess` are unchanged from earlier reports.
- R8 mapping check (`app/build/outputs/mapping/release/seeds.txt`): `IndexWorker` (with its `(Context, WorkerParameters)`
  constructor), `AppDatabase_Impl`, `WorkDatabase_Impl` and `SystemForegroundService` are all kept, so WorkManager and Room3
  can instantiate them. The app has no navigation library (a typed route enum is used), so there are no navigation keep-rule
  concerns. This is a static check of the build output only; the release build was **not** run on a device or emulator
  (see `docs/release/MANUAL_CHECKLISTS.md`).

---
## Update 2026-10-03 (branch `release-prep-r`, current build)

Built with `./gradlew :app:assembleRelease :app:bundleRelease` (unsigned: no `keystore.properties`).

| Item | Size |
|---|---|
| Release APK, unsigned (abiFilters arm64-v8a + armeabi-v7a) | 80,624,509 bytes (80.6 MB) |
| Release AAB (includes the `clip_model` on-demand pack) | 352,228,038 bytes (352.2 MB) |

- `aapt2 dump permissions`: 0 INTERNET entries.
- `zipalign -c -P 16 -v 4`: "Verification successful".
- The APK is larger than the 69.2 MB measured on 2026-09-30; the cause was not investigated, and no per-file
  breakdown was re-run, so the category tables below are from 2026-09-30 and may be stale.
- Sizes from this build are of the unsigned APK; a signed/aligned build may differ slightly.

---
## Original measurement (2026-09-30)
## Totals

| Item | Size |
|---|---|
| Release APK (universal, all 4 ABIs) | **69.2 MB** (66.0 MiB) |
| Before excluding BouncyCastle PQC tables | 73.2 MB |

The build succeeded on the first try: no R8 failure, and the existing keep rules for TFLite, Room3
and PDFBox were sufficient to *build*. **Not verified:** that the release build runs correctly on a
device (search, PDF extraction, TFLite reranker). Smoke-test the release APK before shipping.

## By category

| Category | Size |
|---|---|
| assets (models + tokenizers + PDFBox resources) | 48.6 MB |
| native libs, all four ABIs | 24.5 MB |
| classes.dex | 1.3 MB |
| everything else (res, arsc, manifest) | 0.2 MB |

## Per ABI (native libs only)

| ABI | libtensorflowlite_jni | libsqliteJni | graphics.path | ABI total |
|---|---|---|---|---|
| arm64-v8a | 3.4 MB | 1.07 MB | 0.01 MB | 4.5 MB |
| armeabi-v7a | 2.2 MB | 1.07 MB | 0.01 MB | 3.3 MB |
| x86_64 | 4.5 MB | 1.06 MB | 0.01 MB | 5.6 MB |
| x86 | 4.5 MB | 1.04 MB | 0.01 MB | 5.5 MB |

A single-ABI install is therefore roughly 48.6 + 1.3 + ~4.5 = **about 55 MB** (arm64-v8a).

## Biggest 15 files

| # | File | Kind | MB |
|---|---|---|---|
| 1 | assets/models/cross_encoder.tflite | asset | 23.09 |
| 2 | assets/minilm_optimized.tflite | asset | 22.73 |
| 3 | lib/x86_64/libtensorflowlite_jni.so | native | 4.54 |
| 4 | lib/x86/libtensorflowlite_jni.so | native | 4.46 |
| 5 | lib/arm64-v8a/libtensorflowlite_jni.so | native | 3.40 |
| 6 | lib/armeabi-v7a/libtensorflowlite_jni.so | native | 2.25 |
| 7 | classes.dex | dex | 1.33 |
| 8 | lib/armeabi-v7a/libsqliteJni.so | native | 1.07 |
| 9 | lib/arm64-v8a/libsqliteJni.so | native | 1.07 |
| 10 | lib/x86_64/libsqliteJni.so | native | 1.06 |
| 11 | lib/x86/libsqliteJni.so | native | 1.04 |
| 12 | assets/models/clip/vocab.json | asset | 0.33 |
| 13 | assets/models/clip/merges.txt | asset | 0.21 |
| 14 | assets/com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf | asset | 0.21 |
| 15 | assets/models/tokenizer.json | asset | 0.21 |

Two TFLite models are ~94% of asset bytes. `assets/` also contains duplicate tokenizer/vocab files
(`assets/vocab.txt` and `assets/models/vocab.txt`, same for tokenizer json); small, but check whether
both copies are needed.

## Change made for this report

`app/build.gradle.kts` now excludes `org/bouncycastle/pqc/crypto/**/*.properties` (post-quantum
parameter tables from PDFBox-Android's BouncyCastle dependency; SIKE/Picnic are never used by PDF
text extraction). This saved ~4 MB. Risk: low, but confirm encrypted-PDF extraction still works.
An optional signing config was also added: it reads `LOCALSEEK_KEYSTORE_PATH`,
`LOCALSEEK_KEYSTORE_PASSWORD`, `LOCALSEEK_KEY_ALIAS`, `LOCALSEEK_KEY_PASSWORD` from Gradle
properties or environment variables and does nothing when they are absent.

## CLIP models if shipped

The CLIP tflite models are git-ignored and not in `assets/` (only `models/clip/README.md`,
`vocab.json`, `merges.txt` are). Per the earlier release plan (archived 2026-10-05, no longer in the tree) they are about **290 MB**.
Bundled, the universal APK would grow from 69 MB to about **360 MB**, and an arm64-only APK from
about 55 MB to about 345 MB. That exceeds the Play 200 MB base-module APK/AAB download limit, so CLIP
cannot ship in the base module. (Figure taken from the plan, not measured.)

## Recommendations

- **ABIs:** ship **arm64-v8a + armeabi-v7a** via an Android App Bundle (Play delivers one ABI per
  device). Drop x86/x86_64 (emulators and a handful of old Intel devices). arm64-only is defensible
  for a minSdk 26 app in 2026, but armeabi-v7a still covers older 32-bit devices for +3.3 MB.
  Note `packaging.jniLibs.pickFirsts` in `app/build.gradle.kts` lists only the arm64 TFLite library; it
  is harmless but check it is still needed. For F-Droid/APK distribution, use `splits { abi { ... } }`.
- **Models / Play Asset Delivery:** yes for CLIP: use an on-demand asset pack (or in-app download
  with SHA-256 verification) and enable image search once installed. The two text models (46 MB
  combined) can stay in the base module (install-time), well under limits. If APK size matters more
  than first-run simplicity, move them to a fast-follow pack. Quantising the cross-encoder to int8
  would cut ~17 MB but changes the frozen model, so it is out of scope until after the paper.
- **16 KB pages:** `libtensorflowlite_jni.so` from `org.tensorflow:tensorflow-lite:2.16.1` has LOAD
  segments aligned to **4096**; `libsqliteJni.so` and `libandroidx.graphics.path.so` are 16384 aligned
  (checked by reading the ELF program headers of the arm64-v8a and x86_64 libs). Lint reports one
  `Aligned16KB` warning. Play requires 16 KB support for apps targeting Android 15+ from
  1 Nov 2025 (VERIFY current deadline/extension); a newer TFLite/LiteRT release with 16 KB alignment is
  probably needed. Changing the TFLite dependency affects the frozen pipeline's runtime, so
  it needs a deliberate decision after the paper freeze.

## lintRelease

`./gradlew :app:lintRelease` completed and **reported 0 errors** (no release blockers). Warnings:
GradleDependency 16, UseTomlInstead 13, UnusedResources 8, NewerVersionAvailable 6,
TrustAllX509TrustManager 3, ObsoleteSdkInt 3, UseKtx 3, AndroidGradlePluginVersion 2, OldTargetApi 1,
SdCardPath 1, RedundantLabel 1, SelectedPhotoAccess 1, Aligned16KB 1, plus 7 hints.
Worth a look: `TrustAllX509TrustManager` (3; probably inside a dependency, since the app has no
network, but check where it comes from), `SelectedPhotoAccess` (Play's photo-permission policy) and
`Aligned16KB`. Full reports: `app/build/reports/lint-results-release.{html,txt,xml}` (not committed).

### Update after the Task 5 features

The Task 5 features (tools, Custom Tabs library, tile, widget) were added after the measurements above; the
release APK was rebuilt and is still about 69 MB. `lintRelease` was re-run and initially reported **3 errors**
introduced by that new code (two `NewApi` on the dynamic-colour calls, one `StartActivityAndCollapseDeprecated`
in the tile service); both were fixed and lint again reports **0 errors**. The category and per-file numbers
above were not re-measured.

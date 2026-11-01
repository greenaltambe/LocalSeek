# Resource use of LocalSeek 1.0.0 (task AT part 5; descriptive, one phone)

Date: 2026-10-25 (night run). Aggregates only.

## 5.1 Sizes (bytes from `stat`; MB = 10^6 bytes)

| Item | Bytes | MB |
|---|---|---|
| Release AAB (`:app:bundleRelease`, rebuilt this night from main) | 352,820,095 | 352.8 |
| Universal APK built from it (`tools/bundletool`, `build-apks --mode=universal`, debug-keystore signed, not for release) | 81,010,814 | 81.0 |
| `minilm_optimized.tflite` (MiniLM text encoder) | 22,734,360 | 22.7 |
| `cross_encoder.tflite` (off by default) | 45,309,832 | 45.3 |
| CLIP image encoder fp16 (asset pack `:clip_model`, as stored in the repository) | 175,848,548 | 175.8 |
| CLIP text encoder fp16 (same pack) | 127,008,504 | 127.0 |

The universal APK contains the base module only (the CLIP asset pack is separate); the AAB is larger because it carries the pack. The study APK built on 2026-10-11 is 81,033,660 bytes. Sum of the CLIP files 302.9 MB as stored; their size inside the AAB was not measured separately.

## 5.2 Process memory (phone A, OnePlus CPH2707, Android 16)

Conditions: USB-powered (charging), battery 35 to 37 %, thermal status NONE (0), airplane mode off, DND on, screen woken. Debug build of main (+ a new read-only test), installed with `adb install -r -t` (app data kept). Test: `app/src/androidTest/.../smoke/MemorySearchInstrumentedTest.kt` (new file; the five smoke queries x 4 rounds = 20 searches through `RetrievalConfig.SHIPPED`; no indexing, no writes). It reads `Debug.MemoryInfo` of its own process (the app process under instrumentation) and logs numbers under tag MEMPROBE. Three runs.

| Phase | Total PSS (MB) | Java heap (MB) | Native heap (MB) |
|---|---|---|---|
| Start (idle) | 218.4 / 224.2 / 225.2 | 13.8 / 13.7 / 13.7 | 7.1 / 13.7 / 14.8 |
| After one warm-up search | 1,163.9 / 1,206.1 / 1,207.0 | 49.1 / 63.4 / 62.1 | 751.1 / 785.5 / 787.3 |
| After 20 searches | 1,023.8 / 1,025.8 / 1,029.3 | 52.7 / 57.1 / 57.5 | 608.4 / 611.6 / 614.3 |

(Run 1 / run 2 / run 3; KB values from the log divided by 1,000, rounded.) PSS dropped 140 to 180 MB between the warm-up and the end of each run. Rounds were logged in all three runs; run 1's rounds 2 to 4 were within 4 MB of each other (1,020.5 / 1,023.1 / 1,023.8); only the first and last phases of runs 2 and 3 were checked by hand. No growth over 20 searches.

In-memory dense index logged by the test: path EXACT_MEMORY, 14,886 vectors, 22,864,896 bytes as float32 (14,886 x 384 x 4).

Caveats: debug build; the instrumentation process also holds the test classes; the native heap was not broken down (it includes the loaded TFLite models and runtime buffers); three short runs on one phone; PSS from `Debug.MemoryInfo`, not from `dumpsys meminfo` (the instrumentation process ends with the test).

## Surprise to note

The phone's index now holds **14,886 chunks**, not the 13,696 of the Set B freeze. This was already so in the database snapshot taken before this run's install (`~/localseek-private/db-snapshots/pre-memory-2026-10-25/`, SHA256SUMS inside), so it did not come from this run. The installed app was last updated on 2026-10-05; the database file was last written on 2026-10-12.

## 5.3 Energy

Not run: it needs the cable unplugged. The steps are in `docs/investigations/AV_ENERGY_RUNBOOK.md`.

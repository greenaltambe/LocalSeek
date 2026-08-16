# Phase 20.1 - Instrumentation Audit & Targeted Fixes

Audit the accuracy and methodology of benchmark instrumentation (memory, battery, result ordering, corpus size, LSH independence). Fix identified bugs in tracking and reporting.

## User Review Required

> [!IMPORTANT]
> - **Memory Measurement**: Currently, memory is only measured *after* the heavy search operations have completed. I will refactor the search pipeline to track a `peakMemoryMb` variable that samples memory usage after each major retrieval/ranking stage.
> - **Battery Measurement**: `batteryPctAfter` is currently only logged for the final "hybrid_threshold" row in a benchmark suite. I will update it to be captured for every row if available, providing more granular visibility.

## Proposed Changes

### [SearchViewModel](file:///home/greenaltambe/AndroidStudioProjects/LocalSeek/app/src/main/java/com/augt/localseek/ui/SearchViewModel.kt)

#### [MODIFY] [SearchViewModel.kt](file:///home/greenaltambe/AndroidStudioProjects/LocalSeek/app/src/main/java/com/augt/localseek/ui/SearchViewModel.kt)
- Introduce a `peakMemoryMb` tracker in `executeSearch` and `runBenchmarkSuite`.
- Update `getMem()` to sample actual PSS at point-of-call.
- Capture battery before/after more consistently.
- Ensure all 6 backend logging paths in `runBenchmarkSuite` maintain correct sorting and index alignment.

## Verification Plan

### Automated Tests
- Run existing unit tests to ensure no regressions in search logic:
  `./gradlew testDebugUnitTest`
- Run build to ensure instrumentation changes don't break the build:
  `./gradlew assembleDebug`

### Manual Verification
- Inspect `BenchmarkLogger` output (via adb shell or logcat) to confirm `memoryMbPeak` now reflects a high-water mark across stages.
- Confirm `batteryPctAfter` is populated more frequently in the benchmark logs.
- Verify that `dense_bruteforce` results are correctly sorted by score in the logs.

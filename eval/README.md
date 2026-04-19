# Evaluation Suite

This folder documents the Phase 7 ranking quality harness.

## Kotlin evaluation code
- `app/src/test/java/com/augt/localseek/eval/EvaluationSuite.kt`
- `app/src/test/java/com/augt/localseek/eval/MapEvaluationSuiteTest.kt`

## What it measures
- Average Precision (AP)
- Mean Average Precision (MAP)

## Running locally
```bash
./gradlew :app:testDebugUnitTest --tests "com.augt.localseek.eval.*"
```

## Extending query set
Add more entries in `EvaluationSuite.testQueries` with real file paths from your index.


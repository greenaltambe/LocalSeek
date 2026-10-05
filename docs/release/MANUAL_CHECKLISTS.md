# Manual checklists (not run by Claude)

No Android emulator is installed on the build machine (`~/Android/Sdk/emulator` has no binary and there are no system
images or AVDs), and the connected phone holds personal data, so nothing below was executed. Run these by hand.

## A. Release build smoke test (R8 on), phone, synthetic or your own data

Install with `adb install -r -t app/build/outputs/apk/release/<signed>.apk` (never uninstall: the index took hours).

1. App starts, no crash; splash shows the squirrel mark; launcher icon and themed icon (Android 13+) look right.
2. Search "report": results appear as separate cards; app icons, contact avatar/photo and file-type icons render.
3. Type `c ` (c + space): a chip with a person icon appears, only contacts are listed. Backspace on the empty field removes the chip.
4. Type `g weather`: engine card with the engine icon; tapping opens an in-app tab (or your browser if you changed Settings > Web search > Open in).
5. Filters button: pick PDF + 7 days; badge shows 2; Clear filters resets.
6. Long-press a result: sheet with Share / Open with / Copy / Pin; haptic felt.
7. Open the keyboard in portrait with font size set to the largest: results stay visible above the keyboard; nothing under the status bar. Repeat with One-handed mode on.
8. Indexing banner appears under the app bar while indexing runs and shows "N of M files" or a count; the notification shows progress with a Stop button.
9. Settings: every page opens; back gesture shows the predictive shrink animation; Developer is hidden until you tap the version row in About 7 times.
10. Rotate / fold if possible; on a tablet the content stays in a centred column.
11. Release-only: confirm search, PDF text extraction, TFLite reranker (if turned on) and image search still work (R8 can strip reflective code).
12. `adb shell dumpsys package com.augt.localseek | grep permission` shows no `INTERNET`.

## B. Indexing resume (WorkManager) test

Goal: a timed-out indexing run queues a resume. Do this on a charged phone; do not clear app data.

1. Start indexing from Settings > Indexing > Rebuild (or on a fresh install after onboarding).
2. While it runs, confirm the foreground job exists: `adb shell dumpsys jobscheduler | grep -A6 com.augt.localseek`.
3. Force a stop without deleting data: `adb shell cmd jobscheduler timeout com.augt.localseek` (simulates the job timeout; Android 14+ `STOP_REASON_FOREGROUND_SERVICE_TIMEOUT` / `TIMEOUT` path) or `adb shell am force-stop com.augt.localseek` (this one drops the work queue entry until the next app start, so it only tests the periodic recovery).
4. Within a few seconds check that a resume is queued: `adb shell dumpsys jobscheduler | grep -B2 -A12 com.augt.localseek` should list a pending job with a ~15 minute delay and a battery-not-low constraint. The app banner shows "Indexing is queued and will start shortly" with a Run now button.
5. Tap Run now (or wait ~15 min): the banner switches to progress, then "Index up to date" for 3 s.
6. Log check (no personal data): `adb logcat -d -s IndexWorker | tail` should contain "rescheduling index resume" and "Successfully enqueued resume IndexWorker job via APPEND_OR_REPLACE".
7. Failure path: if the banner shows "Indexing stopped before it finished", tap Resume and confirm it starts.

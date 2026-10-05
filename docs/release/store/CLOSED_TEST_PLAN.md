# Closed test plan

Goal: satisfy Play's requirement for new personal developer accounts (**at least 12 testers opted in for
14 consecutive days**; VERIFY current numbers in the Console) and learn whether the app is ready for production.

## Setup
- Track: Closed testing. Upload the signed AAB (`./gradlew :app:bundleRelease`); the on-demand `clip_model`
  pack uploads with it. Release name like `1.0 (1) closed test`.
- Testers: a Google Group created for this (testers join the group, then opt in via the Play link).
  **Recruit 16 to 20** so dropouts do not take you under 12; everyone must stay opted in for the full 14 days.
- Start the 14-day clock only once 12 testers are opted in; track the opt-in count daily in the Console.
- Device mix wanted: at least one Android 8 to 10 device, one 13/14, one Android 15 or 16, one low-RAM phone,
  one tablet or foldable, at least three manufacturers.
- Tell testers up front: LocalSeek indexes their own files on their own phone; nothing is uploaded; the
  image-search pack is ~290 MB (Wi-Fi recommended); the first index can take a while on large libraries.

## What testers do (ask for this in the welcome message)
1. Day 1: install, walk through onboarding, grant what they are comfortable with, let indexing finish.
2. Days 2 to 7: use it instead of the launcher search and file manager search at least 3 times a day.
3. Try: a phrase inside a PDF, an app name, a contact, a photo description (if they installed the pack),
   `12*8+4`, a unit conversion, pinning an item, the widget or tile.
4. Day 7 and Day 14: deny a permission in system settings and reopen the app; replay onboarding from Settings.
5. Report anything confusing, slow, wrong or crashing.

## Feedback form (Google Form, anonymous)
1. Device model and Android version
2. How large is your Documents + Download folder? (<100 files, 100 to 1000, >1000)
3. How long did the first indexing take, and was the progress clear? (free text)
4. Which permissions did you grant? (contacts / all files / photos / none) and why not the rest?
5. Did the onboarding explain the permissions clearly? (1 to 5) What was missing?
6. Did search find what you were looking for? (1 to 5) Give one query that failed.
7. Did results feel fast enough? (1 to 5)
8. Did you use image search? If not, why? If yes, was the download step OK?
9. Was anything confusing, or hard to tap or read? (mention font size or accessibility settings)
10. Did the app ever crash, freeze or drain the battery noticeably? (describe)
11. Would you keep it installed? (yes / maybe / no) Why?
12. Anything you expected it to search that it did not?

## What to monitor
- Play Console: crashes and ANRs (Android vitals) per device and Android version, install/uninstall counts,
  opt-in count, pre-launch report (16 KB alignment, permission warnings, accessibility findings).
- Crashes to look for: foreground service timeout on Android 15 (long first index; see
  `docs/investigations/T9_INVESTIGATIONS.md`), out-of-memory while embedding, PDFBox on odd PDFs,
  pack-download failures (low storage, cellular), permission revocation while indexing.
- Qualitative: where onboarding is skipped, how many grant contacts/all files/photos (form Q4), requests for
  other folders (supports the SAF decision).
- No in-app analytics exist by design; rely on the form, Play vitals and direct messages.

## Schedule
| Day | Action |
|---|---|
| -7 | Build, sign, smoke-test on two devices, upload to the closed track |
| -3 | Recruit testers, send the opt-in link and welcome message |
| 0 | 12+ testers opted in: clock starts |
| 3 | Check vitals; send reminder; fix blockers with a new build (testers stay opted in) |
| 7 | Mid-point form nudge |
| 14 | Close the form, summarise feedback, apply for production access if the requirement is met |

Exit criteria: no unresolved crash cluster, crash-free sessions above 99% (VERIFY Play's bad-behaviour
thresholds), at least 8 completed forms, and no privacy or permission complaints left open.

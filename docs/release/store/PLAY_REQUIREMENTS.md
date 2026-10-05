# Play requirements checked 2026-10-03

## Target API level
Source: https://support.google.com/googleplay/android-developer/answer/11926878 (read 2026-10-03).
"Starting August 31, 2026, new apps and app updates must target Android 16 (API level 36) or higher to be
submitted to Google Play." An extension to November 1, 2026 can be requested. This app has `targetSdk = 36`
and `compileSdk = 36`, so it meets the requirement. Lint (`OldTargetApi`) only notes a newer SDK exists.
Re-read the page before each submission; the level rises yearly.

## New personal developer account checklist
Source: https://support.google.com/googleplay/android-developer/answer/14151465 (read 2026-10-03).
- [ ] Run a **closed test** with **at least 12 testers opted in continuously for at least 14 days**. If a tester
      opts out and back in, the 14 days must be consecutive.
- [ ] Recruit more than 12 (16-20) so dropouts do not reset the count. See `CLOSED_TEST_PLAN.md`.
- [ ] Then apply for production access in the Console (questions on testing, app design, production
      readiness). Google says review usually takes seven days or less.
- [ ] Create the release keystore yourself and keep a backup (see `RELEASE_CHECKLIST.md`); enrol in Play App Signing.
- [ ] Complete App content: privacy policy URL, Data safety (`DATA_SAFETY.md`), content rating, target audience, ads declaration (none).
- [ ] Submit the All files access Permissions Declaration Form (`PERMISSION_DECLARATION.md`) and demo video
      (`DEMO_VIDEO_SCRIPT.md`); also the contacts and photo permission declarations if asked.
- [ ] Store listing text from `LISTING.md`, graphics from `SCREENSHOT_PLAN.md`.
- [ ] Upload the signed AAB (the `:clip_model` asset pack is inside it).
Account identity verification and any fees are handled in the Console; not checked here.

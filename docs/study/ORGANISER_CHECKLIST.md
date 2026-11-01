# LocalSeek study: organiser checklist

## Before
- [ ] `docs/investigations/USER_STUDY_PLAN.md` and `eval/user_study/` are committed (they are: see git log) and, if wanted, registered on OSF (text at the end of the plan).
- [ ] The owner does not take part (the owner's phone is the Study 1 corpus).
- [ ] 4 to 6 volunteers, 18 or older, Android 8 (API 26) or newer, about 2 GB free storage. Each reads `docs/study/CONSENT.md` (the same text is in the app).
- [ ] Build the study APK from a clean commit (`./gradlew :app:assembleStudy` in a clean checkout; the gitSha shown in the export must not end with `-dirty`). Copy it to `~/localseek-private/study/LocalSeek-study-<version>.apk` and note the SHA-256.
- [ ] Send the APK and `PARTICIPANT_GUIDE.md` to each volunteer by a channel you trust; tell them the SHA-256 so they can compare if they want.
- [ ] Ask them to start in the evening, on the charger, with Wi-Fi not needed.

## During
- [ ] Participants send you back one JSON each (numbers only). Save them to `~/localseek-private/study/participants/` (outside the repo). Never commit them.
- [ ] If someone's export looks wrong, ask them to open the app again (their state is saved) and share again.
- [ ] Do not ask for screenshots of results: they show file names.

## After
- [ ] `python3 eval/user_study/analyze_user_study.py --input ~/localseek-private/study/participants --out-md ~/localseek-private/study/summary.md --out-json ~/localseek-private/study/summary.json`
- [ ] Report as in the plan: descriptive-plus with participant-cluster intervals; say how many participants and queries were analysed and excluded.
- [ ] Tell participants they can uninstall the app, tap "Delete study data", and ask you to delete their file by giving their code.
- [ ] Commit only the aggregate summary (if at all), never participant files.

## Known limits to mention
Image search is off in the study build. Participants judge only what the three arms returned. Small convenience sample.

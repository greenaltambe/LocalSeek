# Screenshot plan (8 screens)

Capture on a clean demo device or emulator (Pixel-class, 1080x2400, English, Android 14+), never on a phone
with personal data. Phone screenshots: 2 to 8 images, 16:9 or 9:16, min 320 px, max 3840 px (VERIFY limits).
Hide the notification bar contents: demo mode (`adb shell settings put global sysui_demo_allowed 1`, then the
`com.android.systemui.demo` broadcast) or use an emulator with a clean status bar.

## Demo data (generic, synthetic)

Put these in `Documents/` and `Download/` on the demo device, then run Settings, Rebuild and wait for the
banner to disappear:

| File | Content (one or two sentences is enough) |
|---|---|
| `Garden_Notes.pdf` | "The irrigation schedule for the vegetable beds runs every other morning before sunrise." |
| `Weekly_Planning.txt` | "Planning outline for the week: groceries, laundry, water the garden, call the plumber." |
| `Budget_2026.pdf` | "Quarterly budget planning: rent, utilities, groceries and a small savings target." |
| `Pasta_Recipes.md` | "Tomato pasta: boil water, salt it well, simmer the sauce for twenty minutes." |

Contacts (create on the demo device, all fictional): **Alex Example**, **Sam Sample**, **Pat Placeholder**
with numbers in the reserved `555-01xx` range. Photos: 6 to 10 royalty-free sample images (beach, dog, mountain,
city at night, food). Apps: the stock apps are enough (Calendar, Camera, Clock).

## The eight screens

| # | Screen | State to set up | Query / action | What the shot must show |
|---|---|---|---|---|
| 1 | Grouped results (light) | Permissions granted, index complete, light theme, dynamic colour off | type `planning` | Result groups Apps / Contacts / Files with tonal type icons and highlighted snippets in `Weekly_Planning.txt` and `Budget_2026.pdf` |
| 2 | Content match in a PDF | same | type `irrigation schedule` | `Garden_Notes.pdf` as the top result with the bold highlighted snippet, proving search by content |
| 3 | Photo search | Image pack installed (internal test track build), image search on | type `beach` | Images group with thumbnails of the sample beach photos |
| 4 | Tools and web fallback | same | type `12*8+4` (card `100`); second frame `5 km in miles` | Calculator or converter card above results, plus the "Search the web" chips when a query has no hits (use `zzqx`) |
| 5 | Contact actions and pins | contacts granted | type `Alex`, pin the contact, clear the query | Call / Message buttons on the contact result; then the home screen with the Pinned strip (Alex, Calendar, `Budget_2026.pdf`) |
| 6 | Onboarding permission | fresh install (clear app data on the demo device only) | Settings, Replay onboarding, advance to "Permission 1 of 3" | Rationale, Allow and Not now buttons, progress bar |
| 7 | Settings, dark theme | dark theme, one-handed mode on | Settings, Appearance | Theme segmented buttons, accent chips, Dynamic colour, One-handed mode switch; optionally the dark main screen with the search bar at the bottom |
| 8 | About and privacy | any | Settings, About, privacy and licenses | "No internet permission" card, privacy summary, version line |

## Also capture
- Tablet or foldable shot (optional): result column limited to 720 dp, centred.
- Font scale 200% check (not for the store): frames 1 and 6 at `adb shell settings put system font_scale 2.0`,
  to confirm nothing clips.
- Feature graphic 1024x500 and 512x512 icon: render from `ic_launcher_foreground.xml` on the purple background.

## Rules
- No real names, numbers, file names or photos; re-read each frame before uploading.
- Same device, same theme per set; do one light and one dark set if space allows.
- Re-shoot after any UI change; filenames `01_results.png` ... `08_about.png`.

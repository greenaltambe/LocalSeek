# Launcher icon candidates ("Hazel" the squirrel)

> Cleanup note (2026-10-05): the round-1 candidate files and the unshipped round-2 variant B (acorn) were archived and removed from the tree. This README still describes them for provenance; only `round2/gen_hazel.py` and the `hazel_A_*` files (the shipped icon) remain.

Original flat designs, four colours (fur `#F0A04B`, shade `#D9822B`, cream `#FFF1D6`, ink `#2B1B12`) on the app
purple `#6750A4`. Nothing third-party is used. SVG sources are 108x108 (the adaptive icon grid); PNGs are renders.

| # | File | Idea |
|---|---|---|
| 1 | `candidate1_peeking_squirrel.svg` | squirrel head peeking over a magnifier (**implemented**) |
| 2 | `candidate2_acorn_lens.svg` | acorn-shaped magnifier lens, tail curl as the handle |
| 3 | `candidate3_badge_face.svg` | squirrel face in a rounded-square badge |

Rendered with ImageMagick (`magick -background none -density 96 x.svg x.png`), the only SVG renderer on the build machine.
The old in-app mascot preview was removed in round 2 (the mascot now uses the launcher drawing).

## Where candidate 1 lives

- `app/src/main/res/drawable/ic_launcher_foreground.xml`: colour layer (also the Android 12 splash icon)
- `app/src/main/res/drawable/ic_launcher_monochrome.xml`: single-colour layer for themed icons (Android 13+)
- `app/src/main/res/drawable/ic_launcher_background.xml` + `colors.xml` `ic_launcher_background`: solid purple
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher*.xml` reference those three and never need to change.

## Switching to candidate 2 or 3

Replace the two drawable files `ic_launcher_foreground.xml` and `ic_launcher_monochrome.xml` with a vector version of
the chosen SVG (shapes map one to one to `<path android:pathData>`; circles become `M cx-r,cy a r,r 0 1,0 2r,0 a r,r 0 1,0 -2r,0z`).
Keep everything inside the 66 dp centre circle. The splash screen, round icon and adaptive icon follow automatically.

## Round 2: one Hazel (current)

Round 1 (candidate 1) had a different character in the app (round ears, acorn) and a lens covering the chin. Round 2 replaces
both with a single design, generated from one script so the launcher icon and the in-app mascot cannot drift apart.

- Spec: head wider than tall, small slightly pointed ears, big eyes with a highlight, cream muzzle, upright body with cream belly,
  one large S-curved tail with a lighter stripe. Five flat colours (fur, shade, cream, ink + the purple tile `#6750A4`, unchanged).
- `round2/gen_hazel.py` is the single source. It writes `ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`, the in-app
  layers `hazel_tail/body/paws_rest/lens.xml`, and the SVG + PNG previews (needs ImageMagick). It also prints the art's max radius,
  checked against the 33 unit (66 dp) safe zone.
- Variants (`round2/hazel_*`): **A** = magnifier in both paws (**implemented**), **B** = acorn in paws, no magnifier (not shipped).
  PNGs at 48/108/432 px: plain, `_dark` and `_light` wallpaper tiles (icon masked to a circle).
- Smile is a filled crescent (a 4% stroke would be a blob at that size); every real stroke is at least 4.4 units.
- Monochrome layer: black silhouette, eyes and a gap round the lens cut out with a clip path.
- Used by: adaptive icon, Android 12 splash (foreground), loader, empty states, About, onboarding (`ui/mascot/Hazel.kt`).
- Not verified on a device: monochrome clip-path rendering and splash cropping need a look on the phone.

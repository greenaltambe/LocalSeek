# AV: energy of the shipped search path (numbers only)

Measured 2026-11-01 on one phone (OnePlus CPH2707, Android 16), debug build, config SHIPPED (E9), existing index (14,886 chunks), screen on, Do Not Disturb on, on battery (59 % to 55 % over the run), thermal status NONE throughout. Wi-Fi adb connected. Test: `EnergySearchInstrumentedTest`, 3 repeats of (10 min idle, then the five generic queries cycled to 600 searches). Source of the charge: `BATTERY_PROPERTY_CHARGE_COUNTER` (supported; it moves in steps of 1 mAh). Analysis: `python3 eval/energy/analyze_energy.py --json ~/localseek-private/energy/energy_av.json`.

| Repeat | Idle, mAh per 10 min | Search phase | Search, mAh per 10 min (scaled) | Net, mAh per 100 searches |
|---|---|---|---|---|
| 1 | 73.0 | 600 searches in 132 s | 145.1 | 2.65 |
| 2 | 57.0 | 600 searches in 130 s | 138.5 | 2.94 |
| 3 | 36.0 | 600 searches in 130 s | 133.9 | 3.53 |
| **Median** | **57.0** | | **138.5** | **2.94 (range 2.65 to 3.53)** |

Net = search-phase charge minus the idle rate times the search-phase duration, per 100 searches. The 600-search limit was reached in about 2 minutes, so the search phase did not run the full 10 minutes; the "per 10 min" search figure is the measured charge scaled to 600 s and is shown only for comparison with idle.

Caveats:
- Percentage of battery capacity: not computed, the phone's rated capacity was not available from the device (pending from the owner). The charge counter and level (about 2.74 Ah at 47 %) suggest roughly 5.8 Ah, which is an estimate, not a rating.
- Idle drain fell from 73 to 36 mAh per 10 min across the repeats (screen-on background activity settling), so the net figure is sensitive to it: a 10 mAh difference in idle per 10 min changes the net by about 0.4 mAh per 100 searches. The range over the three repeats, 2.65 to 3.53, is the honest uncertainty, not a confidence interval.
- The counter resolution (1 mAh) is about 3 % of the search-phase charge.
- One phone, debug build, screen on at about half brightness, 600 searches per repeat, searches run back to back (no user think time).

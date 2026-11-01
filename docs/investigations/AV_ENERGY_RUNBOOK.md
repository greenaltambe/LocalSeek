# AV energy measurement: runbook for the owner

What this measures: battery charge used per 100 shipped searches, minus the idle drain over the same time.
The test does 3 repeats of: 10 minutes idle (screen on, no searches), then 10 minutes of searches (the five generic queries, up to 600 searches). Total about 60 to 65 minutes. The phone must run on battery the whole time. The test never touches the index and never launches the app screen (that would start indexing).

## 1. Prepare the phone
1. Charge to **above 50 %**, then unplug later (step 3). Let it cool: it should not feel warm.
2. Settings > Display: set brightness manually to **about 50 %** (turn adaptive brightness off), and set the screen timeout to the **longest value (30 minutes)**.
3. Turn **Do Not Disturb on**. Close all other apps (clear the recent-apps list). Leave Wi-Fi as it is, but do not use the phone during the run.
4. Airplane mode stays OFF (wireless debugging needs Wi-Fi).

## 2. Wireless debugging (cable still connected for now)
1. Phone: Settings > Developer options > **Wireless debugging** on > "Pair device with pairing code". Note the pairing IP:port and the 6-digit code.
2. Computer: `adb pair <ip>:<pairing-port>` and enter the code.
3. Back on the Wireless debugging screen note the main **IP:port** (a different port) and run `adb connect <ip>:<port>`.
4. Check `adb devices`: the wireless entry must say `device`. (It may already be connected: then skip.)

## 3. Unplug and start
1. Unplug the USB cable. Check on the computer: `adb shell dumpsys battery | grep -E "AC powered|USB powered"` must show `false` for both.
2. Wake the screen and leave it on the home screen.
3. Start the test **without `-w`**, so it keeps running on the phone even if the Wi-Fi connection drops (the command returns at once):
```
adb shell am instrument -e class com.augt.localseek.smoke.EnergySearchInstrumentedTest \
  com.augt.localseek.test/androidx.test.runner.AndroidJUnitRunner
```
4. Do not touch the phone. If it starts charging or gets hot (thermal above LIGHT) the test stops itself and logs `ENERGYPROBE ABORTED: ...`; no JSON is written then.
5. Progress (optional, numbers only; works only while adb is connected): `adb logcat -d -s ENERGYPROBE`.

## 4. How to tell it has finished (after about 65 minutes)
Either of these (reconnect with `adb connect <ip>:<port>` first if the link dropped):
- the file exists: `adb shell ls -l /sdcard/Android/data/com.augt.localseek/files/energy_av.json`
- logcat shows the line `ENERGYPROBE done: energy_av.json written`: `adb logcat -d -s ENERGYPROBE | tail -5`
If logcat shows `ABORTED` instead, tell Claude the message.

Pull the result (no cable needed):
```
mkdir -p ~/localseek-private/energy
adb pull /sdcard/Android/data/com.augt.localseek/files/energy_av.json ~/localseek-private/energy/energy_av.json || \
adb exec-out run-as com.augt.localseek cat files/energy_av.json > ~/localseek-private/energy/energy_av.json
ls -l ~/localseek-private/energy/energy_av.json
```
Then tell Claude "energy finished".

Do not run `adb uninstall`, `pm clear` or anything that deletes app data.

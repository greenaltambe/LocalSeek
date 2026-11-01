package com.augt.localseek.smoke

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.augt.localseek.LocalSeekApplication
import com.augt.localseek.core.config.RetrievalConfig
import com.augt.localseek.di.AppContainer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Energy cost of the shipped search path (task AV part 1): three repeats of
 *   idle phase (10 min, screen on, no searches) then search phase (the five generic queries cycled for 10 min or 600 searches).
 * Every 10 s the charge counter (uAh) and CURRENT_NOW (uA) are sampled. If the charge counter is unsupported (MIN_VALUE or 0),
 * CURRENT_NOW is integrated instead and the JSON says so. Output: numbers only, in getExternalFilesDir/energy_av.json.
 *
 * Refuses to run while charging; aborts if the thermal status goes above LIGHT. Read-only on the index: no indexing, no writes
 * to the database. MainActivity is deliberately NOT launched (it would start indexing); the instrumented process is the
 * foreground process and the screen is kept on by the runbook settings plus a wake-up key event at every sample.
 *
 *   adb shell am instrument -w -e class com.augt.localseek.smoke.EnergySearchInstrumentedTest \
 *     com.augt.localseek.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class EnergySearchInstrumentedTest {

    private val queries = listOf("pdf", "notes", "settings", "camera", "meeting")
    private val phaseMs = 10 * 60_000L
    private val sampleMs = 10_000L
    private val maxSearches = 600
    private val repeats = 3

    private class Sample(val tMs: Long, val chargeUah: Long, val currentUa: Long)

    private class PhaseResult(
        val name: String,
        val elapsedMs: Long,
        val searches: Int,
        val usedUah: Double,
        val method: String,
        val samples: Int,
        val levelStart: Int,
        val levelEnd: Int,
        val tempStartC10: Int,
        val tempEndC10: Int,
        val thermalEnd: Int,
    )

    private fun batteryIntent(context: Context): Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun plugged(context: Context): Boolean {
        val i = batteryIntent(context)
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0 || bm.isCharging
    }

    private fun thermal(context: Context): Int =
        if (Build.VERSION.SDK_INT >= 29) (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus else 0

    private fun level(context: Context) = batteryIntent(context)?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    private fun tempC10(context: Context) = batteryIntent(context)?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1

    private fun wake() {
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        }
    }

    private fun sample(bm: BatteryManager): Sample = Sample(
        SystemClock.elapsedRealtime(),
        bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
        bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
    )

    private fun counterOk(s: Sample) = s.chargeUah != Int.MIN_VALUE.toLong() && s.chargeUah != Long.MIN_VALUE && s.chargeUah > 0

    private fun runPhase(context: Context, name: String, work: ((Long) -> Int)?): PhaseResult {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val samples = ArrayList<Sample>()
        val levelStart = level(context)
        val tempStart = tempC10(context)
        val start = SystemClock.elapsedRealtime()
        samples += sample(bm)
        var nextSample = start + sampleMs
        var searches = 0
        while (SystemClock.elapsedRealtime() - start < phaseMs && (work == null || searches < maxSearches)) {
            if (work != null) searches += work(SystemClock.elapsedRealtime()) else Thread.sleep(250)
            val now = SystemClock.elapsedRealtime()
            if (now >= nextSample) {
                wake()
                assertTrue("not charging is required, but the phone started charging during $name", !plugged(context))
                assertTrue("thermal status above LIGHT during $name: ${thermal(context)}", thermal(context) <= PowerManager.THERMAL_STATUS_LIGHT)
                samples += sample(bm)
                nextSample += sampleMs
            }
        }
        val end = SystemClock.elapsedRealtime()
        samples += sample(bm)
        val useCounter = samples.all(::counterOk)
        val used: Double
        if (useCounter) {
            used = (samples.first().chargeUah - samples.last().chargeUah).toDouble()
        } else {
            // integrate |CURRENT_NOW| (uA) over time (ms) -> uAh; the sign of CURRENT_NOW differs between phones, so use the magnitude
            var sum = 0.0
            for (k in 1 until samples.size) {
                val dtH = (samples[k].tMs - samples[k - 1].tMs) / 3_600_000.0
                val avg = (Math.abs(samples[k].currentUa) + Math.abs(samples[k - 1].currentUa)) / 2.0
                sum += avg * dtH
            }
            used = sum
        }
        return PhaseResult(
            name, end - start, searches, used, if (useCounter) "charge_counter" else "current_now_integral",
            samples.size, levelStart, level(context), tempStart, tempC10(context), thermal(context),
        )
    }

    @Test
    fun energyIdleVersusShippedSearches() {
        try {
            runEnergy()
            Log.i("ENERGYPROBE", "done: energy_av.json written")
        } catch (t: Throwable) {
            Log.e("ENERGYPROBE", "ABORTED: ${t.message}")
            throw t
        }
    }

    private fun runEnergy() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        assertTrue("Unplug the cable first: the energy test must run on battery (use wireless debugging).", !plugged(context))
        assertTrue("thermal status is above LIGHT at start: ${thermal(context)}", thermal(context) <= PowerManager.THERMAL_STATUS_LIGHT)
        val container = (context as? LocalSeekApplication)?.appContainer ?: AppContainer(context)
        val config = RetrievalConfig.SHIPPED
        assertTrue("the phone has no index to search", container.database.chunkDao().countAllChunks() > 0)
        container.searchEngine.search("warmup", config) // load the dense index before measuring
        wake()

        val thermalStart = thermal(context)
        val levelStart = level(context)
        val rounds = JSONArray()
        var q = 0
        repeat(repeats) { r ->
            Log.i("ENERGYPROBE", "repeat ${r + 1}/$repeats: idle phase")
            val idle = runPhase(context, "idle", null)
            Log.i("ENERGYPROBE", "repeat ${r + 1}/$repeats: search phase")
            val search = runPhase(context, "search", { _ ->
                val query = queries[q++ % queries.size]
                runBlocking { container.searchEngine.search(query, config) }
                1
            })
            // idle drain scaled to the search phase's duration, then net per 100 searches
            val idlePerMs = idle.usedUah / idle.elapsedMs
            val netUah = search.usedUah - idlePerMs * search.elapsedMs
            val netMahPer100 = if (search.searches > 0) netUah / 1000.0 / search.searches * 100.0 else Double.NaN
            rounds.put(JSONObject().apply {
                put("repeat", r + 1)
                put("idle", phaseJson(idle))
                put("search", phaseJson(search))
                put("netMahPer100Searches", netMahPer100)
            })
            Log.i("ENERGYPROBE", "repeat ${r + 1}: net mAh per 100 searches = $netMahPer100")
        }
        val root = JSONObject().apply {
            put("task", "AV-energy")
            put("model", Build.MODEL)
            put("manufacturer", Build.MANUFACTURER)
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("config", "SHIPPED")
            put("batteryLevelStart", levelStart)
            put("batteryLevelEnd", level(context))
            put("thermalStatusStart", thermalStart)
            put("thermalStatusEnd", thermal(context))
            put("phaseSeconds", phaseMs / 1000)
            put("sampleSeconds", sampleMs / 1000)
            put("maxSearchesPerPhase", maxSearches)
            put("rounds", rounds)
        }
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        File(dir, "energy_av.json").writeText(root.toString(2))
        Log.i("ENERGYPROBE", "wrote ${File(dir, "energy_av.json").absolutePath}")
        assertTrue("no searches ran", (0 until rounds.length()).all { rounds.getJSONObject(it).getJSONObject("search").getInt("searches") > 0 })
    }

    private fun phaseJson(p: PhaseResult) = JSONObject().apply {
        put("phase", p.name)
        put("elapsedMs", p.elapsedMs)
        put("searches", p.searches)
        put("usedUah", p.usedUah)
        put("method", p.method)
        put("samples", p.samples)
        put("batteryLevelStart", p.levelStart)
        put("batteryLevelEnd", p.levelEnd)
        put("tempStartDeciC", p.tempStartC10)
        put("tempEndDeciC", p.tempEndC10)
        put("thermalStatusEnd", p.thermalEnd)
    }
}

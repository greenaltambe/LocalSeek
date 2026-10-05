package com.augt.localseek.tools

import org.json.JSONException
import org.json.JSONObject

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Two accent presets: [PURPLE] is the app's original palette. */
enum class AccentPreset { PURPLE, GREEN }

/**
 * User theme choice. [dynamicColor] means Material You wallpaper colours and only takes effect on API 31+;
 * it defaults to off so existing installs keep the original look until the user opts in.
 */
data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val accent: AccentPreset = AccentPreset.PURPLE,
    val dynamicColor: Boolean = false
) {
    fun isDark(systemDark: Boolean): Boolean = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    fun useDynamic(sdkInt: Int): Boolean = dynamicColor && sdkInt >= DYNAMIC_MIN_SDK

    companion object {
        const val DYNAMIC_MIN_SDK = 31

        fun toJson(t: ThemeSettings): String = JSONObject()
            .put("mode", t.mode.name)
            .put("accent", t.accent.name)
            .put("dynamicColor", t.dynamicColor)
            .toString()

        /** Unknown enum names fall back to defaults field by field. Null if [json] is not a JSON object. */
        fun fromJson(json: String): ThemeSettings? = try {
            val o = JSONObject(json)
            ThemeSettings(
                mode = ThemeMode.values().firstOrNull { it.name == o.optString("mode") } ?: ThemeMode.SYSTEM,
                accent = AccentPreset.values().firstOrNull { it.name == o.optString("accent") } ?: AccentPreset.PURPLE,
                dynamicColor = o.optBoolean("dynamicColor", false)
            )
        } catch (_: JSONException) {
            null
        }
    }
}

package com.augt.localseek.tools

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A pinned result, identified the same way the index identifies it. Only these two strings are stored; titles and
 * paths are looked up in the index when the pin strip is shown, so pins never duplicate personal data.
 * [entityType] is an EntityType name: FILE, APP or CONTACT.
 */
data class Pin(val entityType: String, val stableKey: String) {
    val id: String get() = "$entityType:$stableKey"
}

object Pins {

    const val MAX_PINS = 12
    val PINNABLE_TYPES = setOf("FILE", "APP", "CONTACT")

    fun isPinnable(entityType: String, stableKey: String) =
        entityType in PINNABLE_TYPES && stableKey.isNotBlank()

    /** Adds the pin (newest last, capped at [MAX_PINS], dropping the oldest) or removes it if already present. */
    fun toggle(pins: List<Pin>, pin: Pin): List<Pin> {
        if (!isPinnable(pin.entityType, pin.stableKey)) return pins
        if (pins.any { it.id == pin.id }) return pins.filterNot { it.id == pin.id }
        return (pins + pin).takeLast(MAX_PINS)
    }

    fun toJson(pins: List<Pin>): String {
        val arr = JSONArray()
        pins.forEach { arr.put(JSONObject().put("entityType", it.entityType).put("stableKey", it.stableKey)) }
        return arr.toString()
    }

    /** Lenient decode: invalid or duplicate entries are dropped, the list is capped. Null if not a JSON array. */
    fun fromJson(json: String): List<Pin>? = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val type = o.optString("entityType"); val key = o.optString("stableKey")
            if (isPinnable(type, key)) Pin(type, key) else null
        }.distinctBy { it.id }.takeLast(MAX_PINS)
    } catch (_: JSONException) {
        null
    }
}

package com.augt.localseek.tools

import java.time.Clock
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Offline date/time helper: "days until 2027-01-01", "days since ...", "days between A and B", "now in tokyo". */
class DateTimeTools(private val clock: Clock = Clock.systemDefaultZone()) {

    data class Answer(val title: String, val value: String)

    companion object {
        private val CITY_ZONES = mapOf(
            "tokyo" to "Asia/Tokyo", "osaka" to "Asia/Tokyo", "seoul" to "Asia/Seoul",
            "beijing" to "Asia/Shanghai", "shanghai" to "Asia/Shanghai", "hong kong" to "Asia/Hong_Kong",
            "singapore" to "Asia/Singapore", "bangkok" to "Asia/Bangkok", "jakarta" to "Asia/Jakarta",
            "delhi" to "Asia/Kolkata", "mumbai" to "Asia/Kolkata", "kolkata" to "Asia/Kolkata",
            "bangalore" to "Asia/Kolkata", "india" to "Asia/Kolkata", "dubai" to "Asia/Dubai",
            "karachi" to "Asia/Karachi", "dhaka" to "Asia/Dhaka", "istanbul" to "Europe/Istanbul",
            "moscow" to "Europe/Moscow", "london" to "Europe/London", "dublin" to "Europe/Dublin",
            "paris" to "Europe/Paris", "berlin" to "Europe/Berlin", "madrid" to "Europe/Madrid",
            "rome" to "Europe/Rome", "amsterdam" to "Europe/Amsterdam", "zurich" to "Europe/Zurich",
            "cairo" to "Africa/Cairo", "lagos" to "Africa/Lagos", "nairobi" to "Africa/Nairobi",
            "johannesburg" to "Africa/Johannesburg", "sydney" to "Australia/Sydney",
            "melbourne" to "Australia/Melbourne", "auckland" to "Pacific/Auckland",
            "new york" to "America/New_York", "nyc" to "America/New_York", "toronto" to "America/Toronto",
            "chicago" to "America/Chicago", "denver" to "America/Denver",
            "los angeles" to "America/Los_Angeles", "la" to "America/Los_Angeles",
            "san francisco" to "America/Los_Angeles", "seattle" to "America/Los_Angeles",
            "mexico city" to "America/Mexico_City", "sao paulo" to "America/Sao_Paulo",
            "buenos aires" to "America/Argentina/Buenos_Aires", "utc" to "UTC", "gmt" to "GMT"
        )

        private val ZONE_IDS_BY_LOWER: Map<String, String> by lazy {
            ZoneId.getAvailableZoneIds().associateBy { it.lowercase(Locale.ROOT) }
        }

        private val UNTIL = Regex("""^days\s+(until|to|till|since|from)\s+(\d{4}-\d{2}-\d{2})$""")
        private val BETWEEN = Regex("""^days\s+between\s+(\d{4}-\d{2}-\d{2})\s+and\s+(\d{4}-\d{2}-\d{2})$""")
        private val TIME_IN = Regex("""^(?:now|time|current time|date|clock)\s+in\s+(.+)$""")

        fun resolveZone(name: String): ZoneId? {
            val key = name.trim().lowercase(Locale.ROOT).replace('_', ' ')
            CITY_ZONES[key]?.let { return ZoneId.of(it) }
            val id = ZONE_IDS_BY_LOWER[name.trim().lowercase(Locale.ROOT).replace(' ', '_')] ?: return null
            return try { ZoneId.of(id) } catch (_: DateTimeException) { null }
        }

        private fun plural(n: Long, unit: String) = "$n $unit" + if (n == 1L || n == -1L) "" else "s"
    }

    /** Returns null if the query is not a date/time question. */
    fun answer(query: String): Answer? {
        val q = query.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        if (q == "now" || q == "today" || q == "time" || q == "date") {
            val now = ZonedDateTime.now(clock)
            return Answer(
                "Now (${now.zone})",
                now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH))
            )
        }
        TIME_IN.matchEntire(q)?.let { m ->
            val zone = resolveZone(m.groupValues[1]) ?: return null
            val now = ZonedDateTime.now(clock).withZoneSameInstant(zone)
            return Answer(
                "Time in ${m.groupValues[1].trim().replaceFirstChar { it.uppercase() }} ($zone)",
                now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm (xxx)", Locale.ENGLISH))
            )
        }
        BETWEEN.matchEntire(q)?.let { m ->
            val a = parseDate(m.groupValues[1]) ?: return null
            val b = parseDate(m.groupValues[2]) ?: return null
            return Answer("Days between ${m.groupValues[1]} and ${m.groupValues[2]}", plural(Math.abs(ChronoUnit.DAYS.between(a, b)), "day"))
        }
        UNTIL.matchEntire(q)?.let { m ->
            val target = parseDate(m.groupValues[2]) ?: return null
            val today = LocalDate.now(clock)
            val days = ChronoUnit.DAYS.between(today, target)
            val since = m.groupValues[1] == "since" || m.groupValues[1] == "from"
            val title = (if (since) "Days since " else "Days until ") + m.groupValues[2]
            val value = when {
                days == 0L -> "Today"
                since && days < 0 -> plural(-days, "day") + " ago"
                since -> "in " + plural(days, "day") + " (date is in the future)"
                days > 0 -> "in " + plural(days, "day")
                else -> plural(-days, "day") + " ago (date has passed)"
            }
            return Answer(title, value)
        }
        return null
    }

    private fun parseDate(s: String): LocalDate? = try { LocalDate.parse(s) } catch (_: DateTimeException) { null }
}

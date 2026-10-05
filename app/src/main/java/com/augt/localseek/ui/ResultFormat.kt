package com.augt.localseek.ui

import com.augt.localseek.model.EntityType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Display-only helpers for result cards. Pure Kotlin so they can be unit tested without Android. */
object ResultFormat {

    /** Timestamps below this are seconds (1e11 ms is 1973; 1e11 s is year 5138). */
    private const val SECONDS_THRESHOLD = 100_000_000_000L

    /** Image rows store epoch seconds, files store milliseconds: normalise to milliseconds, 0 stays 0. */
    fun toEpochMillis(timestamp: Long): Long =
        if (timestamp in 1 until SECONDS_THRESHOLD) timestamp * 1000L else timestamp

    /** Only files and images carry a meaningful date; for apps and contacts it is just the index time. */
    fun showsDate(type: EntityType): Boolean = type == EntityType.FILE || type == EntityType.IMAGE

    sealed interface DateLabel {
        data object Today : DateLabel
        data object Yesterday : DateLabel
        data class DaysAgo(val days: Int) : DateLabel
        data class Absolute(val date: LocalDate) : DateLabel
    }

    /** Relative date for the last week, an absolute date afterwards; null when the timestamp is unknown. */
    fun dateLabel(timestamp: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): DateLabel? {
        val millis = toEpochMillis(timestamp)
        if (millis <= 0L) return null
        val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val days = (today.toEpochDay() - date.toEpochDay()).toInt()
        return when {
            days < 0 -> DateLabel.Absolute(date)
            days == 0 -> DateLabel.Today
            days == 1 -> DateLabel.Yesterday
            days < 7 -> DateLabel.DaysAgo(days)
            else -> DateLabel.Absolute(date)
        }
    }

    /**
     * The last two folder segments that contain the file, e.g. "Documents/Notes" for ".../Documents/Notes/a.txt",
     * so identical file names in different folders can be told apart. Null for content URIs or files at the root.
     */
    fun parentFolder(path: String): String? {
        if (path.contains("://")) return null
        val segments = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (segments.size < 2) return null
        return segments.dropLast(1).takeLast(2).joinToString("/")
    }

    /** First letter or digit, upper-cased; "#" when the name has none. */
    fun initial(name: String): String {
        val cp = name.codePoints().filter { Character.isLetterOrDigit(it) }.findFirst()
        return if (cp.isPresent) String(Character.toChars(Character.toUpperCase(cp.asInt))) else "#"
    }

    /** Deterministic hue in 0..360 from the name, so a contact keeps its colour. */
    fun avatarHue(name: String): Float {
        var h = 0
        for (ch in name.trim().lowercase()) h = h * 31 + ch.code
        return (h.toLong() and 0x7fffffffL).rem(360L).toFloat()
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val kb = bytes / 1024.0
        if (kb < 1024) return "${kb.toInt().coerceAtLeast(1)} KB"
        return String.format(java.util.Locale.US, "%.1f MB", kb / 1024.0)
    }
}

/** File extension buckets used for icons and the file-type filter. */
enum class FileCategory { PDF, MARKDOWN, TEXT, CODE, DOCUMENT, OTHER;

    companion object {
        private val CODE_EXT = setOf(
            "json", "xml", "html", "htm", "kt", "kts", "java", "py", "js", "ts", "c", "cpp", "h", "cs", "go", "rs",
            "sh", "sql", "yaml", "yml", "toml", "css", "gradle", "ini", "properties"
        )
        private val DOC_EXT = setOf("doc", "docx", "odt", "rtf", "ppt", "pptx", "odp", "xls", "xlsx", "ods", "csv", "epub")

        fun of(extension: String): FileCategory {
            val e = extension.trim().trimStart('.').lowercase()
            return when {
                e == "pdf" -> PDF
                e == "md" || e == "markdown" -> MARKDOWN
                e == "txt" || e == "text" || e == "log" -> TEXT
                e in CODE_EXT -> CODE
                e in DOC_EXT -> DOCUMENT
                else -> OTHER
            }
        }
    }
}

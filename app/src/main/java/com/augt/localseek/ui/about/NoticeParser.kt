package com.augt.localseek.ui.about

/** One block of THIRD_PARTY_NOTICES.md: a `## ` heading and its text. [verbatim] blocks are fenced licence texts. */
data class NoticeSection(val title: String, val body: String, val verbatim: List<String>)

/**
 * Splits THIRD_PARTY_NOTICES.md into sections for display. Markdown is reduced to plain text: headings become
 * titles, `**`/backticks/links are flattened, and ``` fenced licence texts are kept verbatim and separate.
 */
object NoticeParser {

    fun parse(markdown: String): List<NoticeSection> {
        val sections = mutableListOf<NoticeSection>()
        var title: String? = null
        val body = StringBuilder()
        val verbatim = mutableListOf<String>()
        var fence: StringBuilder? = null

        fun flush() {
            val t = title ?: return
            sections += NoticeSection(t, body.toString().trim(), verbatim.toList())
            body.clear()
            verbatim.clear()
        }

        for (line in markdown.lines()) {
            if (line.trimStart().startsWith("```")) {
                val open = fence
                if (open == null) fence = StringBuilder() else {
                    verbatim += open.toString().trimEnd()
                    fence = null
                }
                continue
            }
            fence?.let { it.append(line).append('\n'); continue }
            when {
                line.startsWith("## ") -> {
                    flush()
                    title = stripInline(line.removePrefix("## ")).replace(Regex("^\\d+\\.\\s*"), "")
                }
                line.startsWith("# ") || line.trim() == "---" -> Unit
                title != null -> body.append(stripInline(line)).append('\n')
            }
        }
        flush()
        return sections
    }

    /** Flattens inline markdown: links become "text (url)", emphasis and code markers are dropped. */
    fun stripInline(text: String): String = text
        .replace(Regex("\\[([^\\]]+)]\\(([^)]+)\\)"), "$1 ($2)")
        .replace("**", "")
        .replace("`", "")
        .replace(Regex("^\\s*-\\s+"), "• ")
}

package com.augt.localseek.eval

data class BenchmarkQuery(
    val queryId: String,
    val text: String,
    val category: String,
    val clusterId: String
)

/**
 * Parses and validates benchmark queries from CSV files (queries.csv).
 */
object BenchmarkQueryLoader {
    const val MIN_REQUIRED_QUERIES = 50
    const val MIN_IMAGE_QUERIES = 8

    fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '\"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    result.add(current.toString().trim())
                    current = StringBuilder()
                }
                else -> current.append(ch)
            }
        }
        result.add(current.toString().trim())
        return result
    }

    fun parseQueries(lines: List<String>, isImageEnabled: Boolean = false, filePath: String = "queries.csv"): List<BenchmarkQuery> {
        if (lines.isEmpty()) {
            throw IllegalStateException("Benchmark queries CSV at $filePath is empty.")
        }

        val headerLine = lines.first().trim()
        val headerCols = parseCsvLine(headerLine).map { it.lowercase().trim() }

        val qidIdx = headerCols.indexOfFirst { it == "query_id" || it == "queryid" || it == "qid" }
        val textIdx = headerCols.indexOfFirst { it == "text" || it == "query_text" || it == "query" }
        val catIdx = headerCols.indexOfFirst { it == "category" }
        val clusterIdx = headerCols.indexOfFirst { it == "cluster_id" || it == "clusterid" }

        if (qidIdx == -1 || textIdx == -1 || catIdx == -1 || clusterIdx == -1) {
            throw IllegalStateException(
                "Benchmark queries CSV at $filePath has invalid header: '$headerLine'. " +
                "Expected columns: query_id,text,category,cluster_id"
            )
        }

        val queries = mutableListOf<BenchmarkQuery>()
        for (lineIdx in 1 until lines.size) {
            val line = lines[lineIdx].trim()
            if (line.isEmpty()) continue
            val cols = parseCsvLine(line)
            if (cols.size <= maxOf(qidIdx, textIdx, catIdx, clusterIdx)) {
                continue
            }
            val qid = cols[qidIdx].trim()
            val text = cols[textIdx].trim()
            val category = cols[catIdx].trim()
            val clusterId = cols[clusterIdx].trim()
            if (text.isNotEmpty()) {
                queries.add(BenchmarkQuery(qid, text, category, clusterId))
            }
        }

        if (queries.size < MIN_REQUIRED_QUERIES && !isImageEnabled) {
            throw IllegalStateException(
                "Benchmark queries CSV at $filePath contains only ${queries.size} valid query rows " +
                "(minimum $MIN_REQUIRED_QUERIES required). " +
                "Please push a complete query set via: adb push <local_queries.csv> $filePath"
            )
        }

        if (isImageEnabled) {
            val imageQueryCount = queries.count { it.category.equals("image", ignoreCase = true) }
            if (imageQueryCount < MIN_IMAGE_QUERIES) {
                throw IllegalStateException(
                    "Benchmark queries CSV at $filePath contains only $imageQueryCount queries with category 'image' " +
                    "(minimum $MIN_IMAGE_QUERIES required when enableImage is on). " +
                    "Please update queries.csv with >=8 image queries."
                )
            }
        }

        return queries
    }
}

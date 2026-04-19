package com.augt.localseek.search.query

class IntentClassifier {

    enum class Intent {
        LOOKUP,
        EXPLORATION,
        FACT_FINDING,
        CODE_SEARCH,
        RECENT_FILES
    }

    data class IntentPrediction(
        val intent: Intent,
        val confidence: Float,
        val reasoning: String
    )

    fun classify(
        originalQuery: String,
        normalizedQuery: String,
        tokens: List<SmartTokenizer.Token>,
        entities: List<EntityExtractor.Entity>
    ): IntentPrediction {
        val scores = mutableMapOf<Intent, Float>()
        val reasons = mutableMapOf<Intent, MutableList<String>>()

        Intent.entries.forEach {
            scores[it] = 0.0f
            reasons[it] = mutableListOf()
        }

        if (
            normalizedQuery.startsWith("find") || normalizedQuery.startsWith("open") ||
            normalizedQuery.startsWith("show")
        ) {
            scores[Intent.LOOKUP] = scores[Intent.LOOKUP]!! + 0.8f
            reasons[Intent.LOOKUP]!!.add("starts with action verb")
        }

        if (originalQuery.contains("my ", ignoreCase = true)) {
            scores[Intent.LOOKUP] = scores[Intent.LOOKUP]!! + 0.6f
            reasons[Intent.LOOKUP]!!.add("contains possessive 'my'")
        }

        if (entities.any { it.type == EntityExtractor.EntityType.FILE_TYPE }) {
            scores[Intent.LOOKUP] = scores[Intent.LOOKUP]!! + 0.5f
            reasons[Intent.LOOKUP]!!.add("specifies file type")
        }

        val questionStarts = listOf("what is", "how does", "how to", "why", "when", "where")
        if (questionStarts.any { normalizedQuery.startsWith(it) }) {
            scores[Intent.FACT_FINDING] = scores[Intent.FACT_FINDING]!! + 0.9f
            reasons[Intent.FACT_FINDING]!!.add("question format")
        }

        if (normalizedQuery.contains("explain") || normalizedQuery.contains("definition")) {
            scores[Intent.FACT_FINDING] = scores[Intent.FACT_FINDING]!! + 0.7f
            reasons[Intent.FACT_FINDING]!!.add("explanation request")
        }

        if (
            normalizedQuery.contains("example") || normalizedQuery.contains("sample") ||
            normalizedQuery.contains("snippet") || normalizedQuery.contains("code")
        ) {
            scores[Intent.CODE_SEARCH] = scores[Intent.CODE_SEARCH]!! + 0.7f
            reasons[Intent.CODE_SEARCH]!!.add("code-related keywords")
        }

        if (entities.any { it.type == EntityExtractor.EntityType.PROGRAMMING_LANGUAGE }) {
            scores[Intent.CODE_SEARCH] = scores[Intent.CODE_SEARCH]!! + 0.6f
            reasons[Intent.CODE_SEARCH]!!.add("programming language mentioned")
        }

        if (normalizedQuery.contains("implement") || normalizedQuery.contains("build")) {
            scores[Intent.CODE_SEARCH] = scores[Intent.CODE_SEARCH]!! + 0.5f
            reasons[Intent.CODE_SEARCH]!!.add("implementation keywords")
        }

        if (
            entities.any {
                it.type == EntityExtractor.EntityType.DATE_RELATIVE ||
                    it.type == EntityExtractor.EntityType.DATE_ABSOLUTE
            }
        ) {
            scores[Intent.RECENT_FILES] = scores[Intent.RECENT_FILES]!! + 0.8f
            reasons[Intent.RECENT_FILES]!!.add("date entity present")
        }

        if (
            normalizedQuery.contains("recent") || normalizedQuery.contains("latest") ||
            normalizedQuery.contains("new")
        ) {
            scores[Intent.RECENT_FILES] = scores[Intent.RECENT_FILES]!! + 0.6f
            reasons[Intent.RECENT_FILES]!!.add("recency keywords")
        }

        if (
            tokens.size >= 2 && !normalizedQuery.startsWith("find") &&
            !questionStarts.any { normalizedQuery.startsWith(it) }
        ) {
            scores[Intent.EXPLORATION] = scores[Intent.EXPLORATION]!! + 0.4f
            reasons[Intent.EXPLORATION]!!.add("general topic query")
        }

        if (
            normalizedQuery.contains("tutorial") || normalizedQuery.contains("guide") ||
            normalizedQuery.contains("learn")
        ) {
            scores[Intent.EXPLORATION] = scores[Intent.EXPLORATION]!! + 0.6f
            reasons[Intent.EXPLORATION]!!.add("learning keywords")
        }

        val best = scores.maxByOrNull { it.value }
        val intent = best?.key ?: Intent.EXPLORATION
        val confidence = best?.value ?: 0.3f
        val reasoning = reasons[intent]?.joinToString("; ") ?: "default"

        return IntentPrediction(intent = intent, confidence = confidence, reasoning = reasoning)
    }
}



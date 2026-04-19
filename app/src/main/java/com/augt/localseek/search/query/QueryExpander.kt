package com.augt.localseek.search.query

import android.content.Context
import com.augt.localseek.ml.DenseEncoder

class QueryExpander(
    private val context: Context,
    private val encoder: DenseEncoder
) {

    data class ExpandedQuery(
        val originalTerms: List<String>,
        val synonyms: Map<String, List<String>>,
        val domainExpansions: Map<String, String>,
        val boostedTerms: Set<String>,
        val filters: QueryFilters
    )

    data class QueryFilters(
        val fileType: String? = null,
        val dateAfter: Long? = null,
        val dateBefore: Long? = null,
        val programmingLanguage: String? = null,
        val userOwned: Boolean = false
    )

    companion object {
        private val DOMAIN_EXPANSIONS = mapOf(
            "ml" to "machine learning",
            "ai" to "artificial intelligence",
            "dl" to "deep learning",
            "nlp" to "natural language processing",
            "cv" to "computer vision",
            "api" to "application programming interface",
            "rest" to "representational state transfer",
            "crud" to "create read update delete",
            "orm" to "object relational mapping",
            "sql" to "structured query language",
            "nosql" to "non relational database",
            "cicd" to "continuous integration deployment",
            "devops" to "development operations",
            "ui" to "user interface",
            "ux" to "user experience",
            "db" to "database",
            "regex" to "regular expression",
            "gpu" to "graphics processing unit",
            "cpu" to "central processing unit",
            "ram" to "random access memory",
            "ssd" to "solid state drive",
            "hdd" to "hard disk drive"
        )

        private val SYNONYMS = mapOf(
            "search" to listOf("find", "lookup", "query", "locate", "discover"),
            "document" to listOf("file", "doc", "paper", "article", "text"),
            "image" to listOf("picture", "photo", "graphic", "illustration", "img"),
            "create" to listOf("make", "build", "generate", "produce", "develop"),
            "delete" to listOf("remove", "erase", "discard", "eliminate"),
            "update" to listOf("modify", "change", "edit", "revise", "alter"),
            "optimize" to listOf("improve", "enhance", "refine", "boost"),
            "analyze" to listOf("examine", "study", "investigate", "evaluate"),
            "implement" to listOf("build", "develop", "code", "create", "construct"),
            "debug" to listOf("fix", "troubleshoot", "resolve", "repair"),
            "deploy" to listOf("release", "publish", "launch", "distribute"),
            "test" to listOf("verify", "validate", "check", "examine"),
            "design" to listOf("plan", "architect", "structure", "layout"),
            "algorithm" to listOf("method", "procedure", "approach", "technique", "process"),
            "framework" to listOf("library", "toolkit", "platform", "system"),
            "tutorial" to listOf("guide", "walkthrough", "lesson", "course", "howto"),
            "example" to listOf("sample", "demo", "illustration", "case", "instance"),
            "error" to listOf("bug", "issue", "problem", "exception", "fault"),
            "performance" to listOf("speed", "efficiency", "optimization", "throughput"),
            "security" to listOf("safety", "protection", "authentication", "encryption"),
            "learn" to listOf("study", "understand", "master", "grasp"),
            "explain" to listOf("describe", "clarify", "elucidate", "detail"),
            "install" to listOf("setup", "configure", "deploy", "initialize"),
            "run" to listOf("execute", "launch", "start", "invoke"),
            "configure" to listOf("setup", "set", "adjust", "customize"),
            "integrate" to listOf("combine", "merge", "connect", "link")
        )
    }

    suspend fun expand(
        tokens: List<SmartTokenizer.Token>,
        entities: List<EntityExtractor.Entity>
    ): ExpandedQuery {
        val originalTerms = tokens.filterNot { it.isStopword }.map { it.text }
        val synonymMap = mutableMapOf<String, List<String>>()
        val domainExpansionMap = mutableMapOf<String, String>()
        val boosted = mutableSetOf<String>()

        tokens.forEach { token ->
            DOMAIN_EXPANSIONS[token.text]?.let { expansion ->
                domainExpansionMap[token.text] = expansion
                boosted.add(token.text)
                boosted.add(expansion)
            }
        }

        tokens.filter { it.importance >= 0.6f && !it.isStopword }.forEach { token ->
            SYNONYMS[token.text]?.let { synonymMap[token.text] = it.take(3) }
        }

        entities.forEach { entity ->
            when (entity.type) {
                EntityExtractor.EntityType.PROGRAMMING_LANGUAGE,
                EntityExtractor.EntityType.TECHNICAL_DOMAIN -> {
                    boosted.add(entity.text)
                    if (entity.value is String) boosted.add(entity.value)
                }
                else -> Unit
            }
        }

        val fileType = entities.firstOrNull { it.type == EntityExtractor.EntityType.FILE_TYPE }?.value as? String
        val dateAfter = entities.firstOrNull {
            it.type == EntityExtractor.EntityType.DATE_RELATIVE ||
                it.type == EntityExtractor.EntityType.DATE_ABSOLUTE
        }?.value as? Long

        val programmingLanguage = entities.firstOrNull {
            it.type == EntityExtractor.EntityType.PROGRAMMING_LANGUAGE
        }?.value as? String

        val userOwned = entities.any { it.type == EntityExtractor.EntityType.PERSON_POSSESSIVE }

        return ExpandedQuery(
            originalTerms = originalTerms,
            synonyms = synonymMap,
            domainExpansions = domainExpansionMap,
            boostedTerms = boosted,
            filters = QueryFilters(
                fileType = fileType,
                dateAfter = dateAfter,
                programmingLanguage = programmingLanguage,
                userOwned = userOwned
            )
        )
    }

    // Keep BM25 query FTS-safe by repeating boosted terms instead of special syntax.
    fun toBM25Query(expanded: ExpandedQuery): String {
        val queryTerms = mutableListOf<String>()

        expanded.originalTerms.forEach { term ->
            queryTerms.add(term)
            if (term in expanded.boostedTerms) queryTerms.add(term)
        }

        expanded.domainExpansions.values.forEach { expansion ->
            queryTerms.addAll(expansion.split(" ").filter { it.isNotBlank() })
        }

        expanded.synonyms.values.forEach { syns -> queryTerms.addAll(syns) }

        return queryTerms.joinToString(" ").trim()
    }

    fun toDenseQuery(expanded: ExpandedQuery): String {
        val keyTerms = mutableListOf<String>()
        keyTerms.addAll(expanded.originalTerms)
        keyTerms.addAll(expanded.domainExpansions.values)
        return keyTerms.joinToString(" ").trim()
    }
}


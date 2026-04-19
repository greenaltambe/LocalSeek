package com.augt.localseek.search.query

import java.util.Calendar

class EntityExtractor {

    data class Entity(
        val text: String,
        val type: EntityType,
        val value: Any,
        val confidence: Float
    )

    enum class EntityType {
        FILE_TYPE,
        DATE_RELATIVE,
        DATE_ABSOLUTE,
        PROGRAMMING_LANGUAGE,
        TECHNICAL_DOMAIN,
        NUMERIC,
        PERSON_POSSESSIVE
    }

    companion object {
        private val FILE_TYPE_PATTERNS = mapOf(
            Regex("\\b(pdf|pdfs)\\b") to "pdf",
            Regex("\\b(doc|docx|word|document|documents)\\b") to "docx",
            Regex("\\b(txt|text|text file|textfile)\\b") to "txt",
            Regex("\\b(md|markdown)\\b") to "md",
            Regex("\\b(json)\\b") to "json",
            Regex("\\b(xml)\\b") to "xml",
            Regex("\\b(csv)\\b") to "csv",
            Regex("\\b(code|source)\\b") to "code"
        )

        private val RELATIVE_DATE_PATTERNS = mapOf(
            Regex("\\btoday\\b") to 0,
            Regex("\\byesterday\\b") to 1,
            Regex("\\blast week\\b") to 7,
            Regex("\\bthis week\\b") to 0,
            Regex("\\blast month\\b") to 30,
            Regex("\\bthis month\\b") to 0,
            Regex("\\blast year\\b") to 365,
            Regex("\\bthis year\\b") to 0
        )

        private val PROGRAMMING_LANGUAGES = setOf(
            "kotlin", "java", "python", "javascript", "typescript", "c++", "cpp",
            "rust", "go", "golang", "swift", "ruby", "php", "scala", "r", "matlab",
            "c#", "csharp", "dart", "perl", "haskell", "lua", "sql", "html", "css"
        )

        private val TECHNICAL_DOMAINS = mapOf(
            "ml" to "machine learning",
            "ai" to "artificial intelligence",
            "dl" to "deep learning",
            "nlp" to "natural language processing",
            "cv" to "computer vision",
            "api" to "application programming interface",
            "rest" to "rest api",
            "graphql" to "graphql",
            "db" to "database",
            "nosql" to "nosql database",
            "ui" to "user interface",
            "ux" to "user experience",
            "frontend" to "frontend development",
            "backend" to "backend development",
            "fullstack" to "fullstack development",
            "devops" to "devops",
            "cicd" to "continuous integration",
            "docker" to "containerization",
            "kubernetes" to "container orchestration",
            "aws" to "cloud computing",
            "azure" to "cloud computing",
            "gcp" to "cloud computing"
        )
    }

    fun extract(normalizedQuery: String): List<Entity> {
        val entities = mutableListOf<Entity>()

        FILE_TYPE_PATTERNS.forEach { (pattern, type) ->
            if (pattern.containsMatchIn(normalizedQuery)) {
                entities.add(Entity(type, EntityType.FILE_TYPE, type, 1.0f))
            }
        }

        RELATIVE_DATE_PATTERNS.forEach { (pattern, daysAgo) ->
            pattern.find(normalizedQuery)?.let { match ->
                val calendar = Calendar.getInstance()
                calendar.add(Calendar.DAY_OF_YEAR, -daysAgo)
                entities.add(
                    Entity(
                        text = match.value,
                        type = EntityType.DATE_RELATIVE,
                        value = calendar.timeInMillis,
                        confidence = 0.95f
                    )
                )
            }
        }

        Regex("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b").findAll(normalizedQuery).forEach { match ->
            val (year, month, day) = match.destructured
            try {
                val calendar = Calendar.getInstance()
                calendar.set(year.toInt(), month.toInt() - 1, day.toInt())
                entities.add(
                    Entity(
                        text = match.value,
                        type = EntityType.DATE_ABSOLUTE,
                        value = calendar.timeInMillis,
                        confidence = 1.0f
                    )
                )
            } catch (_: Exception) {
                // Ignore invalid date strings.
            }
        }

        PROGRAMMING_LANGUAGES.forEach { lang ->
            if (Regex("\\b${Regex.escape(lang)}\\b").containsMatchIn(normalizedQuery)) {
                entities.add(Entity(lang, EntityType.PROGRAMMING_LANGUAGE, lang, 0.9f))
            }
        }

        TECHNICAL_DOMAINS.forEach { (term, fullName) ->
            if (Regex("\\b${Regex.escape(term)}\\b").containsMatchIn(normalizedQuery)) {
                entities.add(Entity(term, EntityType.TECHNICAL_DOMAIN, fullName, 0.85f))
            }
        }

        if (Regex("\\bmy\\b").containsMatchIn(normalizedQuery)) {
            entities.add(Entity("my", EntityType.PERSON_POSSESSIVE, "user_owned", 0.7f))
        }

        Regex("\\b(\\d+)\\b").findAll(normalizedQuery).forEach { match ->
            entities.add(
                Entity(
                    text = match.value,
                    type = EntityType.NUMERIC,
                    value = match.value.toIntOrNull() ?: 0,
                    confidence = 1.0f
                )
            )
        }

        return entities
    }
}


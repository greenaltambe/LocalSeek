package com.augt.localseek.search.query

import android.content.Context
import android.util.Log

class SmartTokenizer(context: Context) {

    data class Token(
        val text: String,
        val tokenId: Int,
        val isStopword: Boolean,
        val isPunctuation: Boolean,
        val importance: Float
    )

    private val vocab: Map<String, Int>
    private val stopwords: Set<String>

    init {
        vocab = loadVocab(context)
        stopwords = setOf(
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
            "of", "with", "is", "are", "was", "were", "been", "be", "have", "has",
            "had", "do", "does", "did", "will", "would", "should", "could", "may",
            "might", "must", "can", "this", "that", "these", "those", "it", "its",
            "he", "she", "him", "her", "his", "they", "them", "their"
        )
    }

    private fun loadVocab(context: Context): Map<String, Int> {
        val vocabMap = mutableMapOf<String, Int>()
        return try {
            context.assets.open("vocab.txt").bufferedReader().useLines { lines ->
                lines.forEachIndexed { index, line ->
                    vocabMap[line.trim()] = index
                }
            }
            vocabMap
        } catch (e: Exception) {
            Log.e("SmartTokenizer", "Failed to load vocab", e)
            vocabMap
        }
    }

    fun tokenize(text: String): List<Token> {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

        return words.mapIndexed { index, word ->
            val cleanWord = word.trim()
            val isStop = cleanWord in stopwords
            val isPunct = cleanWord.matches(Regex("[^a-z0-9]+"))

            val importance = when {
                isPunct -> 0.0f
                isStop -> 0.1f
                index == 0 -> 0.9f
                cleanWord.length > 8 -> 0.8f
                cleanWord.contains("-") -> 0.7f
                else -> 0.6f
            }

            val tokenId = vocab[cleanWord] ?: vocab["[UNK]"] ?: 100

            Token(
                text = cleanWord,
                tokenId = tokenId,
                isStopword = isStop,
                isPunctuation = isPunct,
                importance = importance
            )
        }.filterNot { it.isPunctuation }
    }

    fun extractKeyTerms(tokens: List<Token>, minImportance: Float = 0.5f): List<String> {
        return tokens.filter { it.importance >= minImportance }.map { it.text }
    }
}


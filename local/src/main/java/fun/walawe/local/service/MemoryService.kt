package `fun`.walawe.local.service

import timber.log.Timber
import `fun`.walawe.local.dao.MessageDao
import `fun`.walawe.local.data.MessageEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Deprecated("Replaced by RAG pipeline (EmbeddingEngine + VectorStore + ChunkDao)")
@Singleton
class MemoryService @Inject constructor(
    private val messageDao: MessageDao,
) {
    data class KeywordMatchResult(
        val text: String,
        val keywordScore: Int,
        val cosineSimilarity: Float,
    )

    companion object {
        const val MIN_KEYWORD_MATCH = 2
    }

    suspend fun findBestMatch(query: String): KeywordMatchResult? {
        val queryKeywords = tokenize(query)
        val records = messageDao.getAllUserMessages()
        if (records.isEmpty()) return null

        var bestScore = 0
        var bestRecord: MessageEntity? = null

        for (record in records) {
            val recordKeywords = tokenize(record.text)
            val keywordScore = queryKeywords.keys.intersect(recordKeywords.keys).size

            if (keywordScore >= MIN_KEYWORD_MATCH && keywordScore > bestScore) {
                bestScore = keywordScore
                bestRecord = record
            }
        }

        return bestRecord?.let {
            val cosSim = cosineSimilarity(tokenize(query), tokenize(it.text))
            KeywordMatchResult(text = it.text, keywordScore = bestScore, cosineSimilarity = cosSim)
        }
    }

    suspend fun augmentQuery(query: String): String {
        val result = findBestMatch(query)
        return if (result != null && result.keywordScore >= MIN_KEYWORD_MATCH) {
            Timber.d("NaiveRAG: match score=%d cosSim=%.4f".format(result.keywordScore, result.cosineSimilarity))
            "$query: ${result.text}"
        } else {
            query
        }
    }

    private fun tokenize(text: String): Map<String, Int> {
        return text.lowercase()
            .replace(Regex("[^a-zA-Z0-9\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
    }

    private fun cosineSimilarity(tf1: Map<String, Int>, tf2: Map<String, Int>): Float {
        val allWords = tf1.keys + tf2.keys
        var dotProduct = 0f
        var magnitudeA = 0f
        var magnitudeB = 0f

        for (word in allWords) {
            val f1 = (tf1[word] ?: 0).toFloat()
            val f2 = (tf2[word] ?: 0).toFloat()
            dotProduct += f1 * f2
            magnitudeA += f1 * f1
            magnitudeB += f2 * f2
        }

        val denom = sqrt(magnitudeA) * sqrt(magnitudeB)
        return if (denom > 0f) dotProduct / denom else 0f
    }
}

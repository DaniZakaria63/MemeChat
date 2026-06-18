package `fun`.walawe.local.service

import `fun`.walawe.local.dao.ChunkDao
import `fun`.walawe.local.data.ChunkEntity
import `fun`.walawe.vector.VectorStore
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChunkHandlerService @Inject constructor(
    private val preprocess: PreprocessTextService,
    private val chunkDao: ChunkDao,
    private val vectorStore: VectorStore,
) {
    private val faissIdCounter = AtomicLong(System.currentTimeMillis())

    fun initVectorStore(checkpointPath: String? = null) {
        vectorStore.init(checkpointPath)
        Timber.d("VectorStore initialized (dim=%d, size=%d)", vectorStore.dimension(), vectorStore.size())
    }

    fun releaseVectorStore() {
        vectorStore.release()
    }

    suspend fun preprocessAndChunk(messageId: String, text: String): List<ChunkEntity> {
        val result = preprocess.preprocess(text)
        return buildChunks(result.sentences, messageId, faissIdCounter)
    }

    suspend fun storeChunk(chunk: ChunkEntity, vector: FloatArray) {
        vectorStore.add(chunk.faissId, vector)
        chunkDao.insert(chunk)
    }

    /**
     * @param queryVector L2-normalized embedding vector from EmbeddingEngine.
     *   Must match the vector dimension of stored chunks (checked at index creation).
     * @param topK Maximum candidates returned from FAISS before filtering.
     *   Higher values increase recall but may return low-relevance results.
     * @param minScore Minimum cosine-similarity threshold (0 .. 1).
     *   Results below this are discarded. Tune based on your embedding model's output range.
     */
    suspend fun searchChunks(
        queryVector: FloatArray,
        topK: Int = 5,
        minScore: Float = 0.5f,
    ): List<ChunkEntity> {
        val matches = vectorStore.search(queryVector, topK)
        val relevant = matches.filter { it.score >= minScore }
        if (relevant.isEmpty()) return emptyList()
        return chunkDao.getChunksByFaissIds(relevant.map { it.id })
    }

    suspend fun deleteConversationChunks(conversationId: String) {
        val chunks = chunkDao.getChunksByConversation(conversationId)
        if (chunks.isEmpty()) return
        chunks.forEach { vectorStore.remove(it.faissId) }
        Timber.d("Removed %d vectors for conversation %s", chunks.size, conversationId)
    }
}

internal fun buildChunks(
    sentences: List<String>,
    messageId: String,
    faissIdCounter: AtomicLong = AtomicLong(System.currentTimeMillis()),
    maxChars: Int = 1000,
): List<ChunkEntity> {
    return sentences
        .filter { it.isNotBlank() }
        .mapIndexed { index, sentence ->
            val text = if (sentence.length > maxChars) sentence.take(maxChars) else sentence
            ChunkEntity(
                id = UUID.randomUUID().toString(),
                messageId = messageId,
                text = text.trim(),
                faissId = faissIdCounter.getAndIncrement(),
                sequence = index,
            )
        }
}

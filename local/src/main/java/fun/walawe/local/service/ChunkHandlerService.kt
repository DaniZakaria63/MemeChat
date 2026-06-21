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
    private val chunkDao: ChunkDao
) {
    private val faissIdCounter = AtomicLong(System.currentTimeMillis())

    fun initVectorStore(checkpointPath: String? = null) {
        VectorStore.init(checkpointPath)
        Timber.d("VectorStore initialized (dim=%d, size=%d)", VectorStore.dimension(), VectorStore.size())
    }

    fun releaseVectorStore() {
        VectorStore.release()
    }

    suspend fun preprocessAndChunk(messageId: String, conversationId: String, role: String, text: String): List<ChunkEntity> {
        val result = preprocess.preprocess(text)
        return buildChunks(result.sentences, messageId, conversationId, role, faissIdCounter)
    }

    suspend fun storeChunk(chunk: ChunkEntity, vector: FloatArray) {
        VectorStore.add(chunk.faissId, vector)
        chunkDao.insert(chunk)
    }

    fun saveFileChunk(){
        VectorStore.save()
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
        conversationId: String,
        topK: Int = 5,
        minScore: Float = 0.5f,
    ): List<ChunkEntity> {
        val matches = VectorStore.search(queryVector, topK)
        val relevant = matches.filter { it.score >= minScore }
        if (relevant.isEmpty()) return emptyList()
        return chunkDao.getChunksByFaissIds(relevant.map { it.id }, conversationId)
    }

    suspend fun deleteConversationChunks(conversationId: String) {
        val chunks = chunkDao.getChunksByConversation(conversationId)
        if (chunks.isEmpty()) return
        chunks.forEach { VectorStore.remove(it.faissId) }
        Timber.d("Removed %d vectors for conversation %s", chunks.size, conversationId)
    }
}

internal fun buildChunks(
    sentences: List<String>,
    messageId: String,
    conversationId: String,
    role: String,
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
                conversationId = conversationId,
                role = role,
                text = text.trim(),
                faissId = faissIdCounter.getAndIncrement(),
                sequence = index,
            )
        }
}

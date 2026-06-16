package `fun`.walawe.memelm.inference

interface EmbeddingEngine {
    suspend fun init(modelPath: String, contextSize: Int = 512): Boolean
    suspend fun embed(text: String): FloatArray
    suspend fun embedBatch(texts: List<String>): List<FloatArray>
    fun dimension(): Int
    fun release()
}

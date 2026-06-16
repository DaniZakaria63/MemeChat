package `fun`.walawe.memelm.inference

import android.content.Context
import android.util.Log
import dalvik.annotation.optimization.FastNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EmbeddingEngineImpl private constructor() : EmbeddingEngine {
    companion object {
        private val TAG = EmbeddingEngineImpl::class.java.simpleName

        @Volatile
        private var instance: EmbeddingEngine? = null

        fun getInstance(context: Context): EmbeddingEngine {
            return instance ?: synchronized(this) {
                instance ?: try {
                    Log.i(TAG, "Instantiating EmbeddingEngineImpl")
                    System.loadLibrary("memelm")
                    EmbeddingEngineImpl().also { instance = it }
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "Failed to load native library", e)
                    throw e
                }
            }
        }
    }

    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)

    /**
     * JNI methods — implemented in memelm.cpp
     */
    external fun nativeInit(contextSize: Int): Boolean
    external fun nativeEmbed(text: String): FloatArray?
    external fun nativeEmbedBatch(texts: Array<String>): Array<FloatArray>?
    external fun nativeDimension(): Int
    external fun nativeRelease()

    override suspend fun init(contextSize: Int): Boolean =
        withContext(llamaDispatcher) {
            Log.i(TAG, "Initializing embedding engine with contextSize=$contextSize")
            try {
                nativeInit(contextSize).also { ok ->
                    if (ok) Log.i(TAG, "Embedding engine initialized")
                    else Log.e(TAG, "Embedding engine init returned false")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Embedding engine init failed", e)
                throw e
            }
        }

    override suspend fun embed(text: String): FloatArray =
        withContext(llamaDispatcher) {
            require(text.isNotBlank()) { "Embedding text cannot be blank" }
            Log.i(TAG, "Embedding text (${text.length} chars)")
            try {
                nativeEmbed(text) ?: throw IllegalStateException("Embed returned null")
            } catch (e: Exception) {
                Log.e(TAG, "Embedding failed", e)
                throw e
            }
        }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> =
        withContext(llamaDispatcher) {
            require(texts.isNotEmpty()) { "Text list cannot be empty" }
            Log.i(TAG, "Embedding batch of ${texts.size} texts")
            try {
                val result = nativeEmbedBatch(texts.toTypedArray())
                    ?: throw IllegalStateException("Batch embed returned null")
                result.toList()
            } catch (e: Exception) {
                Log.e(TAG, "Batch embedding failed", e)
                throw e
            }
        }

    override fun dimension(): Int {
        return try {
            nativeDimension()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get dimension", e)
            0
        }
    }

    override fun release() {
        try {
            nativeRelease()
            instance = null
            Log.i(TAG, "Embedding engine released")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release embedding engine", e)
        }
    }
}

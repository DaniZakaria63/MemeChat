package `fun`.walawe.memelm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

class MemeLM {
    private var nativePtr: Long = 0

    companion object {
        const val DEFAULT_IMAGE_PROMPT = "describe this meme"
        init {
            System.loadLibrary("memelm")
        }
    }

    data class InferenceParams(
        val minP: Float = 0.1f,
        val temperature: Float = 0.8f,
        val storeChats: Boolean = true,
        val contextSize: Long? = null,
        val chatTemplate: String? = null,
        val numThreads: Int = 4,
        val useMmap: Boolean = true,
        val useMlock: Boolean = false,
    )

    /** Load the GGUF text model for text-only or vision workflows. */
    suspend fun load(modelPath: String, params: InferenceParams = InferenceParams()) {
        withContext(Dispatchers.IO) {
            nativePtr = loadModel(
                modelPath, params.minP, params.temperature, params.storeChats,
                params.contextSize ?: 2048, params.chatTemplate ?: "",
                params.numThreads, params.useMmap, params.useMlock
            )
        }
    }

    /** Initialize the vision adapter (mmproj) for Paligemma-style VLM usage. */
    suspend fun initVision(mmprojPath: String, mediaMarker: String = "", useGpu: Boolean = true, warmup: Boolean = true) {
        withContext(Dispatchers.IO) {
            initVision(nativePtr, mmprojPath, mediaMarker, InferenceParams().numThreads, useGpu, warmup)
        }
    }

    /** Text-to-text streaming response. */
    fun getResponseAsFlow(query: String): Flow<String> = flow {
        startCompletion(nativePtr, query)
        var piece = completionLoop(nativePtr)
        while (piece != "[EOG]") {
            emit(piece)
            piece = completionLoop(nativePtr)
        }
    }

    /**
     * Text+image to text streaming response.
     * Requires load() and initVision() to be called first.
     */
    fun getResponseAsFlowWithImage(prompt: String, imageBytes: ByteArray): Flow<String> = flow {
        startCompletionWithImage(nativePtr, prompt, imageBytes)
        var piece = completionLoop(nativePtr)
        while (piece != "[EOG]") {
            emit(piece)
            piece = completionLoop(nativePtr)
        }
    }

    /** Image-to-text streaming response using a default or custom prompt. */
    fun getResponseAsFlowForImage(imageBytes: ByteArray, prompt: String = DEFAULT_IMAGE_PROMPT): Flow<String> =
        getResponseAsFlowWithImage(prompt, imageBytes)

    /** Release native resources. Always call when finished. */
    fun close() {
        if (nativePtr != 0L) {
            close(nativePtr)
            nativePtr = 0
        }
    }

    // Native methods
    private external fun loadModel(
        modelPath: String,
        minP: Float,
        temperature: Float,
        storeChats: Boolean,
        contextSize: Long,
        chatTemplate: String,
        numThreads: Int,
        useMmap: Boolean,
        useMlock: Boolean,
    ): Long
    private external fun addChatMessage(modelPtr: Long, message: String, role: String)
    private external fun startCompletion(modelPtr: Long, prompt: String)
    private external fun completionLoop(modelPtr: Long): String
    private external fun close(modelPtr: Long)
    private external fun initVision(
        modelPtr: Long,
        mmprojPath: String,
        mediaMarker: String,
        numThreads: Int,
        useGpu: Boolean,
        warmup: Boolean,
    )
    private external fun startCompletionWithImage(modelPtr: Long, prompt: String, imageBytes: ByteArray)
}
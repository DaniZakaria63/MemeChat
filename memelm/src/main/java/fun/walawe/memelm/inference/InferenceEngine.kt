package `fun`.walawe.memelm.inference

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream


interface InferenceEngine {
    val state: StateFlow<State>

    /**
     * Load a model from the given path.
     *
     * @throws UnsupportedArchitectureException if model architecture not supported
     */
    suspend fun loadModel(pathToModel: String, params: InferenceParams = InferenceParams())

    /**
     * Sends a system prompt to the loaded model
     */
    suspend fun setSystemPrompt(systemPrompt: String)

    /**
     * Sends a user prompt to the loaded model and returns a Flow of generated tokens.
     */
    fun sendUserPrompt(message: String, predictLength: Int = DEFAULT_PREDICT_LENGTH): Flow<String>

    /**
     * Send a user prompt with embed image bitmap and returns a Flow of tokens
     */
    suspend fun sendUserPromptWithImage(message: String, bitmap: Bitmap, predictLength: Int = DEFAULT_PREDICT_LENGTH): Flow<String>

    /**
     * Initializing the vision ready backend
     */
    suspend fun initVision(mmprojPath: String, mediaMarker: String = "", useGpu: Boolean = true, warmup: Boolean = true)

    /**
     * Runs a benchmark with the specified parameters.
     */
    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String

    /**
     * Unloads the currently loaded model.
     */
    fun cleanUp()

    /**
     * Cleans up resources when the engine is no longer needed.
     */
    fun destroy()

    /**
     * States of the inference engine
     */
    sealed class State {
        object Uninitialized : State()
        object Initializing : State()
        object Initialized : State()

        object LoadingModel : State()
        object UnloadingModel : State()
        object ModelReady : State()

        object Benchmarking : State()
        object ProcessingSystemPrompt : State()
        object ProcessingUserPrompt : State()

        object Generating : State()

        data class Error(val exception: Exception) : State()
    }

    companion object {
        const val DEFAULT_PREDICT_LENGTH = 1024
        const val DEFAULT_IMAGE_PROMPT = "describe this meme"
    }
}

val InferenceEngine.State.isUninterruptible
    get() = this is InferenceEngine.State.Initializing ||
            this is InferenceEngine.State.LoadingModel ||
            this is InferenceEngine.State.UnloadingModel ||
            this is InferenceEngine.State.Benchmarking ||
            this is InferenceEngine.State.ProcessingSystemPrompt ||
            this is InferenceEngine.State.ProcessingUserPrompt

val InferenceEngine.State.isModelLoaded: Boolean
    get() = this is InferenceEngine.State.ModelReady ||
            this is InferenceEngine.State.Benchmarking ||
            this is InferenceEngine.State.ProcessingSystemPrompt ||
            this is InferenceEngine.State.ProcessingUserPrompt ||
            this is InferenceEngine.State.Generating

data class InferenceParams(
    val minP: Float = 0.1f,
    val temperature: Float = 0.8f,
    val storeChats: Boolean = true,
    val contextSize: Long = 2048L,
    val chatTemplate: String? = null,
    val numThreads: Int = 4,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
)

class UnsupportedArchitectureException : Exception()
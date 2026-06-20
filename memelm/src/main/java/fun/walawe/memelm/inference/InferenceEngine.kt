package `fun`.walawe.memelm.inference

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow


interface InferenceEngine {
    val state: StateFlow<State>

    suspend fun loadModel(pathToModel: String, pathToMMProj: String, params: InferenceParams)

    suspend fun setSystemPrompt(systemPrompt: String)

    fun sendConversation(chatML: String, forReasoning: Boolean = false): Flow<Pair<STATE, String>>

    fun sendConversationWithImage(
        bitmap: Bitmap,
        message: String,
        forReasoning: Boolean,
    ): Flow<Pair<STATE, String>>

    suspend fun getBackendInfo(): String
    fun isGenerating(): Boolean
    fun cancelGeneration()
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
    val minP: Float?,
    val temperature: Float?,
    val contextSize: Long?,
    val chatTemplate: String?,
    val numThreads: Int?,
    val useMmap: Boolean?,
    val useMlock: Boolean?,
    val useVulkanBackend: Boolean?,
){
    companion object{
        fun getDefault(): InferenceParams = InferenceParams(
            minP = 0.1f,
            temperature = 0.8f,
            contextSize = 2048L,
            chatTemplate = null,
            numThreads = 2,
            useMmap = true,
            useMlock = false,
            useVulkanBackend = false, // Because my poor device prefer CPU rather than GPU, Sad :(
        )
    }
}

class UnsupportedArchitectureException : Exception()

interface StreamCallback {
    fun onToken(token: String)
    fun onComplete()
}

sealed class STATE{
    object THINKING: STATE()
    object ANSWER: STATE()
}
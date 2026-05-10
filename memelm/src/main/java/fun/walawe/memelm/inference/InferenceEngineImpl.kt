package `fun`.walawe.memelm.inference

import android.graphics.Bitmap
import android.util.Log
import `fun`.walawe.memelm.MemeLM
import `fun`.walawe.memelm.gguf.GGUFReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class InferenceEngineImpl private constructor() : InferenceEngine {
    companion object {
        private const val TAG = "InferenceEngine"
        private const val EXPECTED_MODEL_BASENAME = "Qwen.Qwen3-VL-Embedding-2B.Q2_K"
        private const val DEFAULT_PREDICT_LENGTH = 1024

        @Volatile
        private var instance: InferenceEngineImpl? = null

        fun getInstance(): InferenceEngineImpl =
            instance ?: synchronized(this) {
                instance ?: InferenceEngineImpl().also { instance = it }
            }
    }

    private val _state = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
    override val state = _state.asStateFlow()

    private var readyForSystemPrompt = false
    private var cancelGeneration = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val llamaScope = CoroutineScope(llamaDispatcher + SupervisorJob())

    private var memeLM = MemeLM()
    private val ggufReader = GGUFReader()

    init {
        llamaScope.runCatching {
            _state.value = InferenceEngine.State.Initializing
            Log.i(TAG, "Initializing inference engine")
            _state.value = InferenceEngine.State.Initialized
        }.onFailure {
            _state.value = InferenceEngine.State.Error(Exception(it))
            throw it
        }
    }

    override suspend fun loadModel(pathToModel: String) {
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.Initialized) {
                "Engine not initialized"
            }
            _state.value = InferenceEngine.State.LoadingModel
            Log.i(TAG, "Loading model: $pathToModel")

            try {
                ggufReader.load(pathToModel)
                val isExpected = ggufReader.isExpectedQwenModel(EXPECTED_MODEL_BASENAME)
                if (!isExpected) {
                    throw IllegalArgumentException("Unexpected GGUF model. Expected $EXPECTED_MODEL_BASENAME")
                }

                val contextSize = ggufReader.getContextSize()
                val chatTemplate = ggufReader.getChatTemplate()

                memeLM.load(
                    pathToModel,
                    MemeLM.InferenceParams(
                        contextSize = contextSize,
                        chatTemplate = chatTemplate
                    )
                )
                readyForSystemPrompt = true
                _state.value = InferenceEngine.State.ModelReady
                Log.i(TAG, "Model loaded and ready")
            } catch (e: Exception) {
                _state.value = InferenceEngine.State.Error(e)
                Log.e(TAG, "Model load failed", e)
                throw e
            }
        }
    }

    override suspend fun setSystemPrompt(systemPrompt: String) {
        withContext(llamaDispatcher) {
            require(systemPrompt.isNotBlank()) { "System prompt cannot be blank" }
            check(readyForSystemPrompt) { "System prompt must be set immediately after loading the model" }

            _state.value = InferenceEngine.State.ProcessingSystemPrompt
            Log.i(TAG, "Processing system prompt")
            try {
                memeLM.addChatMessage(systemPrompt, "system")
                readyForSystemPrompt = false
                _state.value = InferenceEngine.State.ModelReady
            } catch (e: Exception) {
                _state.value = InferenceEngine.State.Error(e)
                Log.e(TAG, "System prompt failed", e)
                throw e
            }
        }
    }

    override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> = flow {
        check(message.isNotBlank()) { "User prompt cannot be blank" }
        check(state.value.isModelLoaded) { "Model not ready" }

        cancelGeneration = false
        _state.value = InferenceEngine.State.ProcessingUserPrompt
        Log.i(TAG, "Processing user prompt")

        try {
            _state.value = InferenceEngine.State.Generating
            memeLM.getResponseAsFlow(message).collect { piece ->
                if (cancelGeneration) return@collect
                emit(piece)
            }
            _state.value = InferenceEngine.State.ModelReady
        } catch (e: CancellationException) {
            _state.value = InferenceEngine.State.ModelReady
            throw e
        } catch (e: Exception) {
            _state.value = InferenceEngine.State.Error(e)
            Log.e(TAG, "Generation failed", e)
            throw e
        }
    }.flowOn(llamaDispatcher)

    fun sendUserPromptWithImage(message: String, bitmap: Bitmap, predictLength: Int = DEFAULT_PREDICT_LENGTH): Flow<String> = flow {
        check(message.isNotBlank()) { "User prompt cannot be blank" }
        check(state.value.isModelLoaded) { "Model not ready" }

        cancelGeneration = false
        _state.value = InferenceEngine.State.ProcessingUserPrompt
        Log.i(TAG, "Processing user prompt with image")

        try {
            _state.value = InferenceEngine.State.Generating
            memeLM.getResponseAsFlowWithImage(message, bitmap).collect { piece ->
                if (cancelGeneration) return@collect
                emit(piece)
            }
            _state.value = InferenceEngine.State.ModelReady
        } catch (e: CancellationException) {
            _state.value = InferenceEngine.State.ModelReady
            throw e
        } catch (e: Exception) {
            _state.value = InferenceEngine.State.Error(e)
            Log.e(TAG, "Image generation failed", e)
            throw e
        }
    }.flowOn(llamaDispatcher)

    suspend fun initVision(mmprojPath: String, mediaMarker: String = "", useGpu: Boolean = true, warmup: Boolean = true) {
        withContext(llamaDispatcher) {
            check(state.value.isModelLoaded) { "Model not ready" }
            Log.i(TAG, "Initializing vision adapter: $mmprojPath")
            try {
                memeLM.initVision(mmprojPath, mediaMarker, useGpu, warmup)
            } catch (e: Exception) {
                _state.value = InferenceEngine.State.Error(e)
                Log.e(TAG, "Vision init failed", e)
                throw e
            }
        }
    }

    override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String {
        return withContext(llamaDispatcher) {
            _state.value = InferenceEngine.State.Benchmarking
            Log.i(TAG, "Benchmark requested, returning stub")
            _state.value = InferenceEngine.State.ModelReady
            "Benchmark not implemented in memelm JNI"
        }
    }

    override fun cleanUp() {
        cancelGeneration = true
        memeLM.close()
        memeLM = MemeLM()
        readyForSystemPrompt = false
        _state.value = InferenceEngine.State.Initialized
        Log.i(TAG, "Model unloaded")
    }

    override fun destroy() {
        cancelGeneration = true
        memeLM.close()
        llamaScope.cancel()
        _state.value = InferenceEngine.State.Uninitialized
        Log.i(TAG, "Inference engine destroyed")
    }
}
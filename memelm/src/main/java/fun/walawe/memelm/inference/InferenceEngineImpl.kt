package `fun`.walawe.memelm.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import dalvik.annotation.optimization.FastNative
import `fun`.walawe.memelm.gguf.GGUFReader
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InferenceEngineImpl private constructor(
    private val nativeLibDir: String
) : InferenceEngine {
    companion object {
        private val TAG = InferenceEngineImpl::class.java.simpleName
        private const val EXPECTED_MODEL_BASENAME = "Qwen.Qwen3-VL-Embedding-2B.Q2_K"
        private const val DEFAULT_PREDICT_LENGTH = 1024
        private const val DEFAULT_NUM_THREADS = 4

        @Volatile
        private var instance: InferenceEngine? = null

        /**
         * Create or obtain [InferenceEngineImpl]'s single instance.
         *
         * @param Context for obtaining native library directory
         * @throws IllegalArgumentException if native library path is invalid
         * @throws UnsatisfiedLinkError if library failed to load
         */
        fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                require(nativeLibDir.isNotBlank()) { "Expected a valid native library path!" }

                try {
                    Log.i(TAG, "Instantiating InferenceEngineImpl,,,")
                    InferenceEngineImpl(nativeLibDir).also { instance = it }
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "Failed to load native library from $nativeLibDir", e)
                    throw e
                }
            }
    }


    /**
     * JNI methods
     * @see memelm.cpp
     */

    @FastNative
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

    @FastNative
    private external fun addChatMessage(modelPtr: Long, message: String, role: String)

    @FastNative
    private external fun startCompletion(modelPtr: Long, prompt: String)

    @FastNative
    private external fun completionLoop(modelPtr: Long): String

    @FastNative
    private external fun close(modelPtr: Long)

    @FastNative
    private external fun initVision(
        modelPtr: Long,
        mmprojPath: String,
        mediaMarker: String,
        numThreads: Int,
        useGpu: Boolean,
        warmup: Boolean,
    )

    @FastNative
    private external fun startCompletionWithImage(modelPtr: Long, prompt: String, imageBytes: ByteArray)


    private val _state = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
    override val state = _state.asStateFlow()

    private var readyForSystemPrompt = false
    private var cancelGeneration = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val llamaScope = CoroutineScope(llamaDispatcher + SupervisorJob())
    private val ggufReader = GGUFReader()
    private var nativePtr: Long = 0L

    init {
        llamaScope.launch {
            runCatching {
                check(_state.value is InferenceEngine.State.Uninitialized) {
                    "Cannot load native library in ${_state.value.javaClass.simpleName}!"
                }
                _state.value = InferenceEngine.State.Initializing
                Log.i(TAG, "Initializing inference engine")
                System.loadLibrary("memelm")
                _state.value = InferenceEngine.State.Initialized
            }.onFailure {
                _state.value = InferenceEngine.State.Error(Exception(it))
                throw it
            }
        }
    }

    override suspend fun loadModel(pathToModel: String, params: InferenceParams) {
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

                nativePtr = loadModel(
                    modelPath = pathToModel,
                    minP = params.minP,
                    temperature = params.temperature,
                    storeChats = params.storeChats,
                    contextSize = contextSize ?: params.contextSize,
                    chatTemplate = chatTemplate ?: params.chatTemplate.orEmpty(),
                    params.numThreads, params.useMmap, params.useMlock
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
                addChatMessage(nativePtr, systemPrompt, "system")
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
            startCompletion(nativePtr, message)
            _state.value = InferenceEngine.State.Generating
            var piece = completionLoop(nativePtr)
            while (piece != "[EOG]") {
                if (piece.isNotEmpty()) {
                    emit(piece)
                }
                piece = completionLoop(nativePtr)
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

    override suspend fun sendUserPromptWithImage(message: String, bitmap: Bitmap, predictLength: Int): Flow<String> = flow {
        check(message.isNotBlank()) { "User prompt cannot be blank" }
        check(state.value.isModelLoaded) { "Model not ready" }

        cancelGeneration = false
        _state.value = InferenceEngine.State.ProcessingUserPrompt
        Log.i(TAG, "Processing user prompt with image")
        val imageBytes = bitmapToPngBytes(bitmap)

        try {
            _state.value = InferenceEngine.State.Generating
            startCompletionWithImage(nativePtr, message, imageBytes)
            var piece = completionLoop(nativePtr)
            while (piece != "[EOG]") {
                if (piece.isNotEmpty()) {
                    emit(piece)
                }
                piece = completionLoop(nativePtr)
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

    override suspend fun initVision(mmprojPath: String, mediaMarker: String, useGpu: Boolean, warmup: Boolean) {
        withContext(llamaDispatcher) {
            check(state.value.isModelLoaded) { "Model not ready" }
            Log.i(TAG, "Initializing vision adapter: $mmprojPath")
            val numThreads = DEFAULT_NUM_THREADS
            try {
                initVision(nativePtr, mmprojPath, mediaMarker, numThreads, useGpu, warmup)
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
        readyForSystemPrompt = false
        _state.value = InferenceEngine.State.Initialized
        Log.i(TAG, "Model unloaded")
    }

    override fun destroy() {
        cancelGeneration = true
        if (nativePtr != 0L) {
            close(nativePtr)
            nativePtr = 0
        }
        llamaScope.cancel()
        _state.value = InferenceEngine.State.Uninitialized
        Log.i(TAG, "Inference engine destroyed")
    }

    private fun bitmapToPngBytes(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

}
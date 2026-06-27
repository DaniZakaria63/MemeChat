package `fun`.walawe.memelm.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import dalvik.annotation.optimization.FastNative
import `fun`.walawe.constant.orFalse
import `fun`.walawe.constant.orZero
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class InferenceEngineImpl private constructor(
    private val nativeLibDir: String
) : InferenceEngine {
    companion object {
        private val TAG = InferenceEngineImpl::class.java.simpleName

        @Volatile
        private var instance: InferenceEngine? = null

        fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                require(nativeLibDir.isNotBlank()) { "Expected a valid native library path!" }

                try {
                    Log.i(TAG, "Instantiating InferenceEngineImpl,,,")
                    Log.i(TAG, "nativeLibDir: $nativeLibDir")
                    Log.i(TAG, "nativeLibDir exists: ${File(nativeLibDir).exists()}")
                    File(nativeLibDir).listFiles()?.forEach {
                        Log.i(TAG, "native lib: ${it.name}")
                    }
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

    external fun nativeInit(
        modelPath: String,
        mmprojPath: String,
        backendPath: String,
        contextSize: Int,
        useVulkan: Boolean
    ): Boolean

    external fun nativeProbeBackends(): String

    @FastNative
    external fun nativeSetSystemPrompt(prompt: String)

    @FastNative
    external fun nativeProcessImageAndText(
        bitmap: Bitmap,
        prompt: String,
        forReasoning: Boolean,
        tokenCallback: StreamCallback,
    )


    @FastNative
    external fun nativeProcessConversation(
        chatML: String,
        tokenCallback: StreamCallback,
    )

    @FastNative
    external fun nativeGetBackendInfo(): String

    @FastNative
    external fun nativeRelease()

    @FastNative
    external fun nativeCancelGeneration()

    @FastNative
    external fun nativeIsGenerating(): Boolean

    private val _state =
        MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
    override val state = _state.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val llamaScope = CoroutineScope(llamaDispatcher + SupervisorJob())

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

    override suspend fun loadModel(
        pathToModel: String,
        pathToMMProj: String,
        params: InferenceParams
    ) {
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.Initialized) {
                "Engine not initialized"
            }
            require(nativeLibDir.isNotBlank()) { "Native library directory is not set" }
            _state.value = InferenceEngine.State.LoadingModel
            Log.i(TAG, "Loading model: $pathToModel")

            try {
                File(pathToModel).let {
                    require(it.exists()) { "File not found" }
                    require(it.isFile) { "Not a valid file" }
                    require(it.canRead()) { "Cannot read file" }
                }
                File(pathToMMProj).let {
                    require(it.exists()) { "MMProj file not found" }
                    require(it.isFile) { "MMProj is not a valid file" }
                    require(it.canRead()) { "Cannot read MMProj file" }
                }

                nativeInit(
                    modelPath = pathToModel,
                    mmprojPath = pathToMMProj,
                    backendPath = nativeLibDir,
                    contextSize = params.contextSize.orZero().toInt(),
                    useVulkan = params.useVulkanBackend.orFalse()
                ).let { result ->
                    _state.value = if (result) {
                        InferenceEngine.State.ModelReady
                    } else throw Exception("Model load failed from $TAG. model=$pathToModel mmproj=$pathToMMProj")

                    Log.i(TAG, "Model loaded and ready")
                }
            } catch (e: Exception) {
                _state.value = InferenceEngine.State.Error(e)
                Log.e(TAG, e.message.toString(), e)
                throw e
            }
        }
    }

    override suspend fun setSystemPrompt(systemPrompt: String) {
        withContext(llamaDispatcher) {
            require(systemPrompt.isNotBlank()) { "System prompt cannot be blank" }

            _state.value = InferenceEngine.State.ProcessingSystemPrompt
            Log.i(TAG, "Processing system prompt")
            try {
                nativeSetSystemPrompt(systemPrompt)
                _state.value = InferenceEngine.State.ModelReady
            } catch (e: Exception) {
                _state.value = InferenceEngine.State.Error(e)
                Log.e(TAG, "System prompt failed", e)
                throw e
            }
        }
    }

    override suspend fun getBackendInfo(): String =
        withContext(llamaDispatcher) {
            check(state.value.isModelLoaded) { "Model not ready" }
            Log.i(TAG, "getBackendInfo from $TAG")
            try {
                nativeGetBackendInfo()
            } catch (e: Exception) {
                _state.value = InferenceEngine.State.Error(e)
                Log.e(TAG, " getBackendInfo failed", e)
                throw e
            }
        }

    override fun sendConversation(
        prompt: String,
        forReasoning: Boolean,
    ): Flow<Pair<STATE, String>> = callbackFlow {
        require(prompt.isNotEmpty()) { "User prompt cannot be empty" }
        check(state.value.isModelLoaded) { "Model not ready" }

        _state.value = InferenceEngine.State.Generating

        try {
            val callback = object : StreamCallback {
                var thinkingSeen = forReasoning
                override fun onToken(token: String) {
                    when {
                        !thinkingSeen && token.contains("<think>") -> {
                            thinkingSeen = true
                            val after = token.substringAfter("<think>")
                            if (after.isNotEmpty()) trySend(Pair(STATE.THINKING, after))
                        }
                        !thinkingSeen -> trySend(Pair(STATE.ANSWER, token))
                        !token.contains("</think>") -> trySend(Pair(STATE.THINKING, token))
                        else -> {
                            thinkingSeen = false
                            val after = token.substringAfter("</think>")
                            if (after.isNotEmpty()) trySend(Pair(STATE.ANSWER, after))
                        }
                    }
                }
                override fun onComplete() { close() }
            }
            nativeProcessConversation(prompt, callback)
        } catch (e: CancellationException) {
            _state.value = InferenceEngine.State.ModelReady
            close()
            throw e
        } catch (e: Exception) {
            _state.value = InferenceEngine.State.Error(e)
            close(e)
        } finally {
            _state.value = InferenceEngine.State.ModelReady
            close()
            awaitClose()
        }
    }.flowOn(llamaDispatcher)

    override fun sendConversationWithImage(
        bitmap: Bitmap,
        message: String,
        forReasoning: Boolean,
    ): Flow<Pair<STATE, String>> = callbackFlow {
        check(state.value.isModelLoaded) { "Model not ready" }

        _state.value = InferenceEngine.State.Generating
        val scaledBitmap = prepareImageForModel(bitmap)

        try {
            val callback = object : StreamCallback {
                var thinkingSeen = forReasoning
                override fun onToken(token: String) {
                    when {
                        !thinkingSeen && token.contains("<think>") -> {
                            thinkingSeen = true
                            val after = token.substringAfter("<think>")
                            if (after.isNotEmpty()) trySend(Pair(STATE.THINKING, after))
                        }
                        !thinkingSeen -> trySend(Pair(STATE.ANSWER, token))
                        !token.contains("</think>") -> trySend(Pair(STATE.THINKING, token))
                        else -> {
                            thinkingSeen = false
                            val after = token.substringAfter("</think>")
                            if (after.isNotEmpty()) trySend(Pair(STATE.ANSWER, after))
                        }
                    }
                }
                override fun onComplete() { close() }
            }
            nativeProcessImageAndText(scaledBitmap, message, forReasoning, callback)
        } catch (e: CancellationException) {
            _state.value = InferenceEngine.State.ModelReady
            close()
            throw e
        } catch (e: Exception) {
            _state.value = InferenceEngine.State.Error(e)
            close(e)
        } finally {
            _state.value = InferenceEngine.State.ModelReady
            close()
            awaitClose()
        }
    }.flowOn(llamaDispatcher)

    override fun cancelGeneration() {
        nativeCancelGeneration()
    }

    override fun isGenerating(): Boolean =
        nativeIsGenerating()

    override fun destroy() {
        nativeRelease()
        cancelGeneration()
        llamaScope.cancel()
        Log.i(TAG, "Inference engine destroyed")
    }

    private fun prepareImageForModel(bitmap: Bitmap): Bitmap {
        val maxDim = 224
        val w = bitmap.width
        val h = bitmap.height

        if (w <= maxDim && h <= maxDim) return bitmap

        val scale = maxDim.toFloat() / maxOf(w, h)
        val newW = (w * scale).toInt()
        val newH = (h * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
            .also { Log.i(TAG, "Image scaled: ${w}x${h} → ${newW}x${newH}") }
    }
}
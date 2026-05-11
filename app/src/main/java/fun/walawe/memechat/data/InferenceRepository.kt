package `fun`.walawe.memechat.data

import android.graphics.Bitmap
import `fun`.walawe.memelm.inference.InferenceEngine
import `fun`.walawe.memelm.inference.InferenceParams
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InferenceRepository @Inject constructor(
    private val inferenceEngine: InferenceEngine,
) {
    val state = inferenceEngine.state

    suspend fun loadModel(modelPath: String, systemPrompt: String) {
        inferenceEngine.loadModel(modelPath, InferenceParams())
        if (systemPrompt.isNotBlank()) {
            inferenceEngine.setSystemPrompt(systemPrompt)
        }
    }

    suspend fun initVision(mmprojPath: String, mediaMarker: String, useGpu: Boolean, warmup: Boolean) {
        inferenceEngine.initVision(mmprojPath, mediaMarker, useGpu, warmup)
    }

    fun sendMessage(message: String): Flow<String> = inferenceEngine.sendUserPrompt(message)

    suspend fun sendImageMessage(message: String, bitmap: Bitmap): Flow<String> =
        inferenceEngine.sendUserPromptWithImage(message, bitmap)

    fun unload() {
        inferenceEngine.cleanUp()
    }

    fun destroy() {
        inferenceEngine.destroy()
    }
}


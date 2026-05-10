package `fun`.walawe.memechat.llm

import `fun`.walawe.memelm.MemeLM
import kotlinx.coroutines.flow.Flow

class MemeLMRepository(
    private val memeLM: MemeLM = MemeLM(),
) {
    suspend fun loadTextModel(
        modelPath: String,
        params: MemeLM.InferenceParams = MemeLM.InferenceParams(),
    ) {
        memeLM.load(modelPath, params)
    }

    suspend fun initVision(
        mmprojPath: String,
        mediaMarker: String = "",
        useGpu: Boolean = true,
        warmup: Boolean = true,
    ) {
        memeLM.initVision(mmprojPath, mediaMarker, useGpu, warmup)
    }

    fun streamText(prompt: String): Flow<String> = memeLM.getResponseAsFlow(prompt)

    fun streamVision(prompt: String, imageBytes: ByteArray): Flow<String> =
        memeLM.getResponseAsFlowWithImage(prompt, imageBytes)

    fun streamImageOnly(
        imageBytes: ByteArray,
        prompt: String = MemeLM.Companion.DEFAULT_IMAGE_PROMPT,
    ): Flow<String> = memeLM.getResponseAsFlowForImage(imageBytes, prompt)

    fun close() {
        memeLM.close()
    }
}
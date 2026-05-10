package `fun`.walawe.memelm

import android.content.Context
import `fun`.walawe.memelm.inference.InferenceEngine
import `fun`.walawe.memelm.inference.InferenceEngineImpl

object MemeLMModule {
    fun getInferenceEngine(context: Context): InferenceEngine = InferenceEngineImpl.getInstance(context)
}
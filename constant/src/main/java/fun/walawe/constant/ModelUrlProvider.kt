package `fun`.walawe.constant

import com.google.android.gms.tasks.Tasks
import com.google.firebase.Firebase
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelUrlProvider {
    private val remoteConfig: FirebaseRemoteConfig = Firebase.remoteConfig

    init {
        remoteConfig.setConfigSettingsAsync(
            remoteConfigSettings {
                minimumFetchIntervalInSeconds = 3600
            }
        )
        remoteConfig.setDefaultsAsync(
            mapOf(
                KEY_FILENAME_MINICPM to MODEL_FILENAME_MINICPM_LLM,
                KEY_FILENAME_MMPROJ to MODEL_FILENAME_MINICPM_MMPROJ,
                KEY_FILENAME_EMBEDDING to MODEL_FILENAME_EMBEDDING,
                KEY_HUGGINGFACE_API to HUGGINGFACE_API_KEY,
                KEY_MCP_KEENABLE_API to KEENABLE_API_KEY,
            )
        )
    }

    suspend fun fetch(): Boolean = withContext(Dispatchers.IO) {
        Tasks.await(remoteConfig.fetchAndActivate())
    }
    fun getModelUrl(): String = remoteConfig.getString(KEY_FILENAME_MINICPM)
    fun getMmprojUrl(): String = remoteConfig.getString(KEY_FILENAME_MMPROJ)
    fun getEmbeddingUrl(): String = remoteConfig.getString(KEY_FILENAME_EMBEDDING)
    fun getHuggingFaceApiKey(): String = remoteConfig.getString(KEY_HUGGINGFACE_API)
    fun getMcpKeenableApiKey(): String = remoteConfig.getString(KEY_MCP_KEENABLE_API)

    companion object {
        private const val KEY_FILENAME_MINICPM = "filename_model_llm"
        private const val KEY_FILENAME_MMPROJ = "filename_model_mmproj"
        private const val KEY_FILENAME_EMBEDDING = "filename_model_embedding"
        private const val KEY_HUGGINGFACE_API = "api_key_huggingface"
        private const val KEY_MCP_KEENABLE_API = "api_key_keenable"
    }
}

package `fun`.walawe.memechat.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import `fun`.walawe.constant.DEFAULT_FILENAME_FAISS_PERSISTANCE
import `fun`.walawe.constant.MODEL_DIR_NAME
import `fun`.walawe.constant.MODEL_DISPLAYNAME_EMBEDDING
import `fun`.walawe.constant.MODEL_DISPLAYNAME_MINICPM_LLM
import `fun`.walawe.constant.MODEL_DISPLAYNAME_MINICPM_MMPROJ
import `fun`.walawe.modelpull.model.CacheKey
import `fun`.walawe.modelpull.model.CacheModel
import `fun`.walawe.modelpull.model.ModelCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Singleton
class ModelRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelCache: ModelCache,
) {
    @OptIn(ExperimentalUuidApi::class)
    suspend fun getCachedModel(key: CacheKey): Result<String> = withContext(Dispatchers.IO) {
        val cached = modelCache.getModel(key)
        if (cached != null) return@withContext Result.success(resolveModelPath(cached))

        val displayName = when (key) {
            CacheKey.Model -> MODEL_DISPLAYNAME_MINICPM_LLM
            CacheKey.MMPROJ -> MODEL_DISPLAYNAME_MINICPM_MMPROJ
            CacheKey.Embedding -> MODEL_DISPLAYNAME_EMBEDDING
            else -> return@withContext Result.failure(
                IllegalStateException("Model not downloaded yet")
            )
        }
        val modelDir = context.getDir(MODEL_DIR_NAME, Context.MODE_PRIVATE)
        val localFile = File(modelDir, displayName)
        val doneFile = File(modelDir, "$displayName.done")

        if (localFile.exists() && localFile.length() > 0L && doneFile.exists()) {
            val downloadedAt = doneFile.readText().toLongOrNull()
            val cacheModel = CacheModel(
                modelId = Uuid.random().toString(),
                displayName = displayName,
                localFileDir = modelDir.absolutePath,
                localFileName = displayName,
                fileCache = localFile,
                downloadedAt = downloadedAt,
            )
            modelCache.setModel(key, cacheModel)
            return@withContext Result.success(resolveModelPath(cacheModel))
        }

        return@withContext Result.failure(
            IllegalStateException("Model not downloaded yet")
        )
    }

    suspend fun getVectorDBPath(): String = withContext(Dispatchers.IO) {
        File(context.filesDir, DEFAULT_FILENAME_FAISS_PERSISTANCE).absolutePath
    }

    fun clearCache(): Result<Unit> = modelCache.deleteAllCachedFiles()

    fun resolveModelPath(cacheModel: CacheModel): String {
        cacheModel.fileCache?.let { return it.absolutePath }
        return File(cacheModel.localFileDir, cacheModel.localFileName).absolutePath
    }
}

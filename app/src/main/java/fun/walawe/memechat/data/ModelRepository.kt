package `fun`.walawe.memechat.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import `fun`.walawe.constant.DEFAULT_FILENAME_FAISS_PERSISTANCE
import `fun`.walawe.modelpull.model.CacheKey
import `fun`.walawe.modelpull.model.CacheModel
import `fun`.walawe.modelpull.model.ModelCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelCache: ModelCache,
) {
    suspend fun getCachedModel(key: CacheKey): Result<String> = withContext(Dispatchers.IO) {
        val cached = modelCache.getModel(key) ?: return@withContext Result.failure(
            IllegalStateException("Model not downloaded yet")
        )
        Result.success(resolveModelPath(cached))
    }

    fun getVectorDBPath(): String =
        File(context.filesDir, DEFAULT_FILENAME_FAISS_PERSISTANCE).absolutePath

    fun clearCache(): Result<Unit> = modelCache.deleteAllCachedFiles()

    fun resolveModelPath(cacheModel: CacheModel): String {
        cacheModel.fileCache?.let { return it.absolutePath }
        return File(cacheModel.localFileDir, cacheModel.localFileName).absolutePath
    }
}

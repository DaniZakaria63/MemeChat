package `fun`.walawe.memechat.data

import `fun`.walawe.modelpull.model.CacheModel
import `fun`.walawe.modelpull.model.ModelCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelRepository @Inject constructor(
    private val modelCache: ModelCache,
) {
    suspend fun getCachedModel(): Result<String> = withContext(Dispatchers.IO) {
        val cached = modelCache.getModel() ?: return@withContext Result.failure(
            IllegalStateException("Model not downloaded yet")
        )
        Result.success(resolveModelPath(cached))
    }

    fun clearCache(): Result<Unit> {
        val cached = modelCache.getModel()
        val cachedFile = cached?.fileCache
        if (cachedFile != null && cachedFile.exists()) {
            if (!cachedFile.delete()) {
                return Result.failure(IllegalStateException("Failed to delete cached model"))
            }
        }
        modelCache.clearModel()
        return Result.success(Unit)
    }

    fun resolveModelPath(cacheModel: CacheModel): String {
        cacheModel.fileCache?.let { return it.absolutePath }
        return File(cacheModel.localFileDir, cacheModel.localFileName).absolutePath
    }
}

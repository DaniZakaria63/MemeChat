package `fun`.walawe.modelpull.model

import `fun`.walawe.constant.CACHE_KEY_MODEL
import `fun`.walawe.constant.CACHE_KEY_MMPROJ
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Global singleton to hold the downloaded model(s) in memory.
 * This allows the model(s) to be accessible throughout the entire app
 * without re-downloading from storage repeatedly.
 */
@Singleton
class ModelCache @Inject constructor() {
    private val cache = ConcurrentHashMap<Int, CacheModel?>()

    fun setModel(cacheKey: CacheKey, model: CacheModel?) {
        cache[cacheKey.ordinal] = model
    }

    fun getModel(cacheKey: CacheKey): CacheModel? = cache[cacheKey.ordinal]

    fun clearModel(cacheKey: CacheKey) {
        val model = cache[cacheKey.ordinal]
        deleteFile(model?.fileCache)
        cache.remove(cacheKey.ordinal)
    }

    fun deleteAllCachedFiles() = Result.runCatching {
        cache.values.forEach { cached ->
            deleteFile(cached?.fileCache)
        }
        cache.clear()
    }

    private fun deleteFile(cachedFile: File?) = Result.runCatching{
        if (cachedFile != null && cachedFile.exists()) {
            if (!cachedFile.delete()) {
                throw IllegalStateException("Failed to delete cached model")
            }
        }
    }
}

enum class CacheKey {
    Model,
    MMPROJ,
    ALL,
}
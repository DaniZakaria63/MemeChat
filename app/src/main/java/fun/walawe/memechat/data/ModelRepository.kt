package `fun`.walawe.memechat.data

import `fun`.walawe.memechat.model.ModelDescriptor
import `fun`.walawe.memelm.gguf.GGUFReader
import `fun`.walawe.modelpull.model.CachePaligemmaModel
import `fun`.walawe.modelpull.model.EXPECTED_MODEL_BASENAME
import `fun`.walawe.modelpull.model.ModelCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelRepository @Inject constructor(
    private val modelCache: ModelCache,
    private val ggufReader: GGUFReader,
) {
    suspend fun getCachedModelDescriptor(): Result<ModelDescriptor> {
        val cached = modelCache.getModel() ?: return Result.failure(
            IllegalStateException("Model not downloaded yet")
        )
        val modelPath = resolveModelPath(cached)
        return validateAndDescribe(modelPath)
    }

    fun getCachedModel(): CachePaligemmaModel? = modelCache.getModel()

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

    fun resolveModelPath(cacheModel: CachePaligemmaModel): String {
        cacheModel.fileCache?.let { return it.absolutePath }
        return File(cacheModel.localFileDir, cacheModel.localFileName).absolutePath
    }

    suspend fun validateAndDescribe(modelPath: String): Result<ModelDescriptor> = withContext(Dispatchers.IO) {
        val file = File(modelPath)
        if (!file.exists()) {
            return@withContext Result.failure(IllegalStateException("Model file not found"))
        }
        ggufReader.load(modelPath)
        if (!ggufReader.isExpectedQwenModel(EXPECTED_MODEL_BASENAME)) {
            return@withContext Result.failure(
                IllegalArgumentException("Unexpected GGUF model. Expected ${EXPECTED_MODEL_BASENAME}")
            )
        }

        val basename = ggufReader.getModelBasename() ?: file.nameWithoutExtension
        val quant = basename.substringAfterLast('.', missingDelimiterValue = "unknown")

        Result.success(
            ModelDescriptor(
                name = basename,
                quantization = quant,
                fileSizeBytes = file.length(),
                path = modelPath,
                contextLength = ggufReader.getContextSize(),
            )
        )
    }

    fun findMmprojFile(modelPath: String): File? {
        val dir = File(modelPath).parentFile ?: return null
        val files = dir.listFiles().orEmpty()
        return files.firstOrNull { file ->
            val name = file.name.lowercase()
            name.contains("mmproj") && file.isFile
        }
    }
}

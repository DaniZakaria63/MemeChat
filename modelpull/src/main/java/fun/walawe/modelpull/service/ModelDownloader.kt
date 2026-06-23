package `fun`.walawe.modelpull.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import `fun`.walawe.constant.ModelUrlProvider
import `fun`.walawe.constant.MODEL_FILENAME_MINICPM_LLM
import `fun`.walawe.modelpull.api.WalaweClientAPI
import `fun`.walawe.modelpull.model.BadRequestException
import `fun`.walawe.modelpull.model.CacheModel
import `fun`.walawe.modelpull.model.NotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid


interface ModelDownloader {
    suspend fun getModel(
        uri: String,
        fileName: String = MODEL_FILENAME_MINICPM_LLM,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<CacheModel>
}

class LocalModelDownloader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val walaweClientAPI: WalaweClientAPI,
    private val modelUrlProvider: ModelUrlProvider,
) : ModelDownloader {

    private val modelDir: File by lazy {
        val dir = context.getDir("ml_models", Context.MODE_PRIVATE)
        if (!dir.exists() && !dir.mkdirs()) {
            error("Failed to create model directory: ${dir.absolutePath}")
        }
        if (!dir.isDirectory) {
            error("Model directory path is not a directory: ${dir.absolutePath}")
        }
        dir
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun getModel(
        uri: String,
        fileName: String,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): Result<CacheModel> {
        val safeName = File(fileName).name
        Timber.d("getModel: uri=$uri fileName=$fileName safeName=$safeName modelDir=${modelDir.absolutePath}")
        val localFile = File(modelDir, safeName)

        if (localFile.exists() && localFile.length() > 0L) {
            Timber.d("Model already exists locally: ${localFile.absolutePath} (${localFile.length()} bytes)")
            return Result.success(CacheModel(
                modelId = Uuid.random().toString(),
                displayName = safeName,
                localFileDir = modelDir.absolutePath,
                localFileName = safeName,
                fileCache = localFile,
            ))
        }
        if (localFile.exists() && localFile.length() == 0L) {
            Timber.d("Removing empty partial file from previous failed download")
            localFile.delete()
        }

        try {
            val response = withContext(Dispatchers.IO) {
                walaweClientAPI.getModel(uri.ifEmpty { modelUrlProvider.getModelUrl() })
            }

            if (!response.isSuccessful || response.body() == null) {
                return Result.failure(
                    BadRequestException("HTTP ${response.code()}: ${response.message()}")
                )
            }

            withContext(Dispatchers.IO) {
                if (!modelDir.exists() && !modelDir.mkdirs()) {
                    throw java.io.IOException("Failed to create model directory: ${modelDir.absolutePath}")
                }
                val body = response.body()!!
                val totalBytes = body.contentLength().coerceAtLeast(0L)
                var downloaded = 0L
                body.byteStream().use { inputStream ->
                    localFile.outputStream().use { outputStream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var read = inputStream.read(buffer)
                        while (read >= 0) {
                            outputStream.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, totalBytes)
                            read = inputStream.read(buffer)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "getModel failed: uri=$uri fileName=$fileName safeName=$safeName modelDir=${modelDir.absolutePath} exists=${modelDir.exists()}")
            return Result.failure(BadRequestException("${e::class.simpleName}: ${e.message ?: "no message"}"))
        }

        if (!localFile.exists() || localFile.length() == 0L) {
            return Result.failure(NotFoundException("Downloaded file is empty"))
        }

        val cacheModel = CacheModel(
            modelId = Uuid.random().toString(),
            displayName = safeName,
            localFileName = safeName,
            localFileDir = modelDir.absolutePath,
            fileCache = localFile,
            downloadedAt = System.currentTimeMillis(),
        )

        return Result.success(cacheModel)
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 8 * 1024
    }
}
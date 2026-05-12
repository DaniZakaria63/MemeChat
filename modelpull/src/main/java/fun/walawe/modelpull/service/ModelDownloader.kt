package `fun`.walawe.modelpull.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import `fun`.walawe.constant.DEFAULT_MODEL_DOWNLOADER_URI
import `fun`.walawe.constant.MODEL_FILENAME_MINICPM
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
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<CacheModel>
}

class LocalModelDownloader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val walaweClientAPI: WalaweClientAPI,
) : ModelDownloader {

    private val modelDir by lazy {
        context.getDir("ml_models", Context.MODE_PRIVATE).also {
            it.mkdirs()
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun getModel(
        uri: String,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): Result<CacheModel> {
        val defaultModelName = MODEL_FILENAME_MINICPM
        val localFile = File(modelDir, defaultModelName)

        if (localFile.exists()) {
            Timber.d("Model already exists locally")
            return Result.success(CacheModel(
                modelId = Uuid.random().toString(),
                displayName = defaultModelName,
                localFileDir = modelDir.absolutePath,
                localFileName = defaultModelName,
                fileCache = localFile,
            ))
        }

        try {
            val response = withContext(Dispatchers.IO) {
                walaweClientAPI.getModel(uri.ifEmpty { DEFAULT_MODEL_DOWNLOADER_URI  })
            }

            if (!response.isSuccessful || response.body() == null) {
                return Result.failure(
                    BadRequestException(response.message())
                )
            }

            withContext(Dispatchers.IO) {
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
            return Result.failure(BadRequestException("Network error: ${e.message}"))
        }

        if (!localFile.exists() || localFile.length() == 0L) {
            return Result.failure(NotFoundException("Downloaded file is empty"))
        }

        val cacheModel = CacheModel(
            modelId = Uuid.random().toString(),
            displayName = defaultModelName,
            localFileName = defaultModelName,
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
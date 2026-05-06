package `fun`.walawe.modelpull.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import `fun`.walawe.modelpull.BuildConfig
import `fun`.walawe.modelpull.api.WalaweClientAPI
import `fun`.walawe.modelpull.model.BadRequestException
import `fun`.walawe.modelpull.model.CachePaligemmaModel
import `fun`.walawe.modelpull.model.NotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid


interface ModelDownloader {
    suspend fun getModel(uri: String): Result<CachePaligemmaModel>
}

class LocalModelDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walaweClientAPI: WalaweClientAPI
) : ModelDownloader {

    private val modelDir by lazy {
        context.getDir("ml_models", Context.MODE_PRIVATE).also {
            it.mkdirs()
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun getModel(uri: String): Result<CachePaligemmaModel> {
        val defaultModelName = "model.tflite"
        val localFile = File(modelDir, defaultModelName)

        if (localFile.exists()) {
            Timber.d("Model already exists locally")
            return Result.success(CachePaligemmaModel(
                modelId = Uuid.random().toString(),
                displayName = defaultModelName,
                localFileDir = modelDir.absolutePath,
                localFileName = defaultModelName,
                fileCache = localFile,
            ))
        }

        try {
            val response = withContext(Dispatchers.IO) {
                walaweClientAPI.getModel(uri.ifEmpty { BuildConfig.URI_PALIGEMMA  })
            }

            if (!response.isSuccessful || response.body() == null) {
                return Result.failure(
                    BadRequestException(response.message())
                )
            }

            withContext(Dispatchers.IO) {
                response.body()!!.byteStream().use { inputStream ->
                    localFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
        } catch (e: Exception) {
            return Result.failure(BadRequestException("Network error: ${e.message}"))
        }

        if (!localFile.exists() || localFile.length() == 0L) {
            return Result.failure(NotFoundException("Downloaded file is empty"))
        }

        val cacheModel = CachePaligemmaModel(
            modelId = Uuid.random().toString(),
            displayName = defaultModelName,
            localFileName = defaultModelName,
            localFileDir = modelDir.absolutePath,
            fileCache = localFile,
            downloadedAt = System.currentTimeMillis(),
        )

        return Result.success(cacheModel)
    }

}
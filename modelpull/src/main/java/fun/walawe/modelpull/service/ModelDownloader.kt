package `fun`.walawe.modelpull.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import `fun`.walawe.constant.ModelUrlProvider
import `fun`.walawe.constant.MODEL_DIR_NAME
import `fun`.walawe.constant.MODEL_FILENAME_MINICPM_LLM
import `fun`.walawe.modelpull.api.WalaweClientAPI
import `fun`.walawe.modelpull.model.BadRequestException
import `fun`.walawe.modelpull.model.CacheModel
import `fun`.walawe.modelpull.model.NotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
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
        val dir = context.getDir(MODEL_DIR_NAME, Context.MODE_PRIVATE)
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
        val doneFile = File(modelDir, "$safeName.done")

        if (localFile.exists() && localFile.length() > 0L && doneFile.exists()) {
            val downloadedAt = doneFile.readText().toLongOrNull()
            Timber.d("Model already downloaded: ${localFile.absolutePath} (${localFile.length()} bytes)")
            return Result.success(CacheModel(
                modelId = Uuid.random().toString(),
                displayName = safeName,
                localFileDir = modelDir.absolutePath,
                localFileName = safeName,
                fileCache = localFile,
                downloadedAt = downloadedAt ?: System.currentTimeMillis(),
            ))
        }

        var existingBytes = 0L
        if (localFile.exists()) {
            existingBytes = localFile.length()
            doneFile.delete()
            if (existingBytes == 0L) {
                localFile.delete()
            } else {
                Timber.d("Partial file found, attempting resume: ${localFile.absolutePath} ($existingBytes bytes)")
            }
        }

        try {
            val rangeHeader = if (existingBytes > 0L) "bytes=$existingBytes-" else null
            val response = withContext(Dispatchers.IO) {
                val reqUrl = uri.ifEmpty { modelUrlProvider.getModelUrl() }
                walaweClientAPI.getModel(reqUrl, rangeHeader)
            }

            if (!response.isSuccessful || response.body() == null) {
                localFile.delete()
                doneFile.delete()
                return Result.failure(
                    BadRequestException("HTTP ${response.code()}: ${response.message()}")
                )
            }

            withContext(Dispatchers.IO) {
                if (!modelDir.exists() && !modelDir.mkdirs()) {
                    throw java.io.IOException("Failed to create model directory: ${modelDir.absolutePath}")
                }

                val body = response.body()!!
                var downloaded = existingBytes
                var totalBytes = body.contentLength().coerceAtLeast(0L)

                if (response.code() == 206) {
                    val contentRange = response.headers()["Content-Range"]
                    contentRange?.let {
                        val total = it.substringAfter("/").toLongOrNull()
                        if (total != null) totalBytes = total
                    }
                    if (body.contentLength() == 0L) {
                        Timber.d("File already complete per server, no bytes to download")
                        doneFile.writeText(System.currentTimeMillis().toString())
                        return@withContext
                    }
                }

                if (response.code() == 200 && existingBytes > 0L) {
                    Timber.d("Server does not support Range, re-downloading from scratch")
                    localFile.delete()
                    downloaded = 0L
                    totalBytes = body.contentLength().coerceAtLeast(0L)
                }

                val fileOut = FileOutputStream(localFile, downloaded > 0L)
                fileOut.use { outputStream ->
                    body.byteStream().use { inputStream ->
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
            localFile.delete()
            doneFile.delete()
            return Result.failure(BadRequestException("${e::class.simpleName}: ${e.message ?: "no message"}"))
        }

        if (!localFile.exists() || localFile.length() == 0L) {
            return Result.failure(NotFoundException("Downloaded file is empty"))
        }

        val now = System.currentTimeMillis()
        doneFile.writeText(now.toString())

        val cacheModel = CacheModel(
            modelId = Uuid.random().toString(),
            displayName = safeName,
            localFileName = safeName,
            localFileDir = modelDir.absolutePath,
            fileCache = localFile,
            downloadedAt = now,
        )

        return Result.success(cacheModel)
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 8 * 1024
    }
}
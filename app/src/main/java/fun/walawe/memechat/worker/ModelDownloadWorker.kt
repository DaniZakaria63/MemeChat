package `fun`.walawe.memechat.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import `fun`.walawe.modelpull.model.BadRequestException
import `fun`.walawe.modelpull.model.DEFAULT_MODEL_DOWNLOADER_URI
import `fun`.walawe.modelpull.model.IllegalURILinkIdException
import `fun`.walawe.modelpull.model.ModelCache
import `fun`.walawe.modelpull.model.NotFoundException
import `fun`.walawe.modelpull.service.ModelDownloader
import timber.log.Timber
import java.net.UnknownHostException

@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val modelDownloader: ModelDownloader,
    private val modelCache: ModelCache,
): CoroutineWorker(appContext, workerParams){
    override suspend fun doWork(): Result {
        if (modelCache.getModel() != null) {
            Timber.d("Model already cached, skipping download")
            return Result.success(workDataOf("warning" to "Model already exists"))
        }

        val downloadResult = modelDownloader.getModel(DEFAULT_MODEL_DOWNLOADER_URI)

        if (!downloadResult.isSuccess) {
            if (runAttemptCount < MAX_RETRIES) {
                Timber.d("Retrying download (attempt ${runAttemptCount + 1}/${MAX_RETRIES})")
                return Result.retry()
            }

            val error = when(val exception = downloadResult.exceptionOrNull()) {
                is UnknownHostException -> NotFoundException("Server unreachable")
                else -> BadRequestException("Failed to download model: ${exception?.message}")
            }

            return Result.failure(workDataOf(
                "error_type" to error::class.simpleName,
                "error_message" to error.message
            ))
        }

        val cacheModel = downloadResult.getOrNull() ?: return Result.failure(workDataOf(
            "error_type" to IllegalURILinkIdException::class.simpleName,
            "error_message" to "Invalid model URI or link ID"
        ))

        if (cacheModel.localFileName.isBlank()) {
            return Result.failure(workDataOf(
                "error_type" to IllegalURILinkIdException::class.simpleName,
                "error_message" to "Invalid model URI or link ID"
            ))
        }

        Timber.d("Model downloaded successfully: ${cacheModel.displayName}")

        modelCache.setModel(cacheModel) // Static memory cache
        Timber.d("Model metadata saved to settings")

        return Result.success(workDataOf("info" to "Model downloaded successfully"))
    }

    companion object {
        private val TAG = ModelDownloadWorker::class.simpleName
        private val MAX_RETRIES = 3
        const val WORK_TAG = "model_download"

        // Test Only
        const val TEST_MODEL_URI = "http://192.168.0.103:39983/dummy.txt"
    }
}
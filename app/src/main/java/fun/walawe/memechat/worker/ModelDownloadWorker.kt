package `fun`.walawe.memechat.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import `fun`.walawe.constant.DEFAULT_EMBEDDING_DOWNLOADER_URI
import `fun`.walawe.constant.DEFAULT_MODEL_DOWNLOADER_URI
import `fun`.walawe.constant.DEFAULT_MMPROJ_DOWNLOADER_URI
import `fun`.walawe.constant.MODEL_FILENAME_EMBEDDING
import `fun`.walawe.constant.MODEL_FILENAME_MINICPM
import `fun`.walawe.constant.MODEL_FILENAME_MINICPM_MMPROJ
import `fun`.walawe.constant.orZero
import `fun`.walawe.modelpull.model.BadRequestException
import `fun`.walawe.modelpull.model.CacheKey
import `fun`.walawe.modelpull.model.DownloadTarget
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
        val targets = listOf(
            DownloadTarget(
                uri = DEFAULT_MODEL_DOWNLOADER_URI,
                fileName = MODEL_FILENAME_MINICPM,
                cacheModel = true,
                keyCacheModel = CacheKey.Model
            ),
            DownloadTarget(
                uri = DEFAULT_MMPROJ_DOWNLOADER_URI,
                fileName = MODEL_FILENAME_MINICPM_MMPROJ,
                cacheModel = true,
                keyCacheModel = CacheKey.MMPROJ
            ),
            DownloadTarget(
                uri = DEFAULT_EMBEDDING_DOWNLOADER_URI,
                fileName = MODEL_FILENAME_EMBEDDING,
                cacheModel = true,
                keyCacheModel = CacheKey.EMBEDDING
            )
        )

        var lastProgressUpdateMs = 0L
        var lastReportedBytes = 0L
        val fileCount = targets.size

        fun updateProgress(
            target: DownloadTarget,
            fileIndex: Int,
            downloaded: Long,
            total: Long,
            force: Boolean = false,
        ) {
            val now = System.currentTimeMillis()
            val shouldThrottle =
                !force &&
                downloaded != total &&
                (now - lastProgressUpdateMs) < PROGRESS_THROTTLE_MS &&
                (downloaded - lastReportedBytes) < MIN_PROGRESS_BYTES

            if (shouldThrottle) return

            lastProgressUpdateMs = now
            lastReportedBytes = downloaded
            setProgressAsync(
                workDataOf(
                    PROGRESS_FILE_NAME to target.fileName,
                    PROGRESS_FILE_URI to target.uri,
                    PROGRESS_FILE_INDEX to (fileIndex + 1),
                    PROGRESS_FILE_COUNT to fileCount,
                    PROGRESS_BYTES to downloaded,
                    PROGRESS_TOTAL_BYTES to total
                )
            )
        }

        for ((index, target) in targets.withIndex()) {
            updateProgress(target, index, 0L, 0L, force = true)

            val downloadResult = modelDownloader.getModel(target.uri, target.fileName) { downloaded, total ->
                updateProgress(target, index, downloaded, total)
            }

            if (!downloadResult.isSuccess) {
                val exception = downloadResult.exceptionOrNull()
                Timber.e(exception, "Download failed for ${target.fileName} (attempt ${runAttemptCount + 1})")

                if (runAttemptCount < MAX_RETRIES) {
                    Timber.d("Retrying download (attempt ${runAttemptCount + 1}/${MAX_RETRIES})")
                    return Result.retry()
                }

                val error = when(exception) {
                    is UnknownHostException -> NotFoundException("Server unreachable: ${exception.message ?: "unknown host"}")
                    else -> BadRequestException("Failed to download model: ${exception?.message ?: exception?.let { it::class.simpleName } ?: "unknown error"}")
                }

                return Result.failure(workDataOf(
                    "error_type" to error::class.simpleName,
                    "error_message" to error.message,
                    PROGRESS_FILE_NAME to target.fileName,
                    PROGRESS_FILE_URI to target.uri
                ))
            }

            val cacheModel = downloadResult.getOrNull() ?: return Result.failure(workDataOf(
                "error_type" to IllegalURILinkIdException::class.simpleName,
                "error_message" to "Invalid model URI or link ID",
                PROGRESS_FILE_NAME to target.fileName,
                PROGRESS_FILE_URI to target.uri
            ))

            if (cacheModel.localFileName.isBlank()) {
                return Result.failure(workDataOf(
                    "error_type" to IllegalURILinkIdException::class.simpleName,
                    "error_message" to "Invalid model URI or link ID",
                    PROGRESS_FILE_NAME to target.fileName,
                    PROGRESS_FILE_URI to target.uri
                ))
            }

            Timber.d("Model downloaded successfully: ${cacheModel.displayName}")
            if (target.cacheModel) {
                modelCache.setModel(target.keyCacheModel, cacheModel) // Static memory cache
                Timber.d("Model metadata saved to settings")
            }

            val finalSize = cacheModel.fileCache?.length().orZero().coerceAtLeast(0L)
            updateProgress(target, index, finalSize, finalSize, force = true)
        }

        return Result.success(workDataOf("info" to "Model downloaded successfully"))
    }

    companion object {
        private val TAG = ModelDownloadWorker::class.simpleName
        private val MAX_RETRIES = 3
        const val WORK_TAG = "model_download"
        const val PROGRESS_BYTES = "progress_bytes"
        const val PROGRESS_TOTAL_BYTES = "progress_total_bytes"
        const val PROGRESS_FILE_NAME = "progress_file_name"
        const val PROGRESS_FILE_URI = "progress_file_uri"
        const val PROGRESS_FILE_INDEX = "progress_file_index"
        const val PROGRESS_FILE_COUNT = "progress_file_count"
        private const val PROGRESS_THROTTLE_MS = 200L
        private const val MIN_PROGRESS_BYTES = 64 * 1024L

        // Test Only
        const val TEST_MODEL_URI = "http://192.168.0.103:39983/dummy.txt"
    }
}
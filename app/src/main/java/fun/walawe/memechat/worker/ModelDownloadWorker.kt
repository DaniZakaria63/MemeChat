package `fun`.walawe.memechat.worker

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import `fun`.walawe.constant.MODEL_DISPLAYNAME_EMBEDDING
import `fun`.walawe.constant.MODEL_DISPLAYNAME_MINICPM_LLM
import `fun`.walawe.constant.MODEL_DISPLAYNAME_MINICPM_MMPROJ
import `fun`.walawe.constant.ModelUrlProvider
import `fun`.walawe.constant.orZero
import `fun`.walawe.memechat.MainActivity
import `fun`.walawe.memechat.MemeChatApp
import `fun`.walawe.memechat.analyzer.DeviceCompatibilityChecker
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
    private val modelUrlProvider: ModelUrlProvider,
    private val deviceCompatibilityChecker: DeviceCompatibilityChecker,
): CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (!deviceCompatibilityChecker.isStorageSufficient()) {
            Timber.w("Insufficient storage in worker — aborting download")
            return Result.failure(workDataOf(
                ERROR_TYPE_KEY to ERROR_INSUFFICIENT_STORAGE,
                ERROR_MESSAGE_KEY to ERROR_MSG_STORAGE,
            ))
        }

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        setForeground(createForegroundInfo("Preparing download...", 0, 0, 0, 0, ""))

        val targets = listOf(
            DownloadTarget(
                uri = modelUrlProvider.getModelUrl(),
                fileName = MODEL_DISPLAYNAME_MINICPM_LLM,
                cacheModel = true,
                keyCacheModel = CacheKey.Model
            ),
            DownloadTarget(
                uri = modelUrlProvider.getMmprojUrl(),
                fileName = MODEL_DISPLAYNAME_MINICPM_MMPROJ,
                cacheModel = true,
                keyCacheModel = CacheKey.MMPROJ
            ),
            DownloadTarget(
                uri = modelUrlProvider.getEmbeddingUrl(),
                fileName = MODEL_DISPLAYNAME_EMBEDDING,
                cacheModel = true,
                keyCacheModel = CacheKey.Embedding
            )
        )

        var lastProgressUpdateMs = 0L
        var lastReportedBytes = 0L
        val fileCount = targets.size

        fun updateNotification(
            fileName: String,
            fileIndex: Int,
            downloaded: Long,
            total: Long,
        ) {
            val notification = createForegroundInfo(fileName, fileIndex, fileCount, downloaded, total, fileName)
            notificationManager.notify(NOTIFICATION_ID, notification.notification)
        }

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
            updateNotification(target.fileName, fileIndex + 1, downloaded, total)
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
                    ERROR_TYPE_KEY to error::class.simpleName,
                    ERROR_MESSAGE_KEY to error.message,
                    PROGRESS_FILE_NAME to target.fileName,
                    PROGRESS_FILE_URI to target.uri
                ))
            }

            val cacheModel = downloadResult.getOrNull() ?: return Result.failure(workDataOf(
                ERROR_TYPE_KEY to IllegalURILinkIdException::class.simpleName,
                ERROR_MESSAGE_KEY to "Invalid model URI or link ID",
                PROGRESS_FILE_NAME to target.fileName,
                PROGRESS_FILE_URI to target.uri
            ))

            if (cacheModel.localFileName.isBlank()) {
                return Result.failure(workDataOf(
                    ERROR_TYPE_KEY to IllegalURILinkIdException::class.simpleName,
                    ERROR_MESSAGE_KEY to "Invalid model URI or link ID",
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

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo("", 0, 0, 0, 0, "")
    }

    private fun createForegroundInfo(
        fileName: String,
        fileIndex: Int,
        fileCount: Int,
        bytesDownloaded: Long,
        totalBytes: Long,
        notificationFileName: String,
    ): ForegroundInfo {
        val notification = buildNotification(fileName, fileIndex, fileCount, bytesDownloaded, totalBytes, notificationFileName)
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        fileName: String,
        fileIndex: Int,
        fileCount: Int,
        bytesDownloaded: Long,
        totalBytes: Long,
        notificationFileName: String,
    ): Notification {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MemeChatApp.EXTRA_NAVIGATE_TO_DOWNLOAD, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val displayName = if (fileName.isNotBlank()) fileName else "Downloading AI Model"
        val percentage = if (totalBytes > 0L) (bytesDownloaded * 100 / totalBytes).toInt() else 0
        val fileInfo = if (fileCount > 0) "File $fileIndex of $fileCount" else "Preparing..."

        val text = if (totalBytes > 0L) {
            "$fileInfo · $percentage% · $displayName"
        } else {
            "$fileInfo · $displayName"
        }

        return NotificationCompat.Builder(applicationContext, MemeChatApp.DOWNLOAD_CHANNEL_ID)
            .setContentTitle("Downloading AI Model")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setColor(Color.parseColor(NOTIFICATION_COLOR))
            .setColorized(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (totalBytes > 0L) {
                    setProgress(totalBytes.toInt(), bytesDownloaded.toInt(), false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()
    }

    companion object {
        private val TAG = ModelDownloadWorker::class.simpleName
        private val MAX_RETRIES = 3
        const val WORK_TAG = "model_download"
        const val ERROR_TYPE_KEY = "error_type"
        const val ERROR_MESSAGE_KEY = "error_message"
        const val ERROR_INSUFFICIENT_STORAGE = "InsufficientStorage"
        const val ERROR_MSG_STORAGE = "Not enough free storage space. Please free up space and try again."
        const val PROGRESS_BYTES = "progress_bytes"
        const val PROGRESS_TOTAL_BYTES = "progress_total_bytes"
        const val PROGRESS_FILE_NAME = "progress_file_name"
        const val PROGRESS_FILE_URI = "progress_file_uri"
        const val PROGRESS_FILE_INDEX = "progress_file_index"
        const val PROGRESS_FILE_COUNT = "progress_file_count"
        private const val PROGRESS_THROTTLE_MS = 200L
        private const val MIN_PROGRESS_BYTES = 64 * 1024L
        const val NOTIFICATION_ID = MemeChatApp.NOTIFICATION_ID
        private const val NOTIFICATION_COLOR = "#00685E"

        // Test Only
        const val TEST_MODEL_URI = "http://192.168.0.103:39983/dummy.txt"
    }
}

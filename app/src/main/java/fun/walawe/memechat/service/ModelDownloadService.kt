package `fun`.walawe.memechat.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import dagger.hilt.android.AndroidEntryPoint
import `fun`.walawe.constant.MODEL_DISPLAYNAME_EMBEDDING
import `fun`.walawe.constant.MODEL_DISPLAYNAME_MINICPM_LLM
import `fun`.walawe.constant.MODEL_DISPLAYNAME_MINICPM_MMPROJ
import `fun`.walawe.constant.ModelUrlProvider
import `fun`.walawe.constant.orZero
import `fun`.walawe.memechat.MemeChatApp
import `fun`.walawe.memechat.R
import `fun`.walawe.memechat.analyzer.DeviceCompatibilityChecker
import `fun`.walawe.memechat.model.DownloadStatus
import `fun`.walawe.memechat.model.DownloadUiState
import `fun`.walawe.modelpull.model.BadRequestException
import `fun`.walawe.modelpull.model.CacheKey
import `fun`.walawe.modelpull.model.DownloadTarget
import `fun`.walawe.modelpull.model.IllegalURILinkIdException
import `fun`.walawe.modelpull.model.ModelCache
import `fun`.walawe.modelpull.model.NotFoundException
import `fun`.walawe.modelpull.service.ModelDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.UnknownHostException
import javax.inject.Inject

@AndroidEntryPoint
class ModelDownloadService : Service() {

    @Inject lateinit var modelDownloader: ModelDownloader
    @Inject lateinit var modelCache: ModelCache
    @Inject lateinit var modelUrlProvider: ModelUrlProvider
    @Inject lateinit var deviceCompatibilityChecker: DeviceCompatibilityChecker

    private lateinit var notificationManager: DownloadNotificationManager
    private lateinit var systemNotificationManager: NotificationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var lastProgressUpdateMs = 0L
    private var lastReportedBytes = 0L

    override fun onCreate() {
        super.onCreate()
        notificationManager = DownloadNotificationManager(this)
        systemNotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!deviceCompatibilityChecker.isStorageSufficient()) {
            Timber.w("Insufficient storage in service — aborting download")
            DownloadServiceState.update {
                it.copy(
                    status = DownloadStatus.InsufficientStorage,
                    compatibilityMessage = getString(R.string.model_error_storage),
                )
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val initialNotification = notificationManager.buildForegroundNotification("", 0, 0, 0, 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        scope.launch {
            try {
                downloadModels()
            } catch (e: Exception) {
                Timber.e(e, "Unexpected exception during download")
                val errorNotification = notificationManager.buildErrorNotification(
                    e.message ?: getString(R.string.model_error_unexpected_download)
                )
                systemNotificationManager.notify(ERROR_NOTIFICATION_ID, errorNotification)
                DownloadServiceState.update {
                    it.copy(status = DownloadStatus.Error, errorMessage = e.message ?: getString(R.string.model_error_unexpected))
                }
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun downloadModels() {
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

        val fileCount = targets.size
        var totalError: String? = null

        for ((index, target) in targets.withIndex()) {
            val fileIdx = index + 1

            updateServiceProgress(target.fileName, fileIdx, fileCount, 0L, 0L, force = true)

            var lastException: Throwable? = null
            var success = false

            for (attempt in 1..MAX_RETRIES) {
                val downloadResult = modelDownloader.getModel(target.uri, target.fileName) { downloaded, total ->
                    updateServiceProgress(target.fileName, fileIdx, fileCount, downloaded, total)
                }

                if (downloadResult.isSuccess) {
                    val cacheModel = downloadResult.getOrNull()
                    if (cacheModel != null && cacheModel.localFileName.isNotBlank()) {
                        Timber.d("Model downloaded successfully: ${cacheModel.displayName}")
                        if (target.cacheModel) {
                            modelCache.setModel(target.keyCacheModel, cacheModel)
                        }
                        val finalSize = cacheModel.fileCache?.length().orZero().coerceAtLeast(0L)
                        updateServiceProgress(target.fileName, fileIdx, fileCount, finalSize, finalSize, force = true)
                        success = true
                        break
                    } else {
                        lastException = IllegalURILinkIdException(getString(R.string.model_error_invalid_uri))
                    }
                } else {
                    lastException = downloadResult.exceptionOrNull()
                }

                if (!success && attempt < MAX_RETRIES) {
                    Timber.d("Retrying download (attempt $attempt/$MAX_RETRIES)")
                    val delayMs = 2000L * attempt
                    kotlinx.coroutines.delay(delayMs)
                }
            }

            if (!success) {
                val exception = lastException
                Timber.e(exception, "Download failed for ${target.fileName}")
                totalError = when (exception) {
                    is UnknownHostException -> getString(R.string.model_error_server_unreachable, exception.message ?: "unknown host")
                    else -> getString(R.string.model_error_download_failed, exception?.message ?: "unknown error")
                }
                break
            }
        }

        if (totalError != null) {
            val errorNotification = notificationManager.buildErrorNotification(totalError)
            systemNotificationManager.notify(ERROR_NOTIFICATION_ID, errorNotification)
            DownloadServiceState.update {
                it.copy(status = DownloadStatus.Error, errorMessage = totalError)
            }
        } else {
            val completionNotification = notificationManager.buildCompletionNotification()
            systemNotificationManager.notify(NOTIFICATION_ID, completionNotification)
            DownloadServiceState.update {
                it.copy(status = DownloadStatus.Completed)
            }
        }

        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun updateServiceProgress(
        fileName: String,
        fileIndex: Int,
        fileCount: Int,
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

        val notification = notificationManager.buildForegroundNotification(
            fileName, fileIndex, fileCount, downloaded, total,
        )
        systemNotificationManager.notify(NOTIFICATION_ID, notification)

        DownloadServiceState.update {
            it.copy(
                status = DownloadStatus.Downloading,
                bytesDownloaded = downloaded,
                totalBytes = total,
                fileName = fileName,
                fileIndex = fileIndex,
                fileCount = fileCount,
                errorMessage = null,
                compatibilityMessage = null,
            )
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = MemeChatApp.NOTIFICATION_ID
        private const val ERROR_NOTIFICATION_ID = 1003
        private const val MAX_RETRIES = 3
        private const val PROGRESS_THROTTLE_MS = 200L
        private const val MIN_PROGRESS_BYTES = 64 * 1024L
    }
}

object DownloadServiceState {
    private val _state = MutableStateFlow(DownloadUiState())
    val state = _state.asStateFlow()

    fun update(block: (DownloadUiState) -> DownloadUiState) {
        _state.update(block)
    }

    fun reset() {
        _state.value = DownloadUiState()
    }
}

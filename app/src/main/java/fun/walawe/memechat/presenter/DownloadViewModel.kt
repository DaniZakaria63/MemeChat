package `fun`.walawe.memechat.presenter

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import `fun`.walawe.memechat.model.DownloadStatus
import `fun`.walawe.memechat.model.DownloadUiState
import `fun`.walawe.memechat.worker.ModelDownloadWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class DownloadViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DownloadUiState())
    val uiState = _uiState.asStateFlow()

    init {
        observeWorker()
    }

    private fun observeWorker() {
        viewModelScope.launch {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.WORK_TAG)
                .collect { infos ->
                    val info = infos.firstOrNull()
                    updateFromWorkInfo(info)
                }
        }
    }

    private fun updateFromWorkInfo(info: WorkInfo?) {
        if (info == null) {
            _uiState.update { it.copy(status = DownloadStatus.Idle, errorMessage = null) }
            return
        }

        val progress = info.progress
        val bytesDownloaded = progress.getLong(ModelDownloadWorker.PROGRESS_BYTES, 0L)
        val totalBytes = progress.getLong(ModelDownloadWorker.PROGRESS_TOTAL_BYTES, 0L)
        val fileName = progress.getString(ModelDownloadWorker.PROGRESS_FILE_NAME)
            ?: info.outputData.getString(ModelDownloadWorker.PROGRESS_FILE_NAME)
        val fileIndex = progress.getInt(ModelDownloadWorker.PROGRESS_FILE_INDEX, 0)
        val fileCount = progress.getInt(ModelDownloadWorker.PROGRESS_FILE_COUNT, 0)

        when (info.state) {
            WorkInfo.State.SUCCEEDED -> {
                _uiState.update {
                    it.copy(
                        status = DownloadStatus.Completed,
                        errorMessage = null,
                        bytesDownloaded = bytesDownloaded,
                        totalBytes = totalBytes,
                        fileName = fileName,
                        fileIndex = fileIndex,
                        fileCount = fileCount,
                    )
                }
            }
            WorkInfo.State.RUNNING,
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED -> {
                _uiState.update {
                    it.copy(
                        status = DownloadStatus.Downloading,
                        errorMessage = null,
                        bytesDownloaded = bytesDownloaded,
                        totalBytes = totalBytes,
                        fileName = fileName,
                        fileIndex = fileIndex,
                        fileCount = fileCount,
                    )
                }
            }
            WorkInfo.State.CANCELLED,
            WorkInfo.State.FAILED -> {
                val message = info.outputData.getString("error_message") ?: "Download failed"
                _uiState.update {
                    it.copy(
                        status = DownloadStatus.Error,
                        errorMessage = message,
                        bytesDownloaded = bytesDownloaded,
                        totalBytes = totalBytes,
                        fileName = fileName,
                        fileIndex = fileIndex,
                        fileCount = fileCount,
                    )
                }
            }
        }
    }

    fun retryDownload() {
        val request = OneTimeWorkRequest.Builder(ModelDownloadWorker::class.java)
            .setBackoffCriteria(
                backoffPolicy = BackoffPolicy.EXPONENTIAL,
                backoffDelay = 10L,
                timeUnit = TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            ModelDownloadWorker.WORK_TAG,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
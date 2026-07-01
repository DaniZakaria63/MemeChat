package `fun`.walawe.memechat.presenter

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `fun`.walawe.memechat.model.DownloadStatus
import `fun`.walawe.memechat.model.DownloadUiState
import `fun`.walawe.memechat.service.DownloadServiceState
import `fun`.walawe.memechat.service.ModelDownloadService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadViewModel @Inject constructor(
    application: Application,
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(DownloadUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            DownloadServiceState.state.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun retryDownload() {
        DownloadServiceState.reset()
        val intent = Intent(getApplication(), ModelDownloadService::class.java)
        ContextCompat.startForegroundService(getApplication(), intent)
    }
}

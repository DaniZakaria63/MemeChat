package `fun`.walawe.memechat.presenter

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class DownloadViewModel: ViewModel() {
    private val _isDownloading = MutableStateFlow(false)
    val isDownloading = _isDownloading.asStateFlow()

    val errorState = MutableStateFlow<String?>(null)
}
package `fun`.walawe.memechat.presenter

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `fun`.walawe.memechat.model.DownloadUiState
import `fun`.walawe.memechat.service.DownloadServiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadViewModel @Inject constructor() : BaseViewModel() {
    private val _uiState = MutableStateFlow(DownloadUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            DownloadServiceState.state.collect { state ->
                _uiState.value = state
            }
        }
    }
}

package `fun`.walawe.memechat.presenter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `fun`.walawe.memelm.MemeLMModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(

): ViewModel() {
    init {
        viewModelScope.launch(Dispatchers.Default) {
        }
    }
}
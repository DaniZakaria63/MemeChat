package `fun`.walawe.memechat.presenter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import timber.log.Timber

abstract class BaseViewModel : ViewModel() {
    val errorState = MutableStateFlow<String?>(null)

    protected val safeViewModelScope: CoroutineScope
        get() = CoroutineScope(
            viewModelScope.coroutineContext +
                    CoroutineExceptionHandler { _, throwable ->
                        Timber.e(throwable, "Error in ViewModel coroutine")
                        errorState.value = throwable.message
                    }
        )

    fun clearError() {
        errorState.value = null
    }
}
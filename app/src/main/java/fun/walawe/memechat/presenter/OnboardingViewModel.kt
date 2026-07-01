package `fun`.walawe.memechat.presenter

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `fun`.walawe.memechat.analyzer.CompatibilityResult
import `fun`.walawe.memechat.analyzer.ConnectionSpeedChecker
import `fun`.walawe.memechat.analyzer.DeviceCompatibilityChecker
import `fun`.walawe.memechat.data.UserPreferences
import `fun`.walawe.memechat.model.OnboardingCheckResult
import `fun`.walawe.memechat.model.OnboardingState
import `fun`.walawe.memechat.model.SpeedResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val deviceCompatibilityChecker: DeviceCompatibilityChecker,
    private val connectionSpeedChecker: ConnectionSpeedChecker,
    private val userPreferences: UserPreferences,
) : BaseViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state = _state.asStateFlow()

    init {
        if (userPreferences.isOnboardingCompleted()) {
            _state.update { it.copy(onboardingCompleted = true) }
        }
    }

    fun onPageChanged(page: Int) {
        _state.update { it.copy(currentPage = page) }
        if (page == CHECK_PAGE_INDEX && _state.value.storageCheck == OnboardingCheckResult.Pending) {
            runChecks()
        }
    }

    fun nextPage() {
        val current = _state.value.currentPage
        if (current < 2) {
            _state.update { it.copy(currentPage = current + 1) }
            if (current + 1 == CHECK_PAGE_INDEX && _state.value.storageCheck == OnboardingCheckResult.Pending) {
                runChecks()
            }
        }
    }

    fun getStarted() {
        completeOnboarding()
    }

    private fun completeOnboarding() {
        userPreferences.setOnboardingCompleted(true)
        _state.update { it.copy(onboardingCompleted = true) }
    }

    private fun runChecks() {
        viewModelScope.launch {
            checkStorage()
            checkRam()
            checkSpeed()
        }
    }

    private suspend fun checkStorage() {
        _state.update { it.copy(storageCheck = OnboardingCheckResult.Running) }
        delay(500)
        val result = deviceCompatibilityChecker.runChecks()
        when (result) {
            is CompatibilityResult.Ok,
            is CompatibilityResult.InsufficientRam,
            is CompatibilityResult.InsufficientFreeRam -> {
                _state.update {
                    it.copy(storageCheck = OnboardingCheckResult.Passed("Storage is sufficient"))
                }
            }
            is CompatibilityResult.InsufficientStorage -> {
                _state.update {
                    it.copy(storageCheck = OnboardingCheckResult.Failed(result.message))
                }
                Timber.w("Storage check failed: ${result.message}")
            }
        }
    }

    private suspend fun checkRam() {
        val storagePassed = _state.value.storageCheck is OnboardingCheckResult.Passed
        if (!storagePassed) return

        _state.update { it.copy(ramCheck = OnboardingCheckResult.Running) }
        delay(500)
        val result = deviceCompatibilityChecker.checkFreeRam()
        when (result) {
            is CompatibilityResult.InsufficientFreeRam -> {
                _state.update {
                    it.copy(ramCheck = OnboardingCheckResult.Failed(result.message))
                }
                Timber.w("Free RAM check failed: ${result.message}")
            }
            else -> {
                _state.update {
                    it.copy(ramCheck = OnboardingCheckResult.Passed("Free RAM is sufficient"))
                }
            }
        }
    }

    private suspend fun checkSpeed() {
        val ramPassed = _state.value.ramCheck is OnboardingCheckResult.Passed
        if (!ramPassed) return

        _state.update { it.copy(speedCheck = SpeedResult.Checking) }
        val result = withContext(Dispatchers.IO) {
            connectionSpeedChecker.measureSpeed()
        }
        _state.update { it.copy(speedCheck = result) }
    }

    companion object {
        private const val CHECK_PAGE_INDEX = 2
    }
}

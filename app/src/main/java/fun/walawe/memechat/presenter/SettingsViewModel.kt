package `fun`.walawe.memechat.presenter

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import `fun`.walawe.memechat.data.ModelRepository
import `fun`.walawe.memechat.model.SettingsUiState
import `fun`.walawe.memelm.HardwareAccelerationChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val modelRepository: ModelRepository,
    private val accelerationChecker: HardwareAccelerationChecker,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val deviceInfo = buildDeviceInfo()
            val backendInfo = buildBackendInfo()
            val modelInfo = buildModelInfo()
            val cacheInfo = buildCacheInfo()

            _uiState.update {
                it.copy(
                    deviceInfo = deviceInfo,
                    backendInfo = backendInfo,
                    modelInfo = modelInfo,
                    cacheInfo = cacheInfo,
                )
            }
        }
    }

    fun clearModelAndCache() {
        modelRepository.clearCache()
        refresh()
    }

    private fun buildDeviceInfo(): List<Pair<String, String>> {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val totalRam = formatBytes(memInfo.totalMem)
        return listOf(
            "Device" to Build.MODEL,
            "Android" to "Android ${Build.VERSION.RELEASE}",
            "Total RAM" to totalRam,
        )
    }

    private fun buildBackendInfo(): List<Pair<String, String>> {
        val report = accelerationChecker.getBestAcceleration()
        val neonSupported = Build.SUPPORTED_ABIS.any { it.contains("arm", ignoreCase = true) }
        return listOf(
            "Backend" to report.type.name,
            "Threads" to Runtime.getRuntime().availableProcessors().toString(),
            "Vulkan" to if (report.vulkanSupported) "Enabled" else "Disabled",
            "Neon" to if (neonSupported) "Enabled" else "Disabled",
        )
    }

    private suspend fun buildModelInfo(): List<Pair<String, String>> {
        return listOf(
        )
    }

    private fun buildCacheInfo(): List<Pair<String, String>> {
        return listOf(
            "KV Cache" to "N/A",
            "Memory" to "N/A",
            "Tokens" to "N/A",
        )
    }
/*

    private suspend fun loadModelDescriptor(): ModelDescriptor? {
        val cached = modelRepository.getCachedModel() ?: return null
        val modelPath = modelRepository.resolveModelPath(cached)
        return modelRepository.validateAndDescribe(modelPath).getOrNull()
    }
*/

    private fun formatBytes(bytes: Long): String {
        val gb = 1024.0 * 1024 * 1024
        val mb = 1024.0 * 1024
        return if (bytes >= gb) {
            val value = (bytes / gb * 10).roundToInt() / 10.0
            "${value} GB"
        } else {
            val value = (bytes / mb * 10).roundToInt() / 10.0
            "${value} MB"
        }
    }
}

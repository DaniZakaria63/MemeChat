package `fun`.walawe.memechat.presenter

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import `fun`.walawe.memechat.gguf.GgufQuantReader
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import `fun`.walawe.memechat.data.ModelRepository
import `fun`.walawe.memechat.model.SettingsUiState
import `fun`.walawe.memelm.HardwareAccelerationChecker
import `fun`.walawe.modelpull.model.CacheKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
            val deviceInfo  = buildDeviceInfo()
            val backendInfo = buildBackendInfo()
            val modelInfo   = buildModelInfo()

            _uiState.update {
                it.copy(
                    deviceInfo  = deviceInfo,
                    backendInfo = backendInfo,
                    modelInfo   = modelInfo,
                )
            }
        }
    }

    fun clearModelAndCache() {
        modelRepository.clearCache()
        refresh()
    }

    private fun buildDeviceInfo(): List<Pair<String, String>> {
        val am      = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

        // Primary ABI is index 0
        val architecture = Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown ABI"

        // Processor name from /proc/cpuinfo
        val processor = readCpuInfo()

        // Internal storage via StatFs on the data partition
        val internalStat   = StatFs(Environment.getDataDirectory().path)
        val totalStorage   = internalStat.blockCountLong * internalStat.blockSizeLong
        val freeStorage    = internalStat.availableBlocksLong * internalStat.blockSizeLong
        val usedStorage    = totalStorage - freeStorage

        // App-private storage (code_cache + files)
        val appUsed = (context.filesDir.parentFile?.walkBottomUp()
            ?.filter { it.isFile }
            ?.sumOf { it.length() } ?: 0L)

        return listOf(
            "Device"        to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Android"       to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Processor"     to processor,
            "Architecture"  to architecture,
            "Total RAM"     to formatBytes(memInfo.totalMem),
            "Available RAM" to formatBytes(memInfo.availMem),
            "Total Storage" to formatBytes(totalStorage),
            "Used Storage"  to "${formatBytes(usedStorage)} / ${formatBytes(totalStorage)}",
            "App Storage"   to formatBytes(appUsed),
        )
    }

    private fun readCpuInfo(): String {
        return try {
            File("/proc/cpuinfo")
                .readLines()
                .firstOrNull { it.startsWith("Hardware") || it.startsWith("Processor") }
                ?.substringAfter(":")
                ?.trim()
                ?: Build.HARDWARE
        } catch (e: Exception) {
            Build.HARDWARE
        }
    }

    private fun buildBackendInfo(): List<Pair<String, String>> {
        val report         = accelerationChecker.getBestAcceleration()
        val totalCores     = Runtime.getRuntime().availableProcessors()
        // Mirrors what LLMInference.cpp computes: max(2, min(4, cores - 2))
        val modelThreads   = totalCores.coerceAtLeast(2).coerceAtMost(4)
            .let { maxOf(2, minOf(4, totalCores - 2)) }

        // GPU renderer string — available via OpenGL ES on API 21+
        val gpuRenderer    = readGpuRenderer()

        // CPU feature flags from /proc/cpuinfo
        val cpuFeatures    = readCpuFeatures()
        val hasNeon        = cpuFeatures.contains("neon") || cpuFeatures.contains("asimd")
        val hasDotProd     = cpuFeatures.contains("dotprod") || cpuFeatures.contains("asimddp")
        val hasFp16        = cpuFeatures.contains("fp16") || cpuFeatures.contains("fphp")
        val hasI8mm        = cpuFeatures.contains("i8mm")
        val hasSve         = cpuFeatures.contains("sve")

        return listOf(
            "Active Backend"    to report.type.name,
            "Vulkan"            to if (report.vulkanSupported) "Supported" else "Not supported",
            "GPU"               to gpuRenderer,
            "Total CPU Cores"   to totalCores.toString(),
            "Threads (Model)"   to modelThreads.toString(),
            "NEON / ASIMD"      to if (hasNeon) "✓" else "✗",
            "FP16"              to if (hasFp16) "✓" else "✗",
            "DotProd"           to if (hasDotProd) "✓" else "✗",
            "I8MM"              to if (hasI8mm) "✓" else "✗",
            "SVE"               to if (hasSve) "✓" else "✗",
        )
    }

    private fun readGpuRenderer(): String {
        return try {
            accelerationChecker.getBestAcceleration().nnapiAcceleratorName
                .takeIf { it?.isNotBlank() == true } ?: Build.HARDWARE
        } catch (e: Exception) {
            Build.HARDWARE
        }
    }

    private fun readCpuFeatures(): String {
        return try {
            File("/proc/cpuinfo")
                .readLines()
                .firstOrNull { it.startsWith("Features") }
                ?.substringAfter(":")
                ?.trim()
                ?.lowercase() ?: ""
        } catch (e: Exception) { "" }
    }

    private suspend fun buildModelInfo(): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val modelPath  = modelRepository.getCachedModel(CacheKey.Model).getOrNull()
            val mmprojPath = modelRepository.getCachedModel(CacheKey.MMPROJ).getOrNull()
            val embedPath  = modelRepository.getCachedModel(CacheKey.Embedding).getOrNull()

            val modelFile  = modelPath?.let { File(it) }
            val mmprojFile = mmprojPath?.let { File(it) }
            val embedFile  = embedPath?.let { File(it) }

            val modelName   = modelFile?.nameWithoutExtension ?: "Not loaded"
            val quant       = GgufQuantReader.readFileType(modelPath) ?: "Unknown"
            val mmprojName  = mmprojFile?.nameWithoutExtension ?: "Not loaded"
            val mmprojQuant = GgufQuantReader.readFileType(mmprojPath) ?: "Unknown"
            val embedName   = embedFile?.nameWithoutExtension ?: "Not loaded"
            val embedQuant  = GgufQuantReader.readFileType(embedPath) ?: "Unknown"

            // App process memory usage
            val runtime    = Runtime.getRuntime()
            val appMemUsed = runtime.totalMemory() - runtime.freeMemory()

            val modelSize  = modelFile?.length() ?: 0L
            val mmprojSize = mmprojFile?.length() ?: 0L
            val embedSize  = embedFile?.length() ?: 0L
            val totalModelMem = modelSize + mmprojSize + embedSize

            buildList {
                // ── LLM ──
                add("Model Name"     to modelName)
                add("Type"           to "Multimodal (VLM)")
                add("Quantization"   to quant)
                add("Model Size"     to formatBytes(modelSize))
                add("Context Size"   to "4096 tokens")

                // ── MMProj ──
                add("MMProj Note"    to "Vision encoder projection — maps image patches to LLM embedding space")
                add("MMProj Quant"   to mmprojQuant)
                add("MMProj Size"    to formatBytes(mmprojSize))

                // ── Embedding ──
                add("Embedding Name" to embedName)
                add("Embedding Quant" to embedQuant)
                add("Embedding Size" to formatBytes(embedSize))

                // ── Memory ──
                add("Model Memory"   to formatBytes(totalModelMem))
                add("App Heap Used"  to formatBytes(appMemUsed))
                add("App Max Heap"   to formatBytes(runtime.maxMemory()))
            }
        }

    // ── Formatting ────────────────────────────────────────────────────────
    private fun formatBytes(bytes: Long): String {
        val gb = 1024.0 * 1024 * 1024
        val mb = 1024.0 * 1024
        return when {
            bytes <= 0  -> "N/A"
            bytes >= gb -> "${"%.1f".format(bytes / gb)} GB"
            else        -> "${"%.1f".format(bytes / mb)} MB"
        }
    }
}
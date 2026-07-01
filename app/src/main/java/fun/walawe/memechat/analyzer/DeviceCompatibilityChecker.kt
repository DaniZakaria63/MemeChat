package `fun`.walawe.memechat.analyzer

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceCompatibilityChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val requiredStorageBytes = 2L * 1024 * 1024 * 1024
    private val requiredTotalRamBytes = 4L * 1024 * 1024 * 1024
    private val requiredFreeRamBytes = 1L * 1024 * 1024 * 1024

    fun runChecks(): CompatibilityResult {
        return when (val storage = checkStorage()) {
            is StorageResult.Insufficient -> CompatibilityResult.InsufficientStorage(
                bytesFree = storage.bytesFree,
                bytesRequired = requiredStorageBytes,
                message = "Need ${formatBytes(requiredStorageBytes)} free, only ${formatBytes(storage.bytesFree)} available. Free up space and try again."
            )
            is StorageResult.Ok -> {
                when (val ram = checkRam()) {
                    is RamResult.Insufficient -> CompatibilityResult.InsufficientRam(
                        totalRamBytes = ram.totalRamBytes,
                        message = "This device has ${formatBytes(ram.totalRamBytes)} total RAM, which may not be enough to run the AI model smoothly."
                    )
                    is RamResult.Ok -> CompatibilityResult.Ok
                }
            }
        }
    }

    fun isStorageSufficient(): Boolean {
        return checkStorage() is StorageResult.Ok
    }

    fun checkFreeRam(): CompatibilityResult {
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return CompatibilityResult.Ok
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            return if (memoryInfo.availMem < requiredFreeRamBytes) {
                CompatibilityResult.InsufficientFreeRam(
                    availMemBytes = memoryInfo.availMem,
                    requiredBytes = requiredFreeRamBytes,
                    message = "Need ${formatBytes(requiredFreeRamBytes)} free RAM, only ${formatBytes(memoryInfo.availMem)} available."
                )
            } else {
                CompatibilityResult.Ok
            }
        } catch (e: Exception) {
            return CompatibilityResult.Ok
        }
    }

    private fun checkStorage(): StorageResult {
        try {
            val stat = StatFs(context.filesDir.parentFile?.absolutePath ?: return StorageResult.Ok)
            val bytesAvailable = stat.availableBytes
            return if (bytesAvailable < requiredStorageBytes) {
                StorageResult.Insufficient(bytesFree = bytesAvailable)
            } else {
                StorageResult.Ok
            }
        } catch (e: Exception) {
            return StorageResult.Ok
        }
    }

    private fun checkRam(): RamResult {
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return RamResult.Ok
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            return if (memoryInfo.totalMem < requiredTotalRamBytes) {
                RamResult.Insufficient(totalRamBytes = memoryInfo.totalMem)
            } else {
                RamResult.Ok
            }
        } catch (e: Exception) {
            return RamResult.Ok
        }
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes.toDouble() / (1024 * 1024)
        val gb = mb / 1024
        return if (gb >= 1.0) String.format("%.1f GB", gb)
        else String.format("%.0f MB", mb)
    }

    private sealed interface StorageResult {
        data object Ok : StorageResult
        data class Insufficient(val bytesFree: Long) : StorageResult
    }

    private sealed interface RamResult {
        data object Ok : RamResult
        data class Insufficient(val totalRamBytes: Long) : RamResult
    }
}

sealed interface CompatibilityResult {
    data object Ok : CompatibilityResult
    data class InsufficientStorage(
        val bytesFree: Long,
        val bytesRequired: Long,
        val message: String,
    ) : CompatibilityResult
    data class InsufficientRam(
        val totalRamBytes: Long,
        val message: String,
    ) : CompatibilityResult
    data class InsufficientFreeRam(
        val availMemBytes: Long,
        val requiredBytes: Long,
        val message: String,
    ) : CompatibilityResult
}

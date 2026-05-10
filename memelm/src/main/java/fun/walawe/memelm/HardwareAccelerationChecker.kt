package `fun`.walawe.memelm

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Detects the best available acceleration (TPU, GPU, or CPU) using runtime device checks.
 */
class HardwareAccelerationChecker(private val context: Context) {

    enum class AccelerationType {
        TPU,
        GPU,
        CPU,
    }

    data class AccelerationReport(
        val type: AccelerationType,
        val details: String,
        val nnapiAcceleratorName: String? = null,
        val vulkanSupported: Boolean = false,
        val glesVersion: String = "unknown",
    )

    fun getBestAcceleration(): AccelerationReport {
        val nnapiInfo = findNnapiAccelerator()
        if (nnapiInfo.isAvailable) {
            return AccelerationReport(
                type = AccelerationType.TPU,
                details = "NNAPI accelerator available",
                nnapiAcceleratorName = nnapiInfo.name,
                vulkanSupported = isVulkanSupported(),
                glesVersion = getGlEsVersionString(),
            )
        }

        if (isGpuUsable()) {
            return AccelerationReport(
                type = AccelerationType.GPU,
                details = "GPU supported via Vulkan or OpenGL ES",
                vulkanSupported = isVulkanSupported(),
                glesVersion = getGlEsVersionString(),
            )
        }

        return AccelerationReport(
            type = AccelerationType.CPU,
            details = "No TPU or GPU acceleration detected",
            vulkanSupported = isVulkanSupported(),
            glesVersion = getGlEsVersionString(),
        )
    }

    private fun isGpuUsable(): Boolean {
        return isVulkanSupported() || isOpenGlEs31Supported()
    }

    private fun isVulkanSupported(): Boolean {
        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL) ||
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
    }

    private fun isOpenGlEs31Supported(): Boolean {
        val glEsVersion = getGlEsVersion()
        return glEsVersion >= 0x00030001
    }

    private fun getGlEsVersion(): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.deviceConfigurationInfo.reqGlEsVersion
    }

    private fun getGlEsVersionString(): String {
        val version = getGlEsVersion()
        val major = version shr 16
        val minor = version and 0xffff
        return "$major.$minor"
    }

    private data class NnapiInfo(
        val isAvailable: Boolean,
        val name: String? = null,
    )

    private fun findNnapiAccelerator(): NnapiInfo {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return NnapiInfo(isAvailable = false)
        }

        return try {
            val nnClass = Class.forName("android.neuralnetworks.NeuralNetworks")
            val getDevices = nnClass.getMethod("getDevices")
            val devices = getDevices.invoke(null)
            val deviceList = when (devices) {
                is Array<*> -> devices.toList()
                is List<*> -> devices
                else -> emptyList()
            }
            val deviceClass = Class.forName("android.neuralnetworks.Device")
            val typeMethod = deviceClass.getMethod("getType")
            val nameMethod = deviceClass.getMethod("getName")
            val typeCpu = deviceClass.getField("TYPE_CPU").getInt(null)

            val accelerator = deviceList.firstOrNull { device ->
                val type = typeMethod.invoke(device) as Int
                type != typeCpu
            }

            if (accelerator != null) {
                val name = nameMethod.invoke(accelerator) as String
                NnapiInfo(isAvailable = true, name = name)
            } else {
                NnapiInfo(isAvailable = false)
            }
        } catch (_: Throwable) {
            NnapiInfo(isAvailable = false)
        }
    }
}


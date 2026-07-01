package `fun`.walawe.memechat.analyzer

import fr.bmartel.speedtest.SpeedTestReport
import fr.bmartel.speedtest.SpeedTestSocket
import fr.bmartel.speedtest.inter.ISpeedTestListener
import fr.bmartel.speedtest.model.SpeedTestError
import `fun`.walawe.memechat.model.SpeedResult
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class ConnectionSpeedChecker @Inject constructor() {

    suspend fun measureSpeed(): SpeedResult {
        for (url in TEST_URLS) {
            val result = testServer(url)
            if (result != SpeedResult.Unknown) return result
        }
        return SpeedResult.Unknown
    }

    private suspend fun testServer(url: String): SpeedResult = suspendCancellableCoroutine { continuation ->
        try {
            val client = SpeedTestSocket()
            client.setSocketTimeout(TIMEOUT_MS)

            client.addSpeedTestListener(object : ISpeedTestListener {
                override fun onCompletion(report: SpeedTestReport) {
                    val mbps = report.transferRateBit.toDouble() / 1_000_000.0
                    Timber.d("Speed test result: %.2f Mbps from %s".format(mbps, url))
                    val speed = when {
                        mbps >= 20f -> SpeedResult.Good
                        mbps >= 8f -> SpeedResult.Okay
                        mbps > 0f -> SpeedResult.Weak
                        else -> SpeedResult.Unknown
                    }
                    if (continuation.isActive) continuation.resume(speed)
                }

                override fun onProgress(percent: Float, report: SpeedTestReport) {}

                override fun onError(speedTestError: SpeedTestError, errorMessage: String?) {
                    Timber.w("Speed test error on %s: %s — %s".format(url, speedTestError, errorMessage))
                    if (continuation.isActive) continuation.resume(SpeedResult.Unknown)
                }
            })

            client.startFixedDownload(url, DURATION_MS)

            continuation.invokeOnCancellation {
                try { client.closeSocket() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Timber.e(e, "Speed test failed on %s".format(url))
            if (continuation.isActive) continuation.resume(SpeedResult.Unknown)
        }
    }

    companion object {
        private const val TIMEOUT_MS = 5000
        private const val DURATION_MS = 10_000
        private val TEST_URLS = listOf(
            "http://speedtest.tele2.net/5MB.zip",
            "http://speedtest.reliableservers.com/100MBtest.bin",
            "https://speed.cloudflare.com/__down?bytes=5000000",
        )
    }
}

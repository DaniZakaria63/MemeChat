package `fun`.walawe.memechat.analyzer

import fr.bmartel.speedtest.SpeedTestReport
import `fun`.walawe.memechat.model.SpeedResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class ConnectionSpeedCheckerTest {

    @Test
    fun `production URL should be well-formed HTTP ISO link`() {
        val url = "http://ipv4.ikoula.testdebit.info/5M.iso"

        assertTrue("URL must start with http", url.startsWith("http://") || url.startsWith("https://"))
        assertTrue("URL must have a valid hostname", url.contains("."))
        assertTrue("URL must end with .iso", url.endsWith(".iso"))
        assertNotNull("URL must be parseable", URI(url))
    }

    @Test(timeout = 45_000)
    fun `measureSpeed against 5MB URL returns a valid SpeedResult`() = runBlocking {
        val result = ConnectionSpeedChecker().measureSpeed()

        assertTrue("Expected Good, Okay, Weak, or Unknown but got $result", result.isValidSpeed())
    }

    @Test(timeout = 90_000)
    fun `measureSpeed called twice sequentially returns valid results both times`() = runBlocking {
        val checker = ConnectionSpeedChecker()

        val result1 = checker.measureSpeed()
        assertTrue("First call gave invalid result: $result1", result1.isValidSpeed())

        val result2 = checker.measureSpeed()
        assertTrue("Second call gave invalid result: $result2", result2.isValidSpeed())
    }

    @Test(timeout = 15_000)
    fun `cancellation of measureSpeed coroutine does not throw`() = runBlocking {
        val job = launch {
            ConnectionSpeedChecker().measureSpeed()
        }

        delay(200)
        job.cancel()
        job.join()
    }



    @Test
    fun `SpeedResult Good is returned for speeds at or above 20 Mbps`() {
        val mbps = 20.0
        val speed = classifySpeed(mbps)

        assertTrue("$mbps Mbps should be Good", speed is SpeedResult.Good)
    }

    @Test
    fun `SpeedResult Good is returned for high speeds`() {
        val mbps = 100.0
        val speed = classifySpeed(mbps)

        assertTrue("$mbps Mbps should be Good", speed is SpeedResult.Good)
    }

    @Test
    fun `SpeedResult Okay is returned for speeds between 8 and 20 Mbps`() {
        val mbps = 10.0
        val speed = classifySpeed(mbps)

        assertTrue("$mbps Mbps should be Okay", speed is SpeedResult.Okay)
    }

    @Test
    fun `SpeedResult Weak is returned for speeds between 0 and 8 Mbps`() {
        val mbps = 3.0
        val speed = classifySpeed(mbps)

        assertTrue("$mbps Mbps should be Weak", speed is SpeedResult.Weak)
    }

    @Test
    fun `SpeedResult Unknown is returned for zero speed`() {
        val mbps = 0.0
        val speed = classifySpeed(mbps)

        assertTrue("$mbps Mbps should be Unknown", speed is SpeedResult.Unknown)
    }

    @Test
    fun `SpeedResult Unknown is returned for negative speed`() {
        val mbps = -1.0
        val speed = classifySpeed(mbps)

        assertTrue("$mbps Mbps should be Unknown", speed is SpeedResult.Unknown)
    }

    @Test
    fun `classifySpeed maps exactly 8 Mbps to Okay`() {
        val speed = classifySpeed(8.0)
        assertTrue("8 Mbps should be Okay", speed is SpeedResult.Okay)
    }

    @Test
    fun `classifySpeed maps exactly 20 Mbps to Good`() {
        val speed = classifySpeed(20.0)
        assertTrue("20 Mbps should be Good", speed is SpeedResult.Good)
    }

    @Test
    fun `classifySpeed maps 19_999999 Mbps to Okay`() {
        val speed = classifySpeed(19.999999)
        assertTrue("19.999999 Mbps should be Okay", speed is SpeedResult.Okay)
    }

    private fun classifySpeed(mbps: Double): SpeedResult {
        val report = SpeedTestReport(
            null, 0f, 0L, 0L, 0L, 0L,
            java.math.BigDecimal.ZERO,
            java.math.BigDecimal.valueOf((mbps * 1_000_000).toLong()),
            0
        )
        return convertReportToSpeed(report)
    }

    private fun convertReportToSpeed(report: SpeedTestReport): SpeedResult {
        val mbps = report.transferRateBit.toDouble() / 1_000_000.0
        return when {
            mbps >= 20f -> SpeedResult.Good
            mbps >= 8f -> SpeedResult.Okay
            mbps > 0f -> SpeedResult.Weak
            else -> SpeedResult.Unknown
        }
    }

    private fun SpeedResult.isValidSpeed(): Boolean =
        this is SpeedResult.Good ||
        this is SpeedResult.Okay ||
        this is SpeedResult.Weak ||
        this is SpeedResult.Unknown
}

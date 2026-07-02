package `fun`.walawe.memechat.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import `fun`.walawe.memechat.MemeChatApp
import `fun`.walawe.memechat.model.DownloadStatus
import `fun`.walawe.memechat.model.DownloadUiState
import `fun`.walawe.modelpull.model.CacheKey
import `fun`.walawe.modelpull.model.ModelCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

@HiltAndroidTest
class ModelDownloadServiceTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    @ApplicationContext
    lateinit var context: Context

    @Inject
    lateinit var modelCache: ModelCache

    @Before
    fun setUp() {
        hiltRule.inject()
        createNotificationChannel()
        modelCache.clearModel(CacheKey.Model)
        FakeModelDownloader.setMode(FakeModelDownloader.Mode.SUCCESS)
        DownloadServiceState.reset()
    }

    @After
    fun tearDown() {
        DownloadServiceState.reset()
    }

    @Test
    fun testDownloadSuccess_setsCompletedState() = runBlocking {
        FakeModelDownloader.setMode(FakeModelDownloader.Mode.SUCCESS)

        startService()

        val state = awaitTerminalState()
        assertEquals(DownloadStatus.Completed, state.status)
    }

    @Test
    fun testDownloadSuccess_populatesCache() = runBlocking {
        FakeModelDownloader.setMode(FakeModelDownloader.Mode.SUCCESS)

        startService()

        awaitTerminalState()
        assertNotNull(modelCache.getModel(CacheKey.Model))
        assertNotNull(modelCache.getModel(CacheKey.MMPROJ))
        assertNotNull(modelCache.getModel(CacheKey.Embedding))
    }

    @Test
    fun testDownloadValidation_setsErrorState() = runBlocking {
        FakeModelDownloader.setMode(FakeModelDownloader.Mode.VALIDATION)

        startService()

        val state = awaitTerminalState()
        assertEquals(DownloadStatus.Error, state.status)
    }

    @Test
    fun testDownloadError_setsErrorState() = runBlocking {
        FakeModelDownloader.setMode(FakeModelDownloader.Mode.ERROR)

        startService()

        val state = awaitTerminalState()
        assertEquals(DownloadStatus.Error, state.status)
        assertTrue(state.errorMessage?.isNotBlank() ?: false)
    }

    private fun startService() {
        val intent = Intent(context, ModelDownloadService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    private suspend fun awaitTerminalState(timeoutMs: Long = 10_000L): DownloadUiState {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val state = DownloadServiceState.state.value
            if (state.status == DownloadStatus.Completed || state.status == DownloadStatus.Error) {
                return state
            }
            delay(100)
        }
        return DownloadServiceState.state.value
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            MemeChatApp.DOWNLOAD_CHANNEL_ID,
            "Model Download",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progress of AI model downloads"
            setShowBadge(false)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}

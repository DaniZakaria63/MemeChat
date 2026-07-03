package `fun`.walawe.memechat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.HiltAndroidApp
import `fun`.walawe.constant.FIREBASE_ANALYTICS_KEY_FETCH_ERROR
import `fun`.walawe.constant.ModelUrlProvider
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class MemeChatApp : Application() {

    @Inject lateinit var modelUrlProvider: ModelUrlProvider

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        val rcExceptionHandler = CoroutineExceptionHandler { _, throwable ->
            Timber.e(throwable, "Remote Config fetch failed, using defaults")
            FirebaseAnalytics.getInstance(this@MemeChatApp)
                .logEvent(
                    FIREBASE_ANALYTICS_KEY_FETCH_ERROR,
                    Bundle().apply { putString("error", throwable.message ?: throwable.javaClass.simpleName) }
                )
        }

        applicationScope.launch(rcExceptionHandler) {
            withTimeout(15_000L) {
                modelUrlProvider.fetch()
            }
        }

        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                "Model Download",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress of AI model downloads"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val DOWNLOAD_CHANNEL_ID = "download_progress"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_NAVIGATE_TO_DOWNLOAD = "navigate_to_download"
    }
}

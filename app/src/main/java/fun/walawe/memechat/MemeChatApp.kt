package `fun`.walawe.memechat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.HiltAndroidApp
import `fun`.walawe.constant.FIREBASE_ANALYTICS_KEY_FETCH_ERROR
import `fun`.walawe.constant.ModelUrlProvider
import `fun`.walawe.memechat.worker.ModelDownloadWorker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class MemeChatApp : Application(), Configuration.Provider{

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var modelUrlProvider: ModelUrlProvider

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration:  Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(Log.DEBUG)
            .build()

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
        initializeModelDownload()
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

    fun initializeModelDownload() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val modelDownloadRequest = OneTimeWorkRequest.Builder(ModelDownloadWorker::class.java)
            .setConstraints(constraints)
            .setBackoffCriteria(
                backoffPolicy = BackoffPolicy.EXPONENTIAL,
                backoffDelay = 30L,
                timeUnit = TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            uniqueWorkName = ModelDownloadWorker.WORK_TAG,
            existingWorkPolicy = ExistingWorkPolicy.KEEP,
            request = modelDownloadRequest
        )
        Timber.d("Model download worker enqueued")
    }

    companion object {
        const val DOWNLOAD_CHANNEL_ID = "download_progress"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_NAVIGATE_TO_DOWNLOAD = "navigate_to_download"
    }
}

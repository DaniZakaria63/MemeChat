package `fun`.walawe.memechat

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import `fun`.walawe.memechat.worker.ModelDownloadWorker
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class MemeChatApp : Application(), Configuration.Provider{

    @Inject lateinit var workerFactory: HiltWorkerFactory
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

        initializeModelDownload()
    }

    fun initializeModelDownload() {
        val modelDownloadRequest = OneTimeWorkRequest.Builder(ModelDownloadWorker::class.java)
            .setBackoffCriteria(
                backoffPolicy = BackoffPolicy.EXPONENTIAL,
                backoffDelay = 10L,
                timeUnit = TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            uniqueWorkName = ModelDownloadWorker.WORK_TAG,
            existingWorkPolicy = androidx.work.ExistingWorkPolicy.KEEP,
            request = modelDownloadRequest
        )
        Timber.d("Model download worker enqueued")
    }

}

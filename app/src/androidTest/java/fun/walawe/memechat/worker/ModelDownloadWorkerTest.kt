package `fun`.walawe.memechat.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import `fun`.walawe.modelpull.model.CacheModel
import `fun`.walawe.modelpull.model.ModelCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.inject.Inject

@HiltAndroidTest
class ModelDownloadWorkerTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    @ApplicationContext
    lateinit var context: Context

    lateinit var modelCache: ModelCache

    @Before
    fun setUp() {
        hiltRule.inject()
        modelCache = ModelCache()
        modelCache.clearModel()
        FakeModelDownloader.setMode(FakeModelDownloader.Mode.SUCCESS)
        deleteModelFile()
    }

    @Test
    fun testDownloadInformation() = runBlocking {
        FakeModelDownloader.setMode(FakeModelDownloader.Mode.SUCCESS)

        val result = buildWorker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        val output = (result as ListenableWorker.Result.Success).outputData
        assertEquals("Model downloaded successfully", output.getString("info"))
        assertNotNull(modelCache.getModel())
    }

    @Test
    fun testDownloadValidation() = runBlocking {
        FakeModelDownloader.setMode(FakeModelDownloader.Mode.VALIDATION)

        val result = buildWorker().doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        val output = (result as ListenableWorker.Result.Failure).outputData
        assertEquals("IllegalURILinkIdException", output.getString("error_type"))
    }

    @Test
    fun testDownloadWarning() = runBlocking {
        modelCache.setModel(createCachedModel())

        val result = buildWorker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        val output = (result as ListenableWorker.Result.Success).outputData
        assertEquals("Model already exists", output.getString("warning"))
    }

    @Test
    fun testDownloadError() = runBlocking {
        FakeModelDownloader.setMode(FakeModelDownloader.Mode.ERROR)

        val result = buildWorker(runAttemptCount = 3).doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        val output = (result as ListenableWorker.Result.Failure).outputData
        assertEquals("BadRequestException", output.getString("error_type"))
    }

    @Test
    fun testDownloadSuccess() = runBlocking {
        FakeModelDownloader.setMode(FakeModelDownloader.Mode.SUCCESS)

        val result = buildWorker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        val modelFile = File(context.getDir("ml_models", Context.MODE_PRIVATE), "model.tflite")
        assertTrue(modelFile.exists())
        assertNotNull(modelCache.getModel())
    }

    private fun buildWorker(runAttemptCount: Int = 0): ModelDownloadWorker {
        val workerFactory = object : WorkerFactory() {
            @Suppress("NULLABLE_TYPE_PARAMETER_AGAINST_NOT_NULL_TYPE_PARAMETER")
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker? {
                return ModelDownloadWorker(
                    appContext,
                    workerParameters,
                    FakeModelDownloader(context),
                    modelCache
                )
            }
        }
        return TestListenableWorkerBuilder<ModelDownloadWorker>(context)
            .setWorkerFactory(workerFactory)
            .setRunAttemptCount(runAttemptCount)
            .build()
    }

    private fun createCachedModel(): CacheModel {
        val modelDir = context.getDir("ml_models", Context.MODE_PRIVATE)
        val file = File(modelDir, "model.tflite")
        file.parentFile?.mkdirs()
        file.writeText("cached model")
        return CacheModel(
            modelId = "cached-id",
            displayName = file.name,
            localFileDir = modelDir.absolutePath,
            localFileName = file.name,
            fileCache = file,
            downloadedAt = System.currentTimeMillis()
        )
    }

    private fun deleteModelFile() {
        val modelDir = context.getDir("ml_models", Context.MODE_PRIVATE)
        File(modelDir, "model.tflite").delete()
    }
}
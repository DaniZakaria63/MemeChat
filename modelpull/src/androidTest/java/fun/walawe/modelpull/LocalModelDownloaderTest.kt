package `fun`.walawe.modelpull

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import `fun`.walawe.constant.MODEL_DIR_NAME
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import `fun`.walawe.modelpull.model.BadRequestException
import `fun`.walawe.modelpull.service.LocalModelDownloader
import java.io.File
import javax.inject.Inject

@HiltAndroidTest
class LocalModelDownloaderTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var modelDownloader: LocalModelDownloader

    @Inject
    @ApplicationContext
    lateinit var context: Context

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun testGetModelSuccess() = runBlocking {
        val uri = "http://192.168.0.103:45639/dummy.txt"
        val result = modelDownloader.getModel(uri) { _, _ -> }
        assertTrue("Expected success", result.isSuccess)
        val model = result.getOrNull()
        assertNotNull("Model should not be null", model)
        assertTrue("Local file should exist", File(model!!.localFileDir, model.localFileName).exists())
    }

    @Test
    fun testGetModelError() = runBlocking {
        val uri = "http://invalid.url/model"
        val result = modelDownloader.getModel(uri) { _, _ -> }
        assertTrue("Expected failure", result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull("Exception should not be null", exception)
        assertTrue("Expected BadRequestException", exception is BadRequestException)
    }

    @Test
    fun testGetModelWarning(): Unit = runBlocking {
        val modelDir = context.getDir(MODEL_DIR_NAME, Context.MODE_PRIVATE)
        val localFile = File(modelDir, "model.tflite")
        localFile.createNewFile()

        val uri = "http://example.com/model"
        val result = modelDownloader.getModel(uri) { _, _ -> }
        assertTrue("Expected success (model exists)", result.isSuccess)
        val model = result.getOrNull()
        assertNotNull("Model should not be null", model)
        assertEquals("File should still exist", localFile, model!!.fileCache)

        localFile.delete()
    }
}
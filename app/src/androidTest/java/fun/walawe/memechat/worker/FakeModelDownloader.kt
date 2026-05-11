package `fun`.walawe.memechat.worker

import android.content.Context
import `fun`.walawe.modelpull.model.BadRequestException
import `fun`.walawe.modelpull.model.CachePaligemmaModel
import `fun`.walawe.modelpull.service.ModelDownloader
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class FakeModelDownloader(
    private val context: Context
) : ModelDownloader {
    override suspend fun getModel(
        uri: String,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): Result<CachePaligemmaModel> {
        onProgress(1L, 1L)
        return when (mode.get()) {
            Mode.SUCCESS -> Result.success(createModel("model.tflite"))
            Mode.VALIDATION -> Result.success(createModel(""))
            Mode.ERROR -> Result.failure(BadRequestException("Failed to download model"))
        }
    }

    private fun createModel(fileName: String): CachePaligemmaModel {
        val modelDir = context.getDir("ml_models", Context.MODE_PRIVATE)
        val file = File(modelDir, fileName.ifBlank { "model.tflite" })
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            file.writeText("fake model data")
        }

        return CachePaligemmaModel(
            modelId = "fake-id",
            displayName = file.name,
            localFileDir = modelDir.absolutePath,
            localFileName = fileName,
            fileCache = file,
            downloadedAt = System.currentTimeMillis()
        )
    }

    enum class Mode {
        SUCCESS,
        VALIDATION,
        ERROR,
    }

    companion object {
        private val mode = AtomicReference(Mode.SUCCESS)

        fun setMode(newMode: Mode) {
            mode.set(newMode)
        }
    }
}

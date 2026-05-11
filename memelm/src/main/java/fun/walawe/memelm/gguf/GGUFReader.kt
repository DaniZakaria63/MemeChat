package `fun`.walawe.memelm.gguf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GGUFReader {
    companion object {
        init {
            System.loadLibrary("ggufreader")
        }
    }

    private var nativeHandle: Long = 0L

    suspend fun load(modelPath: String) =
        withContext(Dispatchers.IO) { nativeHandle = getGGUFContextNativeHandle(modelPath) }

    fun getContextSize(): Long? {
        assert(nativeHandle != 0L) { "Use GGUFReader.load() to initialize the reader" }
        val contextSize = getContextSize(nativeHandle)
        return if (contextSize == -1L) {
            null
        } else {
            contextSize
        }
    }

    fun getChatTemplate(): String? {
        assert(nativeHandle != 0L) { "Use GGUFReader.load() to initialize the reader" }
        val chatTemplate = getChatTemplate(nativeHandle)
        return chatTemplate.ifEmpty { null }
    }

    fun getModelBasename(): String? {
        assert(nativeHandle != 0L) { "Use GGUFReader.load() to initialize the reader" }
        val basename = getModelBasename(nativeHandle)
        return basename.ifEmpty { null }
    }

    fun isExpectedQwenModel(expectedBasename: String): Boolean {
        val basename = getModelBasename() ?: return false
        return basename == expectedBasename || basename == "$expectedBasename.gguf"
    }

    /** Returns the native handle (pointer to gguf_context created on the native side) */
    private external fun getGGUFContextNativeHandle(modelPath: String): Long

    /** Read the context size (in no. of tokens) from the GGUF file, given the native handle */
    private external fun getContextSize(nativeHandle: Long): Long

    /** Read the chat template from the GGUF file, given the native handle */
    private external fun getChatTemplate(nativeHandle: Long): String

    /** Read the model basename from the GGUF file, given the native handle */
    private external fun getModelBasename(nativeHandle: Long): String
}
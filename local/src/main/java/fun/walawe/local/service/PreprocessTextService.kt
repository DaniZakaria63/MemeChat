package `fun`.walawe.local.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import `fun`.walawe.constant.MODEL_FILENAME_OPENNLP_SENTENCE
import opennlp.tools.sentdetect.SentenceDetectorME
import opennlp.tools.sentdetect.SentenceModel
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreprocessTextService @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val sentenceDetector: SentenceDetectorME by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SentenceDetectorME(loadAssetModel(MODEL_FILENAME_OPENNLP_SENTENCE) { SentenceModel(it) })
    }

    data class PreprocessedResult(
        val original: String,
        val normalized: String,
        val sentences: List<String>,
    )

    suspend fun preprocess(text: String): PreprocessedResult {
        val normalized = normalizeText(text)
        val sentences = detectSentences(normalized)
        return PreprocessedResult(
            original = text,
            normalized = normalized,
            sentences = sentences.toList(),
        )
    }

    private fun normalizeText(text: String): String {
        val stripped = text.trim()
            .replace(Regex("https?://\\S+"), "")           // remove URLs
            .replace(Regex("[\\w.+-]+@[\\w.-]+"), "")       // remove emails
            .replace(Regex("&#\\d+;|#\\w+"), "")            // remove HTML entities + hashtags

        val filtered = buildString {
            var i = 0
            while (i < stripped.length) {
                val cp = stripped.codePointAt(i)
                i += Character.charCount(cp)
                if (!isEmojiCodePoint(cp)) appendCodePoint(cp)
            }
        }
        return filtered
            .replace(Regex("\\s+"), " ")
            .let { if (it.length > 2000) it.take(2000) else it }
    }

    private fun isEmojiCodePoint(cp: Int): Boolean = cp in 0x1F600..0x1F64F ||
            cp in 0x1F300..0x1F5FF ||
            cp in 0x1F680..0x1F6FF ||
            cp in 0x1F1E0..0x1F1FF ||
            cp in 0x2600..0x26FF ||
            cp in 0x2700..0x27BF

    private fun detectSentences(text: String): Array<String> {
        return try {
            sentenceDetector.sentDetect(text)
        } catch (e: Exception) {
            text.split(Regex("(?<=[.!?])\\s+(?=[A-Z\"'(\\[{])"))
                .filter { it.isNotBlank() }
                .toTypedArray()
        }
    }

    private fun <T> loadAssetModel(filename: String, factory: (InputStream) -> T): T {
        val file = File(context.filesDir, filename)
        if (!file.exists()) {
            context.assets.open(filename).use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
        }
        return file.inputStream().use { factory(it) }
    }
}

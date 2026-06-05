package `fun`.walawe.memechat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val MAX_DIM = 224
        const val WEBP_QUALITY = 80
        const val IMAGES_DIR = "images"
    }

    private val root: File = File(context.filesDir, IMAGES_DIR).apply { mkdirs() }

    fun folderFor(conversationId: String): File =
        File(root, conversationId).apply { mkdirs() }

    suspend fun copyToInternal(
        src: Uri,
        conversationId: String,
        messageId: String,
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val original = context.contentResolver.openInputStream(src)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return@runCatching null

            val resized = resize(original, MAX_DIM)
            if (resized !== original) original.recycle()

            val out = File(folderFor(conversationId), "$messageId.webp")
            FileOutputStream(out).use { fos ->
                resized.compress(Bitmap.CompressFormat.WEBP, WEBP_QUALITY, fos)
            }
            resized.recycle()
            out.absolutePath
        }.getOrNull()
    }

    fun deleteConversationFolder(conversationId: String): Boolean =
        File(root, conversationId).deleteRecursively()

    fun delete(path: String?): Int {
        if (path.isNullOrBlank()) return 0
        return if (File(path).delete()) 1 else 0
    }

    fun deleteAll(): Boolean = root.deleteRecursively()

    suspend fun sweepOrphans(validConversationIds: Set<String>) = withContext(Dispatchers.IO) {
        val folders = root.listFiles() ?: return@withContext
        for (folder in folders) {
            if (folder.isDirectory && folder.name !in validConversationIds) {
                folder.deleteRecursively()
            }
        }
    }

    private fun resize(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap
        val scale = maxDim.toFloat() / maxOf(w, h)
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}

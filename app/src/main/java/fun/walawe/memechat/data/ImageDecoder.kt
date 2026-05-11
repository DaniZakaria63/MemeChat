package `fun`.walawe.memechat.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageDecoder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun decode(uri: Uri): Bitmap {
        val inputStream: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Unable to open image stream")
        return inputStream.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalArgumentException("Unable to decode image")
    }
}


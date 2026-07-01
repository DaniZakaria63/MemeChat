package `fun`.walawe.memechat.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import `fun`.walawe.memechat.MainActivity
import `fun`.walawe.memechat.MemeChatApp

class DownloadNotificationManager(private val context: Context) {

    fun buildForegroundNotification(
        fileName: String,
        fileIndex: Int,
        fileCount: Int,
        bytesDownloaded: Long,
        totalBytes: Long,
    ): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MemeChatApp.EXTRA_NAVIGATE_TO_DOWNLOAD, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val displayName = if (fileName.isNotBlank()) fileName else "Downloading AI Model"
        val percentage = if (totalBytes > 0L) (bytesDownloaded * 100 / totalBytes).toInt() else 0
        val fileInfo = if (fileCount > 0) "File $fileIndex of $fileCount" else "Preparing..."

        val text = if (totalBytes > 0L) {
            "$fileInfo · $percentage% · $displayName"
        } else {
            "$fileInfo · $displayName"
        }

        return NotificationCompat.Builder(context, MemeChatApp.DOWNLOAD_CHANNEL_ID)
            .setContentTitle("Downloading AI Model")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setColor(Color.parseColor(NOTIFICATION_COLOR))
            .setColorized(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (totalBytes > 0L) {
                    setProgress(totalBytes.toInt(), bytesDownloaded.toInt(), false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()
    }

    fun buildCompletionNotification(): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, MemeChatApp.DOWNLOAD_CHANNEL_ID)
            .setContentTitle("Download Complete")
            .setContentText("All AI models are ready. Start chatting!")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setColor(Color.parseColor(NOTIFICATION_COLOR))
            .setColorized(true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
    }

    fun buildErrorNotification(errorMessage: String): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MemeChatApp.EXTRA_NAVIGATE_TO_DOWNLOAD, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, MemeChatApp.DOWNLOAD_CHANNEL_ID)
            .setContentTitle("Download Failed")
            .setContentText(errorMessage)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setColor(Color.parseColor(NOTIFICATION_COLOR))
            .setColorized(true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_COLOR = "#00685E"
    }
}

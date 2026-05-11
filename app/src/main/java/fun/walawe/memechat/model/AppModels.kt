package `fun`.walawe.memechat.model

data class ModelDescriptor(
    val name: String,
    val quantization: String,
    val fileSizeBytes: Long,
    val path: String,
    val contextLength: Long?,
)

enum class ChatRole {
    User,
    Assistant,
    System,
}

data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    val timestamp: String,
    val imageUri: String? = null,
    val isStreaming: Boolean = false,
)

enum class DownloadStatus {
    Idle,
    Downloading,
    Completed,
    Error,
}

data class DownloadUiState(
    val status: DownloadStatus = DownloadStatus.Idle,
    val errorMessage: String? = null,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
) {
    val isDownloading: Boolean
        get() = status == DownloadStatus.Downloading

    val progressPercent: Int
        get() = if (totalBytes > 0L) {
            ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            0
        }
}

data class ChatUiState(
    val isDownloading: Boolean = false,
    val isModelReady: Boolean = false,
    val isProcessing: Boolean = false,
    val selectedImageUri: String? = null,
    val errorMessage: String? = null,
    val errorId: Long = 0L,
    val modelDescriptor: ModelDescriptor? = null,
)

data class SettingsUiState(
    val deviceInfo: List<Pair<String, String>> = emptyList(),
    val backendInfo: List<Pair<String, String>> = emptyList(),
    val modelInfo: List<Pair<String, String>> = emptyList(),
    val cacheInfo: List<Pair<String, String>> = emptyList(),
)

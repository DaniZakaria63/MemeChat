package `fun`.walawe.memechat.model

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
    val reasoning: String = "",
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
    val fileName: String? = null,
    val fileIndex: Int = 0,
    val fileCount: Int = 0,
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
    val isNewConversation: Boolean = true,
    val isProcessing: Boolean = false,
    val selectedImageUri: String? = null,
    val error: String? = null,
    val isThinkingEnabled: Boolean = false,
)

data class SettingsUiState(
    val deviceInfo: List<Pair<String, String>> = emptyList(),
    val backendInfo: List<Pair<String, String>> = emptyList(),
    val modelInfo: List<Pair<String, String>> = emptyList(),
    val cacheInfo: List<Pair<String, String>> = emptyList(),
)

sealed class Screen(val route: String) {
    object Download : Screen("download")
    object Chat : Screen("chat")
    object Settings : Screen("settings")
}
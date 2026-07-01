package `fun`.walawe.memechat.model

enum class ChatRole {
    User,
    Assistant,
    System,
}

fun String.getChatRole(): ChatRole =
    when (this) {
        "User" -> ChatRole.User
        "Assistant" -> ChatRole.Assistant
        else -> ChatRole.System
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
    InsufficientStorage,
    InsufficientRam,
}

data class DownloadUiState(
    val status: DownloadStatus = DownloadStatus.Idle,
    val errorMessage: String? = null,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val fileName: String? = null,
    val fileIndex: Int = 0,
    val fileCount: Int = 0,
    val compatibilityMessage: String? = null,
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

enum class WebSearchMode {
    None, Search, Fetch
}

data class ChatUiState(
    val isNewConversation: Boolean = true,
    val isProcessing: Boolean = false,
    val selectedImageUri: String? = null,
    val error: String? = null,
    val isThinkingEnabled: Boolean = false,
    val webSearchMode: WebSearchMode = WebSearchMode.None,
)

data class ConversationHistory(
    val id: String,
    val title: String,
    val preview: String,
    val time: String,
)

data class AboutInfo(
    val versionName: String = "",
    val versionCode: Int = 0,
)

data class SettingsUiState(
    val deviceInfo: List<Pair<String, String>> = emptyList(),
    val backendInfo: List<Pair<String, String>> = emptyList(),
    val modelInfo: List<Pair<String, String>> = emptyList(),
    val cacheInfo: List<Pair<String, String>> = emptyList(),
    val aboutInfo: AboutInfo? = null,
)

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Download : Screen("download")
    object Chat : Screen("chat")
    object Settings : Screen("settings")
}

sealed interface OnboardingCheckResult {
    data object Pending : OnboardingCheckResult
    data object Running : OnboardingCheckResult
    data class Passed(val message: String) : OnboardingCheckResult
    data class Failed(val message: String) : OnboardingCheckResult
}

sealed interface SpeedResult {
    data object NotChecked : SpeedResult
    data object Checking : SpeedResult
    data object Good : SpeedResult
    data object Okay : SpeedResult
    data object Weak : SpeedResult
    data object Unknown : SpeedResult
}

data class OnboardingState(
    val currentPage: Int = 0,
    val storageCheck: OnboardingCheckResult = OnboardingCheckResult.Pending,
    val ramCheck: OnboardingCheckResult = OnboardingCheckResult.Pending,
    val speedCheck: SpeedResult = SpeedResult.NotChecked,
    val onboardingCompleted: Boolean = false,
)


package `fun`.walawe.memechat.ui.screen

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import `fun`.walawe.memechat.R
import `fun`.walawe.memechat.model.DownloadStatus
import `fun`.walawe.memechat.model.DownloadUiState
import `fun`.walawe.memechat.presenter.DownloadViewModel
import `fun`.walawe.memechat.service.DownloadServiceState
import `fun`.walawe.memechat.service.ModelDownloadService
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SuccessGreen = Color(0xFF4CAF50)

@Composable
fun DownloadScreen(
    viewModel: DownloadViewModel = hiltViewModel(),
    onCompleted: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val currentState = when (uiState.status) {
        DownloadStatus.Completed -> DownloadState.SUCCESS
        DownloadStatus.Downloading,
        DownloadStatus.Idle -> DownloadState.LOADING

        DownloadStatus.InsufficientStorage,
        DownloadStatus.InsufficientRam -> DownloadState.ERROR

        DownloadStatus.Error -> DownloadState.ERROR
    }

    LaunchedEffect(Unit) {
        if (uiState.status == DownloadStatus.Idle) {
            DownloadServiceState.reset()
            val intent = Intent(ctx, ModelDownloadService::class.java)
            ContextCompat.startForegroundService(ctx, intent)
        }
    }

    LaunchedEffect(currentState) {
        if (currentState == DownloadState.SUCCESS) {
            delay(700)
            onCompleted()
        }
    }

    AnimatedContent(
        targetState = currentState,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "DownloadStateTransition"
    ) { state ->
        when (state) {
            DownloadState.SUCCESS -> DownloadLoadingScreen(isComplete = true, uiState = uiState)
            DownloadState.ERROR -> DownloadErrorScreen(
                message = uiState.errorMessage.orEmpty()
                    .ifEmpty { uiState.compatibilityMessage.orEmpty() }
                    .ifEmpty { "Please retry." },
                onRetry = {
                    scope.launch {
                        DownloadServiceState.reset()
                        val intent = Intent(ctx, ModelDownloadService::class.java)
                        ContextCompat.startForegroundService(ctx, intent)
                    }
                }
            )

            else -> DownloadLoadingScreen(isComplete = false, uiState = uiState)
        }
    }
}

@Composable
private fun DownloadLoadingScreen(isComplete: Boolean, uiState: DownloadUiState) {
    val compositionRes = if (isComplete) R.raw.lottieflow_success else R.raw.lottieflow_loading
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(compositionRes))
    val progress by animateLottieCompositionAsState(
        composition,
        iterations = if (isComplete) 1 else Int.MAX_VALUE
    )

    val tintColor = if (isComplete) SuccessGreen else MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LottieAnimation(
                composition = composition,
                progress = { progress },
                modifier = Modifier
                    .size(160.dp)
                    .graphicsLayer {
                        colorFilter = ColorFilter.tint(tintColor)
                    },
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = if (isComplete) "Download Complete" else "Downloading AI Model",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (!isComplete) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.download_connection_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (!uiState.fileName.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.fileName.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                if (uiState.fileCount > 0 && uiState.fileIndex > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "File ${uiState.fileIndex} of ${uiState.fileCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                if (uiState.totalBytes > 0L) {
                    Text(
                        text = "${formatBytes(uiState.bytesDownloaded)} / ${formatBytes(uiState.totalBytes)} (${uiState.progressPercent}%)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                } else if (uiState.bytesDownloaded > 0L) {
                    Text(
                        text = "${formatBytes(uiState.bytesDownloaded)} downloaded",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                )
            }
        }
    }
}

@Composable
private fun DownloadErrorScreen(
    message: String,
    onRetry: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(72.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Download Failed",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(modifier = Modifier.height(32.dp))
            FilledTonalButton(
                onClick = onRetry,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.height(48.dp)
            ) {
                Icon(
                    Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(
                        ButtonDefaults.IconSize
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retry Download")
            }
        }
    }
}

private enum class DownloadState { LOADING, ERROR, SUCCESS }

private fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.US, "%.2f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.US, "%.2f KB", bytes / kb)
        else -> "$bytes B"
    }
}

@Preview
@Composable
fun PreviewDownloadErrorScreen() {
    DownloadErrorScreen("IllegalArgument") {

    }
}

@Preview
@Composable
fun PreviewDownloadLoadingScreen() {
    DownloadLoadingScreen(isComplete = false, uiState = DownloadUiState())
}

@Preview
@Composable
fun PreviewDownloadCompleteScreen() {
    DownloadLoadingScreen(isComplete = true, uiState = DownloadUiState())
}

package `fun`.walawe.memechat.ui.screen

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import `fun`.walawe.memechat.R
import `fun`.walawe.memechat.presenter.DownloadViewModel
import kotlinx.coroutines.delay

@Composable
fun DownloadScreen(
    viewModel: DownloadViewModel = hiltViewModel(),
    onCompleted: () -> Unit
) {

    val isDownloading by viewModel.isDownloading.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorState.collectAsStateWithLifecycle()

    val currentState = when {
        errorMessage != null -> DownloadState.ERROR
        isDownloading -> DownloadState.LOADING
        else -> DownloadState.SUCCESS
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
            DownloadState.SUCCESS -> DownloadLoadingScreen(isComplete = true)
            DownloadState.ERROR -> DownloadErrorScreen(
                message = errorMessage.orEmpty().ifEmpty { "Please retry." },
                onRetry = { }
            )

            else -> DownloadLoadingScreen(isComplete = false)
        }
    }
}


@Composable
private fun DownloadLoadingScreen(isComplete: Boolean) {
    val compositionRes = if (isComplete) R.raw.lottieflow_success else R.raw.lottieflow_loading
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(compositionRes))
    val progress by animateLottieCompositionAsState(
        composition,
        iterations = if (isComplete) 1 else Int.MAX_VALUE
    )

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
                modifier = Modifier.size(160.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = if (isComplete) "Download Complete" else "Downloading AI Model",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!isComplete) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "This may take a moment depending on your connection.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
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
                color = MaterialTheme.colorScheme.onErrorContainer
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


private enum class DownloadState { LOADING, ERROR, COMPLETED, SUCCESS }


@Preview
@Composable
fun PreviewDownloadErrorScreen(){
    DownloadErrorScreen("IllegalArgument") {

    }
}

@Preview
@Composable
fun PreviewDownloadLoadingScreen(){
    DownloadLoadingScreen(isComplete = false)
}

@Preview
@Composable
fun PreviewDownloadCompleteScreen(){
    DownloadLoadingScreen(isComplete = true)
}
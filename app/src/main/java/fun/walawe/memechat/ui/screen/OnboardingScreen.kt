package `fun`.walawe.memechat.ui.screen

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.core.app.ActivityCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `fun`.walawe.memechat.R
import `fun`.walawe.memechat.model.OnboardingCheckResult
import `fun`.walawe.memechat.model.SpeedResult
import `fun`.walawe.memechat.presenter.OnboardingViewModel
import `fun`.walawe.memechat.service.ModelDownloadService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SuccessGreen = Color(0xFF4CAF50)
private val WarningYellow = Color(0xFFFFC107)

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel = hiltViewModel(),
    onCompleted: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { 3 }, initialPage = state.currentPage)

    var notificationPermissionDenied by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (!granted) {
                notificationPermissionDenied = true
                if (!ActivityCompat.shouldShowRequestPermissionRationale(ctx as Activity, Manifest.permission.POST_NOTIFICATIONS)) {
                    Log.w("Onboarding", "POST_NOTIFICATIONS permanently denied")
                } else {
                    Log.w("Onboarding", "POST_NOTIFICATIONS denied")
                }
            }
        },
    )

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(state.onboardingCompleted) {
        if (state.onboardingCompleted) {
            delay(300)
            onCompleted()
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        viewModel.onPageChanged(pagerState.currentPage)
    }

    LaunchedEffect(state.currentPage) {
        pagerState.animateScrollToPage(state.currentPage)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
        Text(
            text = "MemeChat",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) { page ->
                when (page) {
                    0 -> OnboardingSlide(
                        icon = Icons.Default.Psychology,
                        title = "Private AI",
                        description = stringResource(R.string.onboarding_desc_private_ai),
                    )
                    1 -> OnboardingSlide(
                        icon = Icons.Default.AutoAwesome,
                        title = "Smart Reviewer",
                        description = stringResource(R.string.onboarding_desc_smart_reviewer),
                    )
                    2 -> CheckSlide(
                        storageCheck = state.storageCheck,
                        ramCheck = state.ramCheck,
                        speedCheck = state.speedCheck,
                        onOpenFileManager = {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(
                                    android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"),
                                    "vnd.android.document/directory"
                                )
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            ctx.startActivity(Intent.createChooser(intent, "Open File Manager"))
                        },
                        onCloseApp = {
                            (ctx as? Activity)?.finishAffinity()
                        },
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth()
                    .then(Modifier.navigationBarsPadding()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(3) { index ->
                        val isSelected = pagerState.currentPage == index
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isSelected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier
                                .width(if (isSelected) 32.dp else 8.dp)
                                .height(8.dp),
                        ) {}
                    }
                }

                Spacer(Modifier.height(24.dp))

                if (pagerState.currentPage < 2) {
                    Button(
                        onClick = { viewModel.nextPage() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Next")
                    }
                } else {
                    val allDone by remember {
                        derivedStateOf {
                            state.storageCheck is OnboardingCheckResult.Passed ||
                                    state.storageCheck is OnboardingCheckResult.Failed
                        }
                    }
                    if (allDone) {
                        Button(
                            onClick = { scope.launch {
                                viewModel.getStarted()
                                val intent = Intent(ctx, ModelDownloadService::class.java)
                                ContextCompat.startForegroundService(ctx, intent)
                            } },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Get Started")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingSlide(
    icon: ImageVector,
    title: String,
    description: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CheckSlide(
    storageCheck: OnboardingCheckResult,
    ramCheck: OnboardingCheckResult,
    speedCheck: SpeedResult,
    onOpenFileManager: () -> Unit,
    onCloseApp: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.onboarding_check_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_check_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        CheckCard(
            title = "Storage",
            description = "Need at least 2 GB free space",
            result = storageCheck,
            actionLabel = "Open File Manager",
            actionIcon = Icons.Default.FolderOpen,
            onAction = onOpenFileManager,
        )
        Spacer(Modifier.height(12.dp))
        CheckCard(
            title = "Memory",
            description = "Need at least 1 GB free RAM",
            result = ramCheck,
            actionLabel = "Close App",
            actionIcon = Icons.Default.Close,
            onAction = onCloseApp,
        )
        Spacer(Modifier.height(12.dp))
        SpeedCard(speedCheck = speedCheck)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CheckCard(
    title: String,
    description: String,
    result: OnboardingCheckResult,
    actionLabel: String,
    actionIcon: ImageVector,
    onAction: () -> Unit,
) {
    val borderColor = when (result) {
        is OnboardingCheckResult.Passed -> SuccessGreen
        is OnboardingCheckResult.Failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.5.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                when (result) {
                    is OnboardingCheckResult.Pending -> {
                        Icon(
                            Icons.Default.HourglassEmpty,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    is OnboardingCheckResult.Running -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    is OnboardingCheckResult.Passed -> {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = SuccessGreen,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    is OnboardingCheckResult.Failed -> {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            if (result is OnboardingCheckResult.Failed) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onAction,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(actionIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun SpeedCard(speedCheck: SpeedResult) {
    val borderColor = when (speedCheck) {
        is SpeedResult.Good -> SuccessGreen
        is SpeedResult.Okay -> WarningYellow
        is SpeedResult.Weak -> MaterialTheme.colorScheme.error
        is SpeedResult.Unknown -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val statusText = when (speedCheck) {
        is SpeedResult.Good -> "Connection looks great"
        is SpeedResult.Okay -> "Connection looks good"
        is SpeedResult.Weak -> stringResource(R.string.onboarding_speed_weak)
        is SpeedResult.Unknown -> "Could not measure speed"
        else -> ""
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.5.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Connection Speed",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "Testing download speed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                when (speedCheck) {
                    is SpeedResult.NotChecked -> {
                        Icon(
                            Icons.Default.HourglassEmpty,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    is SpeedResult.Checking -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    is SpeedResult.Good -> {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = SuccessGreen,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    is SpeedResult.Okay -> {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = WarningYellow,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    is SpeedResult.Weak -> {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    is SpeedResult.Unknown -> {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            if (statusText.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = borderColor,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

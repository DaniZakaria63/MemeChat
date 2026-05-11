package `fun`.walawe.memechat.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets.statusBars
            .add(WindowInsets.navigationBars)
            .add(WindowInsets.displayCutout),
        topBar = {
            CenterAlignedTopAppBar(
                modifier = Modifier.fillMaxWidth().shadow(4.dp),
                title = {
                    Text(
                        text = "MemeLM Settings",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                windowInsets = WindowInsets.statusBars.only(WindowInsetsSides.Top),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(vertical = 12.dp, horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SettingsCard {
                    SettingRow(
                        title = "Messages",
                        subtitle = null,
                        isChecked = false,
                        onCheckedChange = {}
                    )
                }
            }

            // 2. Notifications Section
            item {
                SettingsCard{
                    Column {
                        SettingRow(
                            title = "Group notifications",
                            subtitle = "Notifications from groups on",
                            isChecked = true,
                            onCheckedChange = {}
                        )
                        SettingRow(
                            title = "Direct messages",
                            subtitle = null,
                            isChecked = true,
                            onCheckedChange = {}
                        )
                        SettingRow(
                            title = "Open messages",
                            subtitle = "Allow messages from everyone",
                            isChecked = false,
                            onCheckedChange = {}
                        )
                    }
                }
            }

            // 3. Local Model Information Section
            item {
                SettingsCard{
                    Column {
                        Text(
                            text = "Local Model",
                            fontSize = 18.sp,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        InfoRow(label = "Model", value = "Llama-3-8B-Instruct")
                        Spacer(modifier = Modifier.height(4.dp))
                        InfoRow(label = "Format", value = "GGUF (Q4_K_M)")
                        Spacer(modifier = Modifier.height(4.dp))
                        InfoRow(label = "Backend", value = "Vulkan / OpenCL")
                        Spacer(modifier = Modifier.height(4.dp))
                        InfoRow(label = "Size", value = "4.2 GB")
                    }
                }
            }

            // 4. Device Details Section
            item {
                SettingsCard{
                    Column {
                        Text(
                            text = "Device Details",
                            fontSize = 18.sp,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        InfoRow(label = "RAM Usage", value = "6.2 GB / 12 GB")
                        Spacer(modifier = Modifier.height(4.dp))
                        InfoRow(label = "Storage", value = "128 GB / 256 GB")
                        Spacer(modifier = Modifier.height(4.dp))
                        InfoRow(label = "Driver", value = "Adreno 650 v3.0")
                    }
                }
            }

            // 5. Clear Cache and Data
            item {
                Button(
                    onClick = { /* Handle clear */ },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(
                        text = "Clear Cache & Data",
                        fontSize = 16.sp,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onError,
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun SettingsCard(
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            content()
        }
    }
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String?,
    isChecked: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        Box(modifier = Modifier.padding(start = 16.dp)) {
            Switch(
                checked = isChecked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSecondary,
                    uncheckedTrackColor = MaterialTheme.colorScheme.secondary
                )
            )
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            fontSize = 15.sp,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreviewLight() {
    MaterialTheme {
        Surface {
            SettingsScreen{}
        }
    }
}
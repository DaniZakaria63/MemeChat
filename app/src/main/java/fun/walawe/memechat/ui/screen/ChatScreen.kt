package `fun`.walawe.memechat.ui.screen

import android.content.res.Configuration
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import `fun`.walawe.memechat.R
import `fun`.walawe.memechat.model.ChatMessage
import `fun`.walawe.memechat.model.ChatRole
import `fun`.walawe.memechat.model.ChatUiState
import `fun`.walawe.memechat.presenter.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.core.net.toUri

private data class DummyConversation(
    val id: String,
    val title: String,
    val preview: String,
    val time: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    onOpenSettings: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    var inputText by remember { mutableStateOf("") }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val conversations = remember {
        listOf(
            DummyConversation("c1", "Qwen Chat", "Latest: cat meme", "10:33"),
            DummyConversation("c2", "Work notes", "Summarize standup", "09:10"),
            DummyConversation("c3", "Trip planning", "Best places in Seoul", "Yesterday"),
            DummyConversation("c4", "Ideas", "Brand voice notes", "Yesterday")
        )
    }
    var selectedConversationId by remember { mutableStateOf("c1") }

    val context = LocalContext.current
    val placeholderUri = remember {
        "android.resource://${context.packageName}/${R.drawable.placeholder}".toUri()
    }

    ChatScreenContent(
        uiState = uiState,
        messages = messages,
        inputText = inputText,
        onInputChange = { inputText = it },
        drawerState = drawerState,
        onOpenDrawer = { scope.launch { drawerState.open() } },
        onOpenSettings = onOpenSettings,
        conversations = conversations,
        selectedConversationId = selectedConversationId,
        onNewChat = {
            selectedConversationId = "c1"
            // Backend integration point: create new conversation
        },
        onSelectConversation = { id ->
            selectedConversationId = id
            // Backend integration point: load conversation
        },
        onAttach = {
            viewModel.setSelectedImageUri(placeholderUri.toString())
            // Backend integration point: open image picker
        },
        onRemoveImage = { viewModel.setSelectedImageUri(null) },
        onSend = {
            if (inputText.isNotBlank() && !uiState.isProcessing) {
                val selectedImage = uiState.selectedImageUri
                if (selectedImage == null) {
                    viewModel.sendMessage(inputText)
                } else {
                    viewModel.sendImageMessage(inputText, selectedImage)
                }
                inputText = ""
            }
        },
        onDismissError = viewModel::consumeError,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreenContent(
    uiState: ChatUiState,
    messages: List<ChatMessage>,
    inputText: String,
    onInputChange: (String) -> Unit,
    drawerState: DrawerState,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    conversations: List<DummyConversation>,
    selectedConversationId: String,
    onNewChat: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onAttach: () -> Unit,
    onRemoveImage: () -> Unit,
    onSend: () -> Unit,
    onDismissError: () -> Unit,
) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.7f),
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                DrawerContent(
                    conversations = conversations,
                    selectedConversationId = selectedConversationId,
                    onNewChat = onNewChat,
                    onSelectConversation = onSelectConversation,
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(text = "Qwen Chat", style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(imageVector = Icons.Filled.Menu, contentDescription = "Open drawer")
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(imageVector = Icons.Outlined.Settings, contentDescription = "Settings")
                        }
                    }
                )
            },
            bottomBar = {
                InputBar(
                    inputText = inputText,
                    isProcessing = uiState.isProcessing,
                    selectedImageUri = uiState.selectedImageUri,
                    onInputChange = onInputChange,
                    onAttach = onAttach,
                    onRemoveImage = onRemoveImage,
                    onSend = onSend
                )
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                MessageList(messages = messages)
                if (uiState.isProcessing) {
                    LoadingOverlay()
                }
                if (uiState.errorMessage != null) {
                    TransientErrorDialog(
                        message = uiState.errorMessage,
                        errorId = uiState.errorId,
                        onDismiss = onDismissError,
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerContent(
    conversations: List<DummyConversation>,
    selectedConversationId: String,
    onNewChat: () -> Unit,
    onSelectConversation: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Conversations", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = onNewChat,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "New Chat")
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(conversations) { convo ->
                val selected = convo.id == selectedConversationId
                val containerColor = if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                }
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(containerColor)
                        .clickable { onSelectConversation(convo.id) }
                        .padding(12.dp)
                        .fillMaxWidth()
                ) {
                    Text(text = convo.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = convo.preview,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = convo.time,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageList(messages: List<ChatMessage>) {
    val listState = rememberLazyListState()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        reverseLayout = true,
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        items(messages) { message ->
            MessageBubble(message = message)
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == ChatRole.User
    val bubbleShape = if (isUser) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
    }
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Card(
            shape = bubbleShape,
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            elevation = if (isUser) CardDefaults.cardElevation(0.dp) else CardDefaults.cardElevation(1.dp),
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                message.imageUri?.let {
                    val context = LocalContext.current
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(it)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Attachment",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        placeholder = painterResource(id = R.drawable.placeholder)
                    )
                }
                Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = message.timestamp,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

@Composable
private fun InputBar(
    inputText: String,
    isProcessing: Boolean,
    selectedImageUri: String?,
    onInputChange: (String) -> Unit,
    onAttach: () -> Unit,
    onRemoveImage: () -> Unit,
    onSend: () -> Unit
) {
    Card(
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            if (selectedImageUri != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    val context = LocalContext.current
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(selectedImageUri)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Selected image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        placeholder = painterResource(id = R.drawable.placeholder)
                    )
                    IconButton(
                        onClick = onRemoveImage,
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(imageVector = Icons.Filled.Close, contentDescription = "Remove image")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                IconButton(onClick = onAttach, enabled = !isProcessing) {
                    Icon(imageVector = Icons.Filled.AttachFile, contentDescription = "Attach image")
                }
                TextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    placeholder = { Text(text = "Message Qwen...") }
                )
                IconButton(
                    onClick = onSend,
                    enabled = inputText.isNotBlank() && !isProcessing
                ) {
                    Icon(imageVector = Icons.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun LoadingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center
    ) {
        val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.lottieflow_loading))
        LottieAnimation(
            composition = composition,
            modifier = Modifier.size(120.dp),
            iterations = Int.MAX_VALUE
        )
    }
}

@Composable
private fun TransientErrorDialog(
    message: String,
    errorId: Long,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(errorId) {
        delay(1000)
        onDismiss()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.TopStart
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            tonalElevation = 2.dp
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenPreviewLight() {
    MaterialTheme {
        Surface {
            ChatScreenContent(
                uiState = ChatUiState(isProcessing = true),
                messages = listOf(
                    ChatMessage(
                        id = "m1",
                        role = ChatRole.User,
                        text = "Hello Qwen!",
                        timestamp = "10:30",
                    ),
                    ChatMessage(
                        id = "m2",
                        role = ChatRole.Assistant,
                        text = "Hi there!",
                        timestamp = "10:31",
                    )
                ),
                inputText = "",
                onInputChange = {},
                drawerState = rememberDrawerState(DrawerValue.Closed),
                onOpenDrawer = {},
                onOpenSettings = {},
                conversations = emptyList(),
                selectedConversationId = "",
                onNewChat = {},
                onSelectConversation = {},
                onAttach = {},
                onRemoveImage = {},
                onSend = {},
                onDismissError = {},
            )
        }
    }
}

@Preview(
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun ChatScreenPreviewDark() {
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy()) {
        Surface(color = MaterialTheme.colorScheme.background) {
            ChatScreenContent(
                uiState = ChatUiState(),
                messages = emptyList(),
                inputText = "",
                onInputChange = {},
                drawerState = rememberDrawerState(DrawerValue.Closed),
                onOpenDrawer = {},
                onOpenSettings = {},
                conversations = emptyList(),
                selectedConversationId = "",
                onNewChat = {},
                onSelectConversation = {},
                onAttach = {},
                onRemoveImage = {},
                onSend = {},
                onDismissError = {},
            )
        }
    }
}
